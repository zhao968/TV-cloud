package com.fongmi.android.tv.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Cloud account + sync preferences. Credentials (token) live in a dedicated
 * SharedPreferences file and never enter a SyncDocument.
 */
public final class CloudSyncSettings {
    private CloudSyncSettings() { }

    private static SharedPreferences preferences() {
        return App.get().getSharedPreferences("cloud-sync", Context.MODE_PRIVATE);
    }

    public static final class Options {
        public final String baseUrl, token, username, nickname;
        public final long userId;
        public final boolean automatic, keeps, history, subscriptions, preferences;

        public Options(String baseUrl, String token, String username, String nickname, long userId,
                       boolean automatic, boolean keeps, boolean history, boolean subscriptions, boolean preferences) {
            this.baseUrl = normalize(baseUrl);
            this.token = token == null ? "" : token;
            this.username = username == null ? "" : username;
            this.nickname = nickname == null ? "" : nickname;
            this.userId = userId;
            this.automatic = automatic;
            this.keeps = keeps;
            this.history = history;
            this.subscriptions = subscriptions;
            this.preferences = preferences;
        }

        public boolean configured() { return !baseUrl.isEmpty() && !token.isEmpty(); }
        public boolean loggedIn() { return !token.isEmpty(); }
        public Set<String> kinds() {
            Set<String> kinds = new HashSet<>();
            if (keeps) kinds.add("keep");
            if (history) kinds.add("history");
            if (subscriptions) kinds.add("subscription");
            if (preferences) kinds.add("preference");
            return kinds;
        }
        public String endpointKey() { return SyncDocument.hash(baseUrl + "\n" + userId + "\n" + username); }
        public String fingerprint() {
            return SyncDocument.hash(baseUrl + "\n" + token + "\n" + userId + "\n" + username + "\n"
                    + automatic + keeps + history + subscriptions + preferences);
        }
    }

    public static synchronized Options get() {
        SharedPreferences p = preferences();
        return new Options(
                p.getString("baseUrl", ""), p.getString("token", ""),
                p.getString("username", ""), p.getString("nickname", ""),
                p.getLong("userId", 0),
                p.getBoolean("automatic", false), p.getBoolean("keeps", true),
                p.getBoolean("history", true), p.getBoolean("subscriptions", true),
                p.getBoolean("preferences", false));
    }

    public static synchronized void save(Options options) {
        if (options.configured() && options.kinds().isEmpty()) throw new IllegalArgumentException("SELECT_SYNC_DATA");
        Options previous = get();
        SharedPreferences.Editor edit = preferences().edit()
                .putString("baseUrl", options.baseUrl).putString("token", options.token)
                .putString("username", options.username).putString("nickname", options.nickname)
                .putLong("userId", options.userId)
                .putBoolean("automatic", options.automatic).putBoolean("keeps", options.keeps)
                .putBoolean("history", options.history).putBoolean("subscriptions", options.subscriptions)
                .putBoolean("preferences", options.preferences);
        if (!previous.endpointKey().equals(options.endpointKey())) edit.remove("last_success");
        if (!edit.commit()) throw new IllegalStateException("SETTINGS_SAVE_FAILED");
        if (!previous.fingerprint().equals(options.fingerprint())) CloudSyncManager.get().configurationChanged();
    }

    /** Save login credentials, keeping existing data-kind preferences. */
    public static synchronized void saveAccount(String baseUrl, String token, String username, String nickname, long userId) {
        Options current = get();
        save(new Options(baseUrl, token, username, nickname, userId, current.automatic,
                current.keeps, current.history, current.subscriptions, current.preferences));
    }

    public static synchronized void updateKinds(boolean automatic, boolean keeps, boolean history, boolean subscriptions, boolean preferences) {
        Options current = get();
        save(new Options(current.baseUrl, current.token, current.username, current.nickname, current.userId,
                automatic, keeps, history, subscriptions, preferences));
    }

    public static synchronized void logout() {
        Options current = get();
        save(new Options(current.baseUrl, "", "", "", 0, false,
                current.keeps, current.history, current.subscriptions, current.preferences));
    }

    public static synchronized String deviceId() {
        SharedPreferences p = preferences();
        String id = p.getString("device", "");
        if (!id.isEmpty()) return id;
        id = UUID.randomUUID().toString();
        if (!p.edit().putString("device", id).commit()) throw new IllegalStateException("DEVICE_ID_SAVE_FAILED");
        return id;
    }

    public static long lastSuccess() { return preferences().getLong("last_success", 0); }

    static synchronized void succeeded(String endpoint) {
        if (get().endpointKey().equals(endpoint))
            preferences().edit().putLong("last_success", System.currentTimeMillis()).apply();
    }

    public static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String v = value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://" + v;
        if (v.contains("@")) throw new IllegalArgumentException("URL_CREDENTIALS_NOT_ALLOWED");
        return v;
    }

    public static String docEndpoint(String baseUrl) { return normalize(baseUrl) + "/api/sync/doc"; }
}
