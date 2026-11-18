#!/usr/bin/env bash
# ============================================================================
# MagicCapsule 部署脚本（MSYS bash / Git Bash on Windows）
#
#   ./deploy.sh                 # 安装 APK + 软重启 SystemUI
#   ./deploy.sh --no-restart    # 只安装，不动 SystemUI
#   ./deploy.sh --build         # 先构建再安装
#   APK=/path/to/app.apk ./deploy.sh   # 指定任意 APK
#
# 做过的事：adb install -r → （可选）killall com.android.systemui 软重启
# 不做的事：不重启手机、不写系统分区、不动 LSPosed 数据库。
# ============================================================================
set -u

ADB="${ADB:-D:/ADB/platform-tools/adb.exe}"
PKG="io.github.yingjfd.magiccapsule"
TARGET_PKG="com.android.systemui"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_DEFAULT="$HERE/app/build/outputs/apk/debug/app-debug.apk"
APK="${APK:-$APK_DEFAULT}"

RESTART=1
DO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --no-restart) RESTART=0 ;;
    --build)      DO_BUILD=1 ;;
    -h|--help)    sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数: $arg（用 --help 看用法）" >&2; exit 2 ;;
  esac
done

say()  { printf '\033[36m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 0. 可选：先构建 ────────────────────────────────────────────────────────
if [ "$DO_BUILD" = "1" ]; then
  say "先构建：$HERE/build.sh"
  "$HERE/build.sh" || die "构建失败，未安装"
fi

# ── 1. 构建产物必须真实存在 ───────────────────────────────────────────────
[ -f "$APK" ] || die "APK 不存在：$APK
  先构建： JAVA_HOME='D:\\Zulu\\21' ./gradlew.bat --offline --no-daemon assembleDebug"
SIZE_BYTES=$(wc -c < "$APK" | tr -d ' \r')
[ "$SIZE_BYTES" -gt 5000 ] || die "APK 太小（${SIZE_BYTES}B），疑似构建失败：$APK"
say "APK: $APK (${SIZE_BYTES} bytes / $((SIZE_BYTES / 1024)) KB)"

# ── 2. 设备与 adb ─────────────────────────────────────────────────────────
[ -x "$ADB" ] || die "找不到 adb：$ADB（可用 ADB=... 覆盖）"
DEVICES=$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$DEVICES" ] || die "没有已授权的设备，先确认 USB 调试 + adb 授权"
say "设备: $(echo "$DEVICES" | tr '\n' ' ')"

# ── 3. 安装 ───────────────────────────────────────────────────────────────
say "安装 $PKG ..."
if ! "$ADB" install -r "$APK"; then
  die "安装失败。常见原因：
  - LSPosed 里没有授权/启用该模块（安装本身不需要授权，但后续 hook 需要）
  - 已安装的 MagicCapsule 是同 applicationId 但不同签名（先 adb uninstall $PKG）
  - 设备存储/包管理器异常，看 adb logcat | grep PackageManager"
fi
say "安装完成"

# ── 4. 提示 LSPosed 作用域（这一步必须手动做，脚本无法代替）──────────────
cat <<'EOF'
------------------------------------------------------------------
下一步（手动，一次性）：
  1) 打开 LSPosed 管理器 → 模块 → MagicCapsule → 启用
  2) 作用域里只勾选：com.android.systemui
  3) 回到本脚本（或手动）软重启 SystemUI，让新 APK 的 hook 被加载
------------------------------------------------------------------
EOF

# ── 5. 软重启 SystemUI ────────────────────────────────────────────────────
if [ "$RESTART" = "0" ]; then
  warn "已跳过 SystemUI 软重启（--no-restart）。设置改动需要重启 SystemUI 才生效。"
  exit 0
fi

say "3 秒后软重启 SystemUI（不会重启手机；桌面会闪一下）..."
sleep 3

# 首选 killall（KernelSU/Magisk 的 su 一般自带 toybox killall）；
# 失败再退到 pkill -f / am crash 之外的温和方式。
if "$ADB" shell su -c "killall $TARGET_PKG" 2>/dev/null; then
  say "已 killall $TARGET_PKG"
elif "$ADB" shell su -c "pkill -f $TARGET_PKG" 2>/dev/null; then
  say "已 pkill -f $TARGET_PKG"
else
  warn "su 执行失败，尝试 force-stop（部分 ROM 会自动重启 SystemUI，若不自动重启请手动重启手机桌面/SystemUI）"
  "$ADB" shell su -c "am force-stop $TARGET_PKG" || \
    die "无法重启 SystemUI：请确认 KernelSU 已给 shell 授权 root，或手动执行：
      adb shell su -c 'killall com.android.systemui'"
fi

sleep 2
if "$ADB" shell pidof "$TARGET_PKG" >/dev/null 2>&1; then
  say "SystemUI 已恢复运行（pid=$("$ADB" shell pidof "$TARGET_PKG" | tr -d '\r')）"
else
  warn "SystemUI 暂未回到运行状态，通常 5-10 秒内系统会自动拉起；若黑屏/无状态栏请重启手机"
fi

say "完成。日志查看：LSPosed 管理器 → 日志，过滤 tag=MagicCapsule"
