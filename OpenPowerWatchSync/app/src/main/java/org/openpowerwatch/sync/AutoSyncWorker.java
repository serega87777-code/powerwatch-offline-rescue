package org.openpowerwatch.sync;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background auto-sync.
 * Режим: синк на ближайший восход/закат (по SunTimes),
 * плюс страховочный периодический синк раз в 12 часов.
 */
public class AutoSyncWorker extends Worker {

    private static final String WORK_NAME = "powerwatch-autosync";
    private static final String SUN_WORK_NAME = "powerwatch-sunsync";
    private static final long FALLBACK_HOURS = 12;

    public AutoSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            return syncAll();
        } finally {
            // В любом случае (успех, ошибка, ретрай) — планируем следующий синк
            // на ближайший восход или закат. Цепочка замыкается сама.
            scheduleNextSunSync(getApplicationContext());
        }
    }

    private Result syncAll() {
        Context app = getApplicationContext();
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) return Result.retry();

        Set<String> macs = SyncEngine.savedMacs(app);
        if (macs.isEmpty()) return Result.failure();

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] anyOk = {false};
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            final SyncEngine engine = SyncEngine.get(app);
            if (engine.isBusy()) { latch.countDown(); return; }
            final SyncEngine.Listener prev = engine.getListener();
            engine.setListener(new SyncEngine.Listener() {
                @Override public void onStatus(String s) { if (prev != null) prev.onStatus("[Auto] " + s); }
                @Override public void onLog(String s) { if (prev != null) prev.onLog("[Auto] " + s); }
                @Override public void onSyncResult(boolean ok) {
                    anyOk[0] = ok;
                    if (prev != null) prev.onLog("[Auto] finished, ok=" + ok);
                    latch.countDown();
                }
                @Override public void onScanFinished(Map<String, BluetoothDevice> found) { }
            });
            engine.clearQueue();
            for (String m : macs) engine.queueMac(m);
            engine.startQueue(true);
        });
        try {
            if (!latch.await(3, TimeUnit.MINUTES)) return Result.retry();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        return anyOk[0] ? Result.success() : Result.retry();
    }

    /** Ставит одноразовый синк на ближайший восход или закат. */
    public static void scheduleNextSunSync(Context context) {
        long now = System.currentTimeMillis();
        long next = SunTimes.nextEvent(now);
        if (next == -1) return;
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AutoSyncWorker.class)
                .setInitialDelay(next - now, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(SUN_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }

    /** Страховка: периодический синк + ближайший солнечный. */
    public static void schedule(Context context, long intervalHours) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(AutoSyncWorker.class, intervalHours, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        scheduleNextSunSync(context);
    }

    public static void runOnceNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AutoSyncWorker.class).build();
        WorkManager.getInstance(context).enqueue(request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork(SUN_WORK_NAME);
    }
}
