#!/usr/bin/env bash
# ============================================================================
# MagicCapsule 离线构建脚本
#
#   ./build.sh              # assembleDebug（APK 在 app/build/outputs/apk/debug/）
#   ./build.sh assembleRelease
#
# 关键点（本机实测）：
#  1) Gradle 8.9 不能跑在 JDK 25 上；本机 D:\Zulu 是 25.0.2，
#     真正的 JDK 21 在 D:\Zulu\21（AGP 的 JdkImageTransform 还需要 jlink.exe，
#     JetBrains Runtime 之类没有 jmods/jlink 的运行时不行）。
#  2) 构建沙箱里 ~/.gradle 不可写（AGP 默认 debug 签名还要写 ~/.android），
#     所以脚本会自动回退到工程内的 .gradle-home，并用工程内 keystore。
#  3) 全程 --offline：网络 TLS 握手失败，所有依赖必须来自缓存。
# ============================================================================
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE" || exit 1

TASK="${1:-assembleDebug}"

say() { printf '\033[36m[build]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[fail]\033[0m %s\n' "$*" >&2; exit 1; }

# ── JDK：必须是带 jmods/jlink 的完整 JDK 17-22 ─────────────────────────────
pick_jdk() {
  for cand in "${JAVA_HOME:-}" "D:\\Zulu\\21" "D:\\Zulu\\21.0.11" "D:\\Zulu"; do
    [ -n "$cand" ] || continue
    local unix
    unix=$(printf '%s' "$cand" | sed -e 's|\\|/|g' -e 's|^\([A-Za-z]\):|/\L\1|')
    if [ -x "$unix/bin/javac.exe" ] && [ -x "$unix/bin/jlink.exe" ]; then
      printf '%s' "$cand"
      return 0
    fi
  done
  return 1
}

JDK=$(pick_jdk) || die "没找到带 jlink 的完整 JDK（试过 \$JAVA_HOME / D:\\Zulu\\21 / D:\\Zulu）。
  D:\\Zulu 是 JDK 25，Gradle 8.9 不支持；请装 JDK 21 或设置 JAVA_HOME='D:\\Zulu\\21'"
export JAVA_HOME="$JDK"
say "JAVA_HOME=$JAVA_HOME"

# ── GRADLE_USER_HOME：能写 ~/.gradle 就直接用，否则用工程内副本 ────────────
if [ -n "${GRADLE_USER_HOME:-}" ]; then
  say "沿用外部 GRADLE_USER_HOME=$GRADLE_USER_HOME"
elif touch "$HOME/.gradle/.magiccapsule_write_probe" 2>/dev/null; then
  rm -f "$HOME/.gradle/.magiccapsule_write_probe"
  say "使用默认 GRADLE_USER_HOME（~/.gradle 可写）"
else
  LOCAL_GH="$HERE/.gradle-home"
  # 转 Windows 路径：优先 cygpath，退回手工替换（保留盘符！）
  if command -v cygpath >/dev/null 2>&1; then
    export GRADLE_USER_HOME="$(cygpath -w "$LOCAL_GH")"
  else
    export GRADLE_USER_HOME="$(printf '%s' "$LOCAL_GH" | sed 's|^/\([A-Za-z]\)/|\U\1:/|' | sed 's|/|\\|g')"
  fi
  say "~/.gradle 不可写 → GRADLE_USER_HOME=$GRADLE_USER_HOME"
  if [ ! -d "$LOCAL_GH/caches/modules-2" ] || [ ! -d "$LOCAL_GH/wrapper/dists/gradle-8.9-bin" ]; then
    say "从 ~/.gradle 引导本地 Gradle 缓存（约 670MB，只需一次）..."
    mkdir -p "$LOCAL_GH/wrapper/dists" "$LOCAL_GH/caches" || die "无法创建 $LOCAL_GH"
    cp -r "$HOME/.gradle/wrapper/dists/gradle-8.9-bin" "$LOCAL_GH/wrapper/dists/" || die "复制 gradle-8.9 发行版失败"
    cp -r "$HOME/.gradle/caches/modules-2" "$LOCAL_GH/caches/" || die "复制依赖缓存失败"
    say "引导完成"
  fi
fi

# ── 构建 ────────────────────────────────────────────────────────────────────
say "执行: ./gradlew.bat --offline --no-daemon $TASK"
./gradlew.bat --offline --no-daemon --console=plain "$TASK"
RC=$?
[ "$RC" -eq 0 ] || die "构建失败（exit=$RC）"

APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
[ "$TASK" = "assembleRelease" ] && APK="$HERE/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  say "APK: $APK"
  ls -l "$APK"
else
  say "构建完成，但没找到预期 APK 路径（$APK），请检查 build/outputs"
fi
