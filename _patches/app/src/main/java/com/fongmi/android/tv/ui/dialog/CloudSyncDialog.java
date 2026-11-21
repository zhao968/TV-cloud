package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.sync.CloudAccountApi;
import com.fongmi.android.tv.sync.CloudSyncManager;
import com.fongmi.android.tv.sync.CloudSyncSettings;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Cloud account login / register / sync form, shared by TV and mobile. */
public final class CloudSyncDialog {
    private CloudSyncDialog() { }

    public static AlertDialog show(Context context, Runnable onChanged) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        Context themed = builder.getContext();
        CloudSyncSettings.Options saved = CloudSyncSettings.get();
        LinearLayout form = new LinearLayout(themed);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ResUtil.dp2px(24), ResUtil.dp2px(8), ResUtil.dp2px(24), ResUtil.dp2px(8));

        TextInputEditText server = field(themed, form, "服务器地址 (如 https://tv.example.com)", saved.baseUrl,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        TextInputEditText user = field(themed, form, "账号", saved.username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        TextInputEditText password = field(themed, form, "密码", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextInputEditText nickname = field(themed, form, "昵称 (注册时用，可选)", "", InputType.TYPE_CLASS_TEXT);

        MaterialCheckBox keeps = check(themed, form, "同步收藏", saved.keeps);
        MaterialCheckBox history = check(themed, form, "同步观看历史", saved.history);
        MaterialCheckBox subscriptions = check(themed, form, "同步接口/源配置", saved.subscriptions);
        MaterialCheckBox preferences = check(themed, form, "同步播放偏好", saved.preferences);
        MaterialCheckBox automatic = check(themed, form, "自动同步", saved.automatic);

        TextView status = new TextView(themed);
        status.setText(saved.loggedIn() ? ("已登录: " + saved.username + "  " + accountSummary(saved)) : "未登录，请先登录或注册");
        form.addView(status);
        LinearProgressIndicator progress = new LinearProgressIndicator(themed);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        form.addView(progress);
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(form);

        AlertDialog dialog = builder.setTitle("云同步账号")
                .setView(scroll)
                .setPositiveButton("登录", null)
                .setNeutralButton("注册", null)
                .setNegativeButton(R.string.dialog_negative, null)
                .show();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            if (!validate(server, user, password, status)) return;
            busy(dialog, progress, status, true);
            CloudAccountApi.register(text(server), text(user), text(password), text(nickname), new CloudAccountApi.Callback2() {
                @Override public void onSuccess(CloudAccountApi.Account account) {
                    applyAccount(dialog, progress, status, onChanged, server, account, keeps, history, subscriptions, preferences, automatic);
                }
                @Override public void onError(String message) { busy(dialog, progress, status, false); status.setText("注册失败: " + friendly(message)); }
            });
        });

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!validate(server, user, password, status)) return;
            busy(dialog, progress, status, true);
            CloudAccountApi.login(text(server), text(user), text(password), new CloudAccountApi.Callback2() {
                @Override public void onSuccess(CloudAccountApi.Account account) {
                    applyAccount(dialog, progress, status, onChanged, server, account, keeps, history, subscriptions, preferences, automatic);
                }
                @Override public void onError(String message) { busy(dialog, progress, status, false); status.setText("登录失败: " + friendly(message)); }
            });
        });
        return dialog;
    }

    private static void applyAccount(AlertDialog dialog, LinearProgressIndicator progress, TextView status, Runnable onChanged,
                                     TextInputEditText server, CloudAccountApi.Account account,
                                     MaterialCheckBox keeps, MaterialCheckBox history, MaterialCheckBox subscriptions,
                                     MaterialCheckBox preferences, MaterialCheckBox automatic) {
        try {
            CloudSyncSettings.saveAccount(text(server), account.token, account.username, account.nickname, account.userId);
            CloudSyncSettings.updateKinds(automatic.isChecked(), keeps.isChecked(), history.isChecked(),
                    subscriptions.isChecked(), preferences.isChecked());
        } catch (RuntimeException e) {
            busy(dialog, progress, status, false);
            status.setText("保存设置失败: " + e.getMessage());
            return;
        }
        if (onChanged != null) onChanged.run();
        busy(dialog, progress, status, false);
        status.setText("登录成功: " + account.username + "，正在同步...");
        CloudSyncManager.get().sync(result -> {
            if (!dialog.isShowing()) return;
            status.setText(result.success
                    ? "登录成功，同步完成 (更新 " + result.updated + " / 删除 " + result.deleted + " / 跳过 " + result.skipped + ")"
                    : "登录成功，但同步失败: " + friendly(result.error));
        });
    }

    private static boolean validate(TextInputEditText server, TextInputEditText user, TextInputEditText password, TextView status) {
        if (text(server).isEmpty() || text(user).isEmpty() || text(password).isEmpty()) {
            status.setText("请填写服务器地址、账号和密码");
            return false;
        }
        return true;
    }

    private static void busy(AlertDialog dialog, LinearProgressIndicator progress, TextView status, boolean on) {
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!on);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!on);
        if (on) status.setText("请稍候...");
    }

    private static String accountSummary(CloudSyncSettings.Options o) {
        long last = CloudSyncSettings.lastSuccess();
        return last > 0 ? ("上次同步 " + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(last))) : "";
    }

    private static String friendly(String code) {
        if (code == null) return "未知错误";
        return switch (code) {
            case "NETWORK_ERROR", "SYNC_NETWORK_OR_STORAGE_ERROR" -> "网络错误，请检查服务器地址和连通性";
            case "AUTH_FAILED", "HTTP_401", "HTTP_403" -> "账号或密码错误";
            case "SYNC_BUSY" -> "正在同步中，请稍后再试";
            default -> code;
        };
    }

    private static String text(TextInputEditText input) { return input.getText() == null ? "" : input.getText().toString().trim(); }

    private static TextInputEditText field(Context context, LinearLayout parent, String hint, String value, int type) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setHint(hint);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setInputType(type);
        input.setText(value);
        layout.addView(input, new TextInputLayout.LayoutParams(-1, -2));
        if ((type & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)
            layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = ResUtil.dp2px(8);
        parent.addView(layout, params);
        return input;
    }

    private static MaterialCheckBox check(Context context, LinearLayout parent, String label, boolean checked) {
        MaterialCheckBox box = new MaterialCheckBox(context);
        box.setText(label);
        box.setChecked(checked);
        parent.addView(box);
        return box;
    }
}
