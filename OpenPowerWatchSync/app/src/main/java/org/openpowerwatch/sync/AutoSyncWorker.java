package org.openpowerwatch.sync;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

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
 * После синка — вибрация-подтверждение (если включена галочкой).
 */
public class AutoSyncWorker extends Worker {

    private static final String WORK_NAME = "powerwatch-autosync";
    private static final String SUN_WORK_NAME = "powerwatch-sunsync";

    /** Флаг успеха последнего синка (для вибрации). */
    private boolean lastOk;

    public AutoSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Result r;
        try {
            r = syncAll();
        } finally {
            // В любом случае (успех, ошибка, ретрай) — планируем следующий синк
            // на ближайший восход или закат. Цепочка замыкается сама.
            scheduleNextSunSync(getApplicationContext());
        }
        buzz(getApplicationContext(), lastOk);
        return r;
    }

    private Result syncAll() {
        lastOk = false;
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
        lastOk = anyOk[0];
        return anyOk[0] ? Result.success() : Result.retry();
    }

    /** Вибрация-подтверждение: один короткий «бум» на успех, двойной — на неудачу. */
    private static void buzz(Context context, boolean ok) {
        boolean enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("vibrate_on_sync", true);
        if (!enabled) return;

        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm == null) return;
            vibrator = vm.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = ok ? new long[]{0, 120}              // один «бум» — успех
                            : new long[]{0, 120, 150, 120};   // «бум-бум» — неудача
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
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
