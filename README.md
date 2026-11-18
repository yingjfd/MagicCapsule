# MagicCapsule

![MagicCapsule 封面](docs/cover-hero-1600.png)

> 让荣耀 MagicOS 的**灵动胶囊不要收起**：胶囊出现后一直保持展开的长条形态（图标 + 文字），
> 不会过一会儿缩成只显示图标的小圆点。

一个基于 **libxposed API 102** 的 LSPosed 模块，只作用于 `com.android.systemui`。
只改一个方法入参，不碰动画与绘制。

---

## 为什么会有这个模块

MagicOS 的灵动胶囊（音乐、计时、通知等场景）是「展开态 ↔ 收起态」两段式：

| 状态 | 形态 |
| --- | --- |
| 展开 | 长条 pill：左侧图标 + 右侧信息/声波 |
| 收起 | 只剩一个图标圆点（信息全部消失） |

系统会在胶囊出现一段时间后自动收起。对想持续看到胶囊里信息的人来说，
「刚看清就缩没了」比「文字需要滚动」更难受。

本模块把**收起**这一步拦下来，让胶囊一直停在展开态。

## 原理

反编译 `SystemUI.apk`（AAK-AN00 / MagicOS 11 / Android 17）后定位到唯一的状态开关：

```
com.hihonor.systemui.dynamicserver.view.dynamiccapsule.HnDynamicCapsuleSetLayout
    #setDisplayState(int displayState, boolean animate)
```

状态值语义与直觉相反：

* `0` = **展开**（长条）
* `1` = **收起**（圆点）

Hook 逻辑只有一句：**把入参 `1` 改成 `0`**。

```java
int state = ((Number) chain.getArgs().get(0)).intValue();
if (state == STATE_COLLAPSE && keepExpanded()) {
    return chain.proceed(new Object[] { STATE_EXPAND, animate });
}
```

选择这个切入点而不是 `setToCollapseState()` 的原因：

1. 它是「展开/收起」的**唯一决策点**，内部已经处理好动画进行中、100ms 重试、
   `mDisplayState` 赋值、图标可见性等全部状态机细节；
2. 我们只换入参，原生状态机一行不改 —— 出问题时关掉模块即恢复原生行为；
3. 不触碰布局常量、View 层级、Path 圆角、动画时长这些高风险区域。

## 环境

* 荣耀 MagicOS 设备（实测：AAK-AN00 / MagicOS 11 / Android 17）
* KernelSU 或 Magisk + LSPosed（API 102，实测 LSPosed 2.2.1）
* 作用域：`com.android.systemui`（APK 内已通过 `scope.list` 固定）

## 安装

1. 下载安装包：[`dist/MagicCapsule-v1.0.0.apk`](dist/MagicCapsule-v1.0.0.apk)
   （或从 Releases 下载对应版本）；
2. 在 LSPosed 管理器里**启用模块**（作用域会自动带上 `com.android.systemui`）；
3. **软重启 SystemUI**（不需要重启手机）：
   ```bash
   adb shell su -c 'killall com.android.systemui'
   ```
   或者：设置 → 应用 → 显示系统进程 → 系统界面 → 强行停止；
4. 播放音乐，胶囊出现后观察它是否一直保持长条。

## 使用

桌面图标打开设置页，只有一个开关 **「胶囊不收起」**（默认开启）。
改动后需要再次软重启 SystemUI 生效（模块运行在 SystemUI 进程里）。

## 安全

* 每个 hook 回调都 `try/catch(Throwable)`，异常绝不抛回 SystemUI；
* 类/方法不存在时静默跳过 —— 换机型/换版本只是失效，不会让系统异常；
* 只改方法入参，不改字段、不改布局参数、不注册任何广播/服务；
* 关闭模块（或关掉开关 + 软重启）即回到原生行为。

## 从源码构建

```bash
cd MagicCapsule
./build.sh              # debug
./build.sh assembleRelease
```

产物：

* `app/build/outputs/apk/debug/app-debug.apk`
* `app/build/outputs/apk/release/app-release.apk`

构建环境（本机实测）：

* Gradle 8.9 + AGP 8.5.2，`JAVA_HOME='D:\Zulu\21'`（必须是带 `jmods/jlink` 的完整 JDK 21；
  `D:\Zulu` 是 JDK 25，Gradle 8.9 不支持）；
* 依赖只有 `io.github.libxposed:api:102.0.0`（`compileOnly`，运行时由 LSPosed 提供）；
* 网络不可用时用 `--offline`，依赖全部来自 Gradle 缓存（详见 `build.sh`）。

## 逆向资料

本仓库同时保留了分析产出的证据链，方便复现或适配新版本：

* `docs/capsule-status-analysis.md` —— 状态栏胶囊布局与状态机分析
* `docs/capsule-dynamic-analysis.md` —— 展开态/灵动胶囊（布局常量、状态机、风险点）完整分析
* `_capsule/` —— 从手机提取的 `SystemUI.apk`、jadx 反编译结果、资源表 dump、验证截图

## 免责声明

本项目由 AI 生成，仅供学习研究 Android Hook / 逆向技术使用。
与荣耀公司（HONOR）无任何关联；相关商标与系统界面版权归原厂所有。
使用本模块产生的一切后果由使用者自行承担。

## License

MIT
