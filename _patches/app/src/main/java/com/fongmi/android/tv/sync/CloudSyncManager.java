package com.fongmi.android.tv.sync;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Nonblocking entry points for cloud account sync, mirroring WebDavSyncManager. */
public final class CloudSyncManager {
    public interface Callback { void complete(Result result); }
    public static final class Result {
        public final boolean success;
        public final String error;
        public final int updated, deleted, skipped;
        private Result(boolean success, String error, int updated, int deleted, int skipped) {
            this.success = success; this.error = error; this.updated = updated; this.deleted = deleted; this.skipped = skipped;
        }
    }
    private static final CloudSyncManager INSTANCE = new CloudSyncManager();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong cancellation = new AtomicLong();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Cloud-sync"); thread.setDaemon(true); return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile CloudTransport active;
    private volatile long lastAutomaticAttempt;
    private CloudSyncManager() { }
    public static CloudSyncManager get() { return INSTANCE; }
    public boolean isRunning() { return busy.get(); }
    public void cancel() { cancellation.incrementAndGet(); CloudTransport t = active; if (t != null) t.cancel(); }
    void configurationChanged() { cancel(); lastAutomaticAttempt = 0; }
    public void maybeAutoSync() { maybeAutoSync(false); }
    public void maybeAutoSyncOnBackground() { maybeAutoSync(true); }
    private void maybeAutoSync(boolean background) {
        CloudSyncSettings.Options options = CloudSyncSettings.get();
        long now = System.currentTimeMillis();
        if (!options.automatic || !options.loggedIn() || (!background && now - CloudSyncSettings.lastSuccess() < 15 * 60_000L)
                || now - lastAutomaticAttempt < 60_000L || busy.get()) return;
        lastAutomaticAttempt = now;
        sync(null);
    }
    public void sync(Callback callback) {
        long generation = cancellation.get();
        if (!busy.compareAndSet(false, true)) { deliver(callback, new Result(false, "SYNC_BUSY", 0, 0, 0)); return; }
        worker.execute(() -> {
            Result result;
            try {
                CloudSyncSettings.Options options = CloudSyncSettings.get();
                if (!options.loggedIn()) throw new SyncCoordinator.Failure("SYNC_NOT_LOGGED_IN");
                if (options.kinds().isEmpty()) throw new SyncCoordinator.Failure("SELECT_SYNC_DATA");
                CloudTransport transport = new CloudTransport(options.baseUrl, options.token);
                active = transport;
                if (generation != cancellation.get()) transport.cancel();
                CloudSyncStore store = new CloudSyncStore(AppDatabase.get(), options, () -> generation == cancellation.get());
                File directory = new File(App.get().getFilesDir(), "cloud-sync");
                SyncCoordinator.Applied applied = SyncCoordinator.sync(store,
                        CloudSyncStore.journal(directory, options.endpointKey()), transport,
                        options.kinds(), CloudSyncSettings.deviceId(), System.currentTimeMillis());
                CloudSyncSettings.succeeded(options.endpointKey());
                if (applied.updated + applied.deleted > 0)
                    main.post(() -> { RefreshEvent.keep(); RefreshEvent.history(); });
                result = new Result(true, "", applied.updated, applied.deleted, applied.skipped);
            } catch (SyncCoordinator.Failure failure) {
                result = new Result(false, failure.code, 0, 0, 0);
            } catch (IOException failure) {
                result = new Result(false, "SYNC_NETWORK_OR_STORAGE_ERROR", 0, 0, 0);
            } catch (RuntimeException failure) {
                result = new Result(false, "SYNC_LOCAL_STATE_ERROR", 0, 0, 0);
            } finally {
                active = null;
                busy.set(false);
            }
            deliver(callback, result);
        });
    }
    private void deliver(Callback callback, Result result) {
        if (callback != null) main.post(() -> callback.complete(result));
    }
}
