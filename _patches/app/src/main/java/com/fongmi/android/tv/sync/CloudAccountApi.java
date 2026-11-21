package com.fongmi.android.tv.sync;

import com.fongmi.android.tv.App;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Lightweight client for tvserver account endpoints. All callbacks run on the main thread. */
public final class CloudAccountApi {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build();

    public interface Callback2 {
        void onSuccess(Account account);
        void onError(String message);
    }

    public static final class Account {
        public final String token, username, nickname;
        public final long userId;
        Account(String token, String username, String nickname, long userId) {
            this.token = token; this.username = username; this.nickname = nickname; this.userId = userId;
        }
    }

    private CloudAccountApi() { }

    public static void login(String baseUrl, String username, String password, Callback2 callback) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        post(CloudSyncSettings.normalize(baseUrl) + "/api/login", body.toString(), null, callback);
    }

    public static void register(String baseUrl, String username, String password, String nickname, Callback2 callback) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty("nickname", nickname == null ? "" : nickname);
        post(CloudSyncSettings.normalize(baseUrl) + "/api/register", body.toString(), null, callback);
    }

    public static void changePassword(String baseUrl, String token, String oldPassword, String newPassword, Callback2 callback) {
        JsonObject body = new JsonObject();
        body.addProperty("oldPassword", oldPassword);
        body.addProperty("newPassword", newPassword);
        post(CloudSyncSettings.normalize(baseUrl) + "/api/change-password", body.toString(), token, callback);
    }

    /** Fetch the wuxierj/TVBox interface list through tvserver (or directly if server offline). */
    public static void fetchInterfaces(String baseUrl, String token, okhttp3.Callback callback) {
        String url = CloudSyncSettings.normalize(baseUrl) + "/api/upstream/interfaces";
        Request.Builder builder = new Request.Builder().url(url).get();
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        CLIENT.newCall(builder.build()).enqueue(callback);
    }

    private static void post(String url, String json, String token, Callback2 callback) {
        Request.Builder builder = new Request.Builder().url(url)
                .post(RequestBody.create(json, JSON));
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        CLIENT.newCall(builder.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                App.post(() -> callback.onError("NETWORK_ERROR"));
            }
            @Override public void onResponse(Call call, Response response) {
                String text = "";
                try {
                    if (response.body() != null) text = response.body().string();
                } catch (IOException ignored) { }
                final String body = text;
                final int code = response.code();
                App.post(() -> parseResponse(code, body, callback));
            }
        });
    }

    private static void parseResponse(int httpCode, String body, Callback2 callback) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            int code = root.has("code") ? root.get("code").getAsInt() : httpCode;
            if (code != 0) {
                String msg = root.has("msg") ? root.get("msg").getAsString() : "HTTP_" + httpCode;
                callback.onError(msg);
                return;
            }
            JsonObject data = root.getAsJsonObject("data");
            String token = data.has("token") ? data.get("token").getAsString() : "";
            JsonObject user = data.has("user") ? data.getAsJsonObject("user") : data;
            String username = user != null && user.has("username") ? user.get("username").getAsString() : "";
            String nickname = user != null && user.has("nickname") && !user.get("nickname").isJsonNull()
                    ? user.get("nickname").getAsString() : "";
            long userId = user != null && user.has("id") ? user.get("id").getAsLong() : 0L;
            callback.onSuccess(new Account(token, username, nickname, userId));
        } catch (Exception e) {
            callback.onError("INVALID_RESPONSE");
        }
    }
}
