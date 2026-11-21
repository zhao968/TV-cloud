package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.sync.CloudAccountApi;
import com.fongmi.android.tv.sync.CloudSyncSettings;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Fetches the interface list from wuxierj/TVBox (via the cloud server when configured,
 * otherwise directly from GitHub API) and lets the user pick one to configure.
 */
public final class InterfacePickerDialog {
    private InterfacePickerDialog() { }

    public interface Listener {
        void onPicked(String name, String url);
    }

    private static final String GITHUB_API = "https://api.github.com/repos/wuxierj/TVBox/contents/README.md";

    public static AlertDialog show(Context context, Listener listener) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        Context themed = builder.getContext();
        LinearLayout box = new LinearLayout(themed);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ResUtil.dp2px(20), ResUtil.dp2px(12), ResUtil.dp2px(20), ResUtil.dp2px(8));
        TextView status = new TextView(themed);
        status.setText("正在拉取接口列表...");
        ProgressBar progress = new ProgressBar(themed);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = ResUtil.dp2px(8);
        box.addView(status);
        box.addView(progress, pp);
        ScrollView scroll = new ScrollView(themed);
        LinearLayout list = new LinearLayout(themed);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        box.addView(scroll);

        AlertDialog dialog = builder.setTitle("选择接口 (来源: wuxierj/TVBox)")
                .setView(box)
                .setNegativeButton(R.string.dialog_negative, null)
                .show();

        load(context, list, status, progress, listener, dialog);
        return dialog;
    }

    private static void load(Context context, LinearLayout list, TextView status, ProgressBar progress,
                             Listener listener, AlertDialog dialog) {
        CloudSyncSettings.Options opt = CloudSyncSettings.get();
        if (opt.configured()) {
            CloudAccountApi.fetchInterfaces(opt.baseUrl, opt.token, new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    App.post(() -> loadFromGithub(context, list, status, progress, listener, dialog));
                }
                @Override public void onResponse(Call call, Response response) {
                    final String body = read(response);
                    final int code = response.code();
                    App.post(() -> {
                        try {
                            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                            if (root.has("data")) {
                                JsonArray items = root.getAsJsonObject("data").getAsJsonArray("items");
                                if (items != null && items.size() > 0) {
                                    render(context, list, status, progress, listener, dialog, items);
                                    return;
                                }
                            }
                        } catch (Exception ignored) { }
                        loadFromGithub(context, list, status, progress, listener, dialog);
                    });
                }
            });
        } else {
            loadFromGithub(context, list, status, progress, listener, dialog);
        }
    }

    private static void loadFromGithub(Context context, LinearLayout list, TextView status, ProgressBar progress,
                                       Listener listener, AlertDialog dialog) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build();
        okhttp3.Request request = new okhttp3.Request.Builder().url(GITHUB_API)
                .header("Accept", "application/vnd.github.raw").get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                App.post(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("拉取失败，请检查网络或先在云同步中配置服务器");
                });
            }
            @Override public void onResponse(Call call, Response response) {
                final String body = read(response);
                final int code = response.code();
                App.post(() -> {
                    try {
                        String md = body;
                        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                        if (root.has("content")) md = new String(android.util.Base64.decode(root.get("content").getAsString(),
                                android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                        JsonArray items = parseMarkdown(md);
                        if (items.size() == 0) throw new IllegalArgumentException("empty");
                        render(context, list, status, progress, listener, dialog, items);
                    } catch (Exception e) {
                        progress.setVisibility(View.GONE);
                        status.setText("解析接口失败 (HTTP " + code + ")");
                    }
                });
            }
        });
    }

    private static JsonArray parseMarkdown(String md) {
        JsonArray out = new JsonArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : md.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("|")) continue;
            String[] cells = line.split("\\|");
            if (cells.length < 3) continue;
            String name = cells[1].replace("**", "").trim();
            if (name.isEmpty() || name.equals("名称") || name.matches("^-+$")) continue;
            String joined = String.join(" ", java.util.Arrays.copyOfRange(cells, 2, cells.length));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s)）\\]|]+").matcher(joined);
            if (!m.find()) continue;
            String url = m.group();
            if (seen.contains(url)) continue;
            seen.add(url);
            JsonObject item = new JsonObject();
            item.addProperty("name", name);
            item.addProperty("url", url);
            out.add(item);
        }
        return out;
    }

    private static void render(Context context, LinearLayout list, TextView status, ProgressBar progress,
                               Listener listener, AlertDialog dialog, JsonArray items) {
        list.removeAllViews();
        progress.setVisibility(View.GONE);
        status.setText("共 " + items.size() + " 个接口，点击选择：");
        for (JsonElement el : items) {
            JsonObject item = el.getAsJsonObject();
            String name = item.has("name") ? item.get("name").getAsString() : "";
            String url = item.has("url") ? item.get("url").getAsString() : "";
            TextView row = new TextView(context);
            row.setText(name + "\n" + url);
            row.setTextSize(14);
            row.setFocusable(true);
            row.setClickable(true);
            int pad = ResUtil.dp2px(10);
            row.setPadding(pad, pad, pad, pad);
            row.setOnClickListener(v -> {
                listener.onPicked(name, url);
                dialog.dismiss();
            });
            list.addView(row);
            android.widget.TextView div = new android.widget.TextView(context);
            div.setHeight(1);
            div.setBackgroundColor(0x22000000);
            list.addView(div);
        }
    }

    private static String read(Response response) {
        try {
            if (response.body() != null) return response.body().string();
        } catch (IOException ignored) { }
        return "";
    }
}
