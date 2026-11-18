package io.github.yingjfd.magiccapsule;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 模块设置的读写约定。
 *
 * <p>设置页（{@link MainActivity}）以普通 {@link SharedPreferences} 写入
 * {@link #PREFS_NAME}；SystemUI 进程内的 Hook 用
 * {@link XposedModule#getRemotePreferences(String)} 读取同一个偏好文件。</p>
 *
 * <p>读失败一律回落到默认值（默认开启），保证「设置读不到」时模块行为不变、
 * 更不会影响 SystemUI 稳定性。</p>
 */
public final class SettingsStore {

    public static final String PREFS_NAME = "magiccapsule_settings";

    /** 是否禁止胶囊收起（true = 一直保持展开）。 */
    public static final String KEY_KEEP_EXPANDED = "keep_expanded";
    public static final boolean DEFAULT_KEEP_EXPANDED = true;

    private SettingsStore() {
    }

    /** 读取「胶囊不收起」开关；任何异常都回落到默认值。 */
    public static boolean keepExpanded(XposedModule module) {
        try {
            SharedPreferences prefs = module.getRemotePreferences(PREFS_NAME);
            if (prefs == null) {
                return DEFAULT_KEEP_EXPANDED;
            }
            return prefs.getBoolean(KEY_KEEP_EXPANDED, DEFAULT_KEEP_EXPANDED);
        } catch (Throwable t) {
            CapsuleHooks.log(module, Log.WARN, "读取设置失败，按默认值（保持展开）处理: " + t);
            return DEFAULT_KEEP_EXPANDED;
        }
    }
}
