package com.fongmi.android.tv.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cloud HTTP transport for SyncCoordinator. The server exposes GET/PUT /api/sync/doc
 * with a strong ETag (revision) and If-Match conditional writes (412 on conflict).
 */
public final class CloudTransport implements SyncCoordinator.Remote {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final HttpUrl endpoint;
    private final String token;
    private volatile Call active;
    private volatile boolean canceled;

    public CloudTransport(String baseUrl, String token) {
        this(new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
                CloudSyncSettings.docEndpoint(baseUrl), token);
    }

    CloudTransport(OkHttpClient client, String endpoint, String token) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false).build();
        this.endpoint = HttpUrl.get(endpoint);
        this.token = token == null ? "" : token;
    }

    public void cancel() { canceled = true; Call call = active; if (call != null) call.cancel(); }

    private Request.Builder request() throws IOException {
        if (canceled || Thread.currentThread().isInterrupted()) throw new IOException("SYNC_CANCELED");
        return new Request.Builder().url(endpoint)
                .header("Cache-Control", "no-cache")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
    }

    private Response execute(Request request) throws IOException {
        Call call = client.newCall(request);
        active = call;
        if (canceled) call.cancel();
        return call.execute();
    }

    private static boolean usableEtag(String value) {
        if (value == null || value.startsWith("W/")) return false;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (c != 0x21 && (c < 0x23 || c > 0x7e)) return false;
            }
            return true;
        }
        return value.matches("[A-Za-z0-9._~-]+");
    }

    @Override
    public SyncCoordinator.Fetched get() throws IOException {
        try (Response response = execute(request().get().build())) {
            if (response.code() == 404) return new SyncCoordinator.Fetched(new SyncDocument(), null, false);
            if (response.code() == 401 || response.code() == 403) throw new SyncCoordinator.Failure("AUTH_FAILED");
            if (response.code() != 200) throw new SyncCoordinator.Failure("HTTP_" + response.code());
            String etag = response.header("ETag");
            if (!usableEtag(etag)) throw new SyncCoordinator.Failure("SERVER_NO_STRONG_ETAG");
            if (response.body() == null || response.body().contentLength() > SyncDocument.MAX_BYTES)
                throw new SyncCoordinator.Failure("INVALID_REMOTE_DOCUMENT");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    if (bytes.size() + count > SyncDocument.MAX_BYTES)
                        throw new SyncCoordinator.Failure("REMOTE_DOCUMENT_TOO_LARGE");
                    bytes.write(buffer, 0, count);
                }
            }
            String text = bytes.toString(StandardCharsets.UTF_8.name());
            try {
                return new SyncCoordinator.Fetched(SyncDocument.decode(text), etag, true);
            } catch (RuntimeException invalid) {
                throw new SyncCoordinator.Failure("INVALID_REMOTE_DOCUMENT");
            }
        } finally {
            active = null;
        }
    }

    @Override
    public boolean put(SyncDocument document, SyncCoordinator.Fetched fetched) throws IOException {
        String etag = fetched.exists ? fetched.etag : "\"0\"";
        if (!usableEtag(etag)) throw new SyncCoordinator.Failure("SERVER_NO_STRONG_ETAG");
        Request.Builder builder = request()
                .put(RequestBody.create(document.encode(), JSON))
                .header("If-Match", etag);
        try (Response response = execute(builder.build())) {
            if (response.code() == 412) return false;
            if (response.code() == 401 || response.code() == 403) throw new SyncCoordinator.Failure("AUTH_FAILED");
            if (response.code() != 200 && response.code() != 201 && response.code() != 204)
                throw new SyncCoordinator.Failure("HTTP_" + response.code());
            return true;
        } finally {
            active = null;
        }
    }
}
