package io.github.yingjfd.magiccapsule;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页：只提供一个开关「胶囊不收起」。
 *
 * <p>纯 framework 实现（android.app.Activity + Switch），不依赖 AppCompat / Material。
 * 开关写入 {@link SettingsStore#PREFS_NAME}，SystemUI 进程内的 Hook 通过
 * {@code getRemotePreferences} 读取同一个偏好文件。</p>
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private Switch switchKeepExpanded;
    private TextView status;

    /** 初始化期间不要写回偏好，避免把默认值覆盖用户已存的值。 */
    private boolean binding = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(SettingsStore.PREFS_NAME, MODE_PRIVATE);
        switchKeepExpanded = findViewById(R.id.switch_keep_expanded);
        status = findViewById(R.id.status);

        switchKeepExpanded.setChecked(prefs.getBoolean(
                SettingsStore.KEY_KEEP_EXPANDED, SettingsStore.DEFAULT_KEEP_EXPANDED));

        switchKeepExpanded.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (binding) {
                    return;
                }
                prefs.edit().putBoolean(SettingsStore.KEY_KEEP_EXPANDED, isChecked).apply();
                status.setText(R.string.tip_saved);
                status.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(R.string.tip_scope);
                    }
                }, 1200L);
            }
        });

        binding = false;
    }
}
