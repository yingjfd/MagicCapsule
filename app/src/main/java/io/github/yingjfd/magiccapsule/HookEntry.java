package io.github.yingjfd.magiccapsule;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * MagicCapsule 模块入口。
 *
 * <p>由 LSPosed 根据 APK 内 {@code META-INF/xposed/java_init.list} 反射实例化（无参构造），
 * 然后在作用域进程（这里只有 {@code com.android.systemui}）包就绪时回调
 * {@link #onPackageReady(PackageReadyParam)}。</p>
 *
 * <p><b>硬性要求：任何异常都不允许抛回 SystemUI 进程。</b>
 * 本类的所有回调体都包在 try/catch(Throwable) 中；hook 侧同理见 {@link CapsuleHooks}。</p>
 */
public class HookEntry extends XposedModule {

    public static final String TAG = "MagicCapsule";
    public static final String TARGET_PACKAGE = "com.android.systemui";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = "<unknown>";
        try {
            pkg = param.getPackageName();

            // 入口日志：走模块自己的 log()，落到 LSPosed 日志（android.util.Log 在注入进程里未必可见）
            log(Log.INFO, TAG, "MagicCapsule onPackageReady pkg=" + pkg
                    + " first=" + param.isFirstPackage());

            // 作用域只有 com.android.systemui，其它包直接跳过
            if (!TARGET_PACKAGE.equals(pkg)) {
                return;
            }

            log(Log.INFO, TAG, "MagicCapsule ENTRY LOADED pkg=" + pkg);
            CapsuleHooks.install(this, param.getClassLoader(), pkg);
        } catch (Throwable t) {
            // 绝不外抛：吞掉一切异常，只记日志
            safeLog(Log.ERROR, "onPackageReady 失败 pkg=" + pkg, t);
        }
    }

    /** log() 自身也可能抛（框架未就绪时），再兜一层。 */
    void safeLog(int priority, String message, Throwable t) {
        try {
            log(priority, TAG, message, t);
        } catch (Throwable ignored) {
            // 彻底放弃：日志失败不能影响宿主进程
        }
    }

    void safeLog(int priority, String message) {
        try {
            log(priority, TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
