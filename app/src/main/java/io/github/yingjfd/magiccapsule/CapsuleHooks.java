package io.github.yingjfd.magiccapsule;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * MagicCapsule —— 让 MagicOS 的灵动胶囊「不收起」。
 *
 * <h3>现象</h3>
 * <p>音乐/歌词等场景下，胶囊先以「长条 pill（展开态）」出现，随后系统会把它收缩成
 * 一个只显示图标的小圆点（收起态）。用户希望它一直保持展开的长条形态，不要缩进去。</p>
 *
 * <h3>原理（反编译 SystemUI 得到）</h3>
 * <p>展开/收起由
 * {@code com.hihonor.systemui.dynamicserver.view.dynamiccapsule.HnDynamicCapsuleSetLayout#setDisplayState(int, boolean)}
 * 决定，状态值语义与直觉相反：</p>
 * <ul>
 *   <li>{@code 0} = <b>展开</b>（长条 pill，显示图标 + 文字/声波）</li>
 *   <li>{@code 1} = <b>收起</b>（只剩一个图标圆点）</li>
 * </ul>
 * <p>本模块只做一件事：把这个方法的入参从 {@code 1}（收起）改成 {@code 0}（展开）。
 * 其它任何逻辑（宽度、文字、动画、绘制、数据链路）一律不碰。</p>
 *
 * <h3>安全约束</h3>
 * <ul>
 *   <li>所有 hook 回调都 {@code try/catch(Throwable)}，异常绝不抛回 SystemUI；</li>
 *   <li>类/方法找不到时静默跳过（换机型/换版本只是失效，不影响系统）；</li>
 *   <li>不改字段、不改布局参数，只改一次方法入参 —— 出问题时把模块关掉即可恢复。</li>
 * </ul>
 */
public final class CapsuleHooks {

    private static final String TAG = "MagicCapsule";

    /** 灵动胶囊的「集合布局」，展开/收起的开关就在它的 setDisplayState 上。 */
    private static final String CLS_DYNAMIC_CAPSULE_SET_LAYOUT =
            "com.hihonor.systemui.dynamicserver.view.dynamiccapsule.HnDynamicCapsuleSetLayout";

    /** 单颗胶囊的布局（setToExpandState/setToCollapseState 的参数类型）。 */
    private static final String CLS_DYNAMIC_CAPSULE_LAYOUT =
            "com.hihonor.systemui.dynamicserver.view.dynamiccapsule.HnDynamicCapsuleLayout";

    /** 原生状态值：0 = 展开（长条），1 = 收起（圆点）。 */
    private static final int STATE_EXPAND = 0;
    private static final int STATE_COLLAPSE = 1;

    private static volatile boolean installed;

    /** 设置缓存：避免同一帧内反复跨进程读偏好。 */
    private static final long CACHE_TTL_MS = 3000L;
    private static volatile boolean haveCachedValue;
    private static volatile boolean cachedValue = SettingsStore.DEFAULT_KEEP_EXPANDED;
    private static volatile long cachedAt;

    private CapsuleHooks() {
    }

    /**
     * 安装 hook。任何失败都只写日志，绝不向宿主抛出。
     *
     * @param moduleInstance LSPosed 模块实例
     * @param classLoader    SystemUI 的 ClassLoader（必须用它加载宿主类）
     * @param packageName    目标包名，仅用于日志
     */
    public static void install(XposedModule moduleInstance, ClassLoader classLoader, String packageName) {
        try {
            if (installed) {
                log(moduleInstance, Log.INFO, "install 跳过：本进程已安装 (pkg=" + packageName + ")");
                return;
            }
            installed = true;
            log(moduleInstance, Log.INFO, "install pkg=" + packageName);

            if (hookDisplayState(moduleInstance, classLoader)) {
                // 第二条入口：有些路径不经过 setDisplayState，而是直接 setToCollapseState
                //（例如 updateForCollapseState / onCollapseState）
                hookCollapseState(moduleInstance, classLoader);
                log(moduleInstance, Log.INFO, "install 完成：胶囊收起已被拦截");
            } else {
                log(moduleInstance, Log.WARN, "install 未生效：目标方法不存在（本机型/版本不适用）");
            }
        } catch (Throwable t) {
            log(moduleInstance, Log.ERROR, "install 失败（已吞掉，SystemUI 不受影响）", t);
        }
    }

    /**
     * 把 {@code setDisplayState(1, ...)}（收起）改成 {@code setDisplayState(0, ...)}（展开）。
     *
     * <p>选择这个方法而不是 {@code setToCollapseState} 的原因：它是收缩的唯一决策点，
     * 内部会处理动画是否进行中、100ms 重试、mDisplayState 赋值等全部状态机细节；
     * 我们只换入参，原生逻辑一点不变，风险最小。</p>
     */
    private static boolean hookDisplayState(XposedModule moduleInstance, ClassLoader classLoader) {
        try {
            Class<?> setLayoutClass = Class.forName(CLS_DYNAMIC_CAPSULE_SET_LAYOUT, false, classLoader);
            java.lang.reflect.Method setDisplayState =
                    setLayoutClass.getDeclaredMethod("setDisplayState", int.class, boolean.class);
            setDisplayState.setAccessible(true);

            moduleInstance.hook(setDisplayState).intercept(chain -> {
                try {
                    java.util.List<Object> args = chain.getArgs();
                    int state = ((Number) args.get(0)).intValue();
                    if (state == STATE_COLLAPSE && keepExpanded(moduleInstance)) {
                        boolean animate = ((Boolean) args.get(1)).booleanValue();
                        // 只在真正拦截时记一条（正常播放时每次收起一条，量很小）
                        log(moduleInstance, Log.INFO, "拦截收起：1 -> 0 (animate=" + animate + ")");
                        return chain.proceed(new Object[] {STATE_EXPAND, animate});
                    }
                } catch (Throwable ignored) {
                    // 参数异常就按原样执行
                }
                return chain.proceed();
            });
            return true;
        } catch (Throwable t) {
            log(moduleInstance, Log.WARN, "hook setDisplayState 失败：" + t);
            return false;
        }
    }

    /**
     * 第二条收缩入口：{@code setToCollapseState(capsule, animate, flag)} 会被
     * {@code updateForCollapseState()} / {@code onCollapseState()} 直接调用，绕过 setDisplayState。
     * 这里把它重定向到对应的展开方法，保持状态机一致（而不是简单地"什么都不做"）。
     */
    private static boolean hookCollapseState(XposedModule moduleInstance, ClassLoader classLoader) {
        try {
            final Class<?> layoutClass = Class.forName(CLS_DYNAMIC_CAPSULE_SET_LAYOUT, false, classLoader);
            final Class<?> capsuleClass = Class.forName(CLS_DYNAMIC_CAPSULE_LAYOUT, false, classLoader);
            final java.lang.reflect.Method setToCollapse =
                    layoutClass.getDeclaredMethod("setToCollapseState", capsuleClass, boolean.class, int.class);
            final java.lang.reflect.Method setToExpand =
                    layoutClass.getDeclaredMethod("setToExpandState", capsuleClass, boolean.class);
            setToCollapse.setAccessible(true);
            setToExpand.setAccessible(true);

            moduleInstance.hook(setToCollapse).intercept(chain -> {
                try {
                    if (!keepExpanded(moduleInstance)) {
                        return chain.proceed();
                    }
                    Object capsule = chain.getArgs().get(0);
                    boolean animate = ((Boolean) chain.getArgs().get(1)).booleanValue();
                    log(moduleInstance, Log.INFO, "拦截 setToCollapseState -> 展开 (animate=" + animate + ")");
                    setToExpand.invoke(chain.getThisObject(), capsule, animate);
                    return null;
                } catch (Throwable t) {
                    return chain.proceed();
                }
            });
            return true;
        } catch (Throwable t) {
            log(moduleInstance, Log.WARN, "hook setToCollapseState 失败（不影响主路径）：" + t);
            return false;
        }
    }

    /**
     * 读取「胶囊不收起」开关（3 秒缓存）。
     * 收起判断可能在一帧内多次发生，不能每次都读跨进程偏好。
     */
    private static boolean keepExpanded(XposedModule moduleInstance) {
        long now = android.os.SystemClock.uptimeMillis();
        if (haveCachedValue && now - cachedAt < CACHE_TTL_MS) {
            return cachedValue;
        }
        boolean value = SettingsStore.keepExpanded(moduleInstance);
        cachedValue = value;
        cachedAt = now;
        haveCachedValue = true;
        return value;
    }

    /** 模型侧日志：safe 版本，log() 抛异常也不外泄。 */
    static void log(XposedModule moduleInstance, int priority, String message) {
        log(moduleInstance, priority, message, null);
    }

    static void log(XposedModule moduleInstance, int priority, String message, Throwable t) {
        try {
            if (moduleInstance == null) {
                return;
            }
            if (t == null) {
                moduleInstance.log(priority, TAG, message);
            } else {
                moduleInstance.log(priority, TAG, message, t);
            }
        } catch (Throwable ignored) {
            // 日志失败不能影响宿主
        }
    }
}
