package io.github.yingjfd.magiccapsule;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 设置页：开关「胶囊不收起」+ 一键重启 SystemUI。
 *
 * <p>纯 framework 实现（android.app.Activity + Switch + Button），不依赖 AppCompat / Material。
 * 开关写入 {@link SettingsStore#PREFS_NAME}，SystemUI 进程内的 Hook 通过
 * {@code getRemotePreferences} 读取同一个偏好文件；改完开关用「重启系统界面」按钮让 Hook 立即生效。</p>
 */
public class MainActivity extends Activity {

    /** su 命令超时：既要够 root 管理器弹授权框，又不能把界面卡死。 */
    private static final long SU_TIMEOUT_SECONDS = 10L;
    /** 杀掉 SystemUI 后等它被 init 拉起来的观察时间。 */
    private static final long RESTART_SETTLE_MS = 1800L;

    private SharedPreferences prefs;
    private Switch switchKeepExpanded;
    private Button restartButton;
    private TextView status;

    /** 初始化期间不要写回偏好，避免把默认值覆盖用户已存的值。 */
    private boolean binding = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(SettingsStore.PREFS_NAME, MODE_PRIVATE);
        switchKeepExpanded = findViewById(R.id.switch_keep_expanded);
        restartButton = findViewById(R.id.btn_restart_systemui);
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

        restartButton.setOnClickListener(v -> confirmRestart());

        binding = false;
    }

    /** 重启会闪屏、状态栏消失一会儿，先确认一次再执行。 */
    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_confirm_title)
                .setMessage(R.string.restart_confirm_message)
                .setPositiveButton(R.string.restart_confirm_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        restartSystemUi();
                    }
                })
                .setNegativeButton(R.string.restart_cancel, null)
                .show();
    }

    /** 后台线程执行 su，结果回主线程显示；期间按钮置灰防止连点。 */
    private void restartSystemUi() {
        restartButton.setEnabled(false);
        status.setText(R.string.restart_running);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String message;
                if (!hasRoot()) {
                    message = getString(R.string.restart_no_root);
                } else {
                    // killall 最直接；个别精简系统没有该命令，退回 pkill -f
                    String error = execSu("killall com.android.systemui");
                    if (error != null) {
                        error = execSu("pkill -f com.android.systemui");
                    }
                    if (error == null) {
                        sleepQuietly(RESTART_SETTLE_MS);
                        String pid = execSuForOutput("pidof com.android.systemui");
                        message = pid.isEmpty()
                                ? getString(R.string.restart_success)
                                : getString(R.string.restart_success) + "（pid " + pid + "）";
                    } else {
                        message = getString(R.string.restart_failed, error);
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText(message);
                        restartButton.setEnabled(true);
                    }
                });
            }
        }, "magiccapsule-restart").start();
    }

    /** 用 {@code su -c id} 判断是否真的拿到了 root（用户可能拒绝授权）。 */
    private boolean hasRoot() {
        String out = execSuForOutput("id");
        return out.contains("uid=0");
    }

    /**
     * 执行一条 root 命令。
     *
     * @return {@code null} 表示执行成功；否则返回可展示的错误文本
     */
    private String execSu(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String output = readAll(process.getInputStream());
            boolean finished = process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return "su 超时（可能未授权 root）";
            }
            if (process.exitValue() != 0) {
                String trimmed = output.trim();
                return trimmed.isEmpty() ? ("exit code " + process.exitValue()) : trimmed;
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 执行 root 命令并返回标准输出（失败返回空串），用于查询类命令。 */
    private String execSuForOutput(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String output = readAll(process.getInputStream());
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy();
                return "";
            }
            return output.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String readAll(InputStream input) {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 8192) {
                    break;
                }
            }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
