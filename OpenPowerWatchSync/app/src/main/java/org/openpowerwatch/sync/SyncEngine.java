package org.openpowerwatch.sync;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Shared BLE sync engine: scan, connect, MAC queue, time write.
 * Used by MainActivity (UI) and AutoSyncWorker (background).
 * Contains no original app assets and no private device identifiers.
 */
public class SyncEngine {

    public interface Listener {
        void onStatus(String s);
        void onLog(String s);
        void onSyncResult(boolean anyOk);
        void onScanFinished(Map<String, BluetoothDevice> found);
    }

    private static final UUID SERVICE_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e1011");
    private static final UUID NOTIFY_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0012");
    private static final UUID PWX_WRITE_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0013");
    private static final UUID CLASSIC_WRITE_UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0011");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte[] HEADER = new byte[] {
            0x14, 0x00, 0x01, 0x00, 0x00, 0x64, 0x00, 0x00,
            0x00, (byte) 0xCA, (byte) 0xC6, (byte) 0x88, (byte) 0x99, 0x00, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xA9, 0x4C, (byte) 0xA7, 0x21
    };

    private static final String KEY_MACS = "sync_macs";

    private static SyncEngine instance;

    public static synchronized SyncEngine get(Context context) {
        if (instance == null) instance = new SyncEngine(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    private final Queue<byte[]> pendingWrites = new ArrayDeque<>();
    private final List<byte[]> pendingChunks = new ArrayList<>();
    private final Queue<String> macQueue = new ArrayDeque<>();
    private final Map<String, BluetoothDevice> found = new LinkedHashMap<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean writeInProgress = false;
    private boolean busy = false;
    private boolean macDone = true;
    private boolean autoMode = false;
    private int okCount = 0;
    private int failCount = 0;
    private String currentMac = "";
    private Runnable watchdog = null;
    private boolean scanning = false;
    private int serviceRetries = 0;

    private SyncEngine(Context c) { this.context = c; }

    public synchronized void setListener(Listener l) { this.listener = l; }
    public synchronized Listener getListener() { return this.listener; }
    public synchronized boolean isBusy() { return busy; }

    // ---- saved MAC list (prefs) ----

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    public static Set<String> savedMacs(Context c) {
        Set<String> out = new LinkedHashSet<>();
        for (String m : prefs(c).getString(KEY_MACS, "").split("\\|")) {
            if (m != null && m.trim().length() == 17) out.add(m.trim().toUpperCase(Locale.US));
        }
        return out;
    }

    public static void addSavedMac(Context c, String mac) {
        if (mac == null || mac.trim().length() != 17) return;
        Set<String> s = savedMacs(c);
        s.add(mac.trim().toUpperCase(Locale.US));
        prefs(c).edit().putString(KEY_MACS, TextUtils.join("|", s)).apply();
    }

    public static void clearSavedMacs(Context c) { prefs(c).edit().remove(KEY_MACS).apply(); }

    // ---- UI-safe emit ----

    private void emitStatus(final String s) {
        handler.post(() -> { Listener l = listener; if (l != null) l.onStatus(s); });
    }

    private void emitLog(final String s) {
        handler.post(() -> { Listener l = listener; if (l != null) l.onLog(s); });
    }

    // ---- scan ----

    @SuppressLint("MissingPermission")
    public void startScan(long ms) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            emitStatus("ERROR: Bluetooth is off");
            return;
        }
        if (scanning) return;
        found.clear();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { emitStatus("ERROR: BLE scanner not available"); return; }
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                if (d == null) return;
                String key = safeAddress(d) + "  " + safeName(d);
                if (!found.containsKey(key)) {
                    found.put(key, d);
                    emitLog("Found: " + key);
                }
            }
        };
        scanning = true;
        emitStatus("Scanning...");
        emitLog("Scanning for " + (ms / 1000) + " seconds...");
        try { scanner.startScan(cb); } catch (Exception e) { scanning = false; emitStatus("ERROR: scan failed"); return; }
        handler.postDelayed(() -> {
            if (!scanning) return;
            scanning = false;
            try { scanner.stopScan(cb); } catch (Exception ignored) {}
            Listener l = listener;
            if (l != null) l.onScanFinished(new LinkedHashMap<>(found));
        }, ms);
    }

    // ---- queue of MACs ----

    public void clearQueue() { macQueue.clear(); }

    public void queueMac(String mac) {
        if (mac != null && mac.trim().length() == 17) macQueue.add(mac.trim().toUpperCase(Locale.US));
    }

    public void startQueue(boolean auto) {
        handler.post(() -> {
            if (busy) { emitLog("Busy — another sync is running"); return; }
            if (macQueue.isEmpty()) { emitStatus("Nothing to sync"); return; }
            busy = true;
            autoMode = auto;
            okCount = 0;
            failCount = 0;
            processNext();
        });
    }

    private void processNext() {
        String mac = macQueue.poll();
        if (mac == null) { finishQueue(); return; }
        startMac(mac);
    }

    @SuppressLint("MissingPermission")
    private void startMac(String mac) {
        macDone = false;
        currentMac = mac;
        serviceRetries = 0;
        closeGatt();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { failCurrent("Bluetooth is off"); return; }
        BluetoothDevice device;
        try { device = adapter.getRemoteDevice(mac); } catch (IllegalArgumentException e) { failCurrent("Bad MAC " + mac); return; }
        pendingWrites.clear();
        pendingChunks.clear();
        writeInProgress = false;
        emitStatus("Connecting " + mac + " ...");
        emitLog("Connecting to " + mac + " ...");
        watchdog = () -> { if (!macDone) failCurrent("Timeout: " + mac); };
        handler.postDelayed(watchdog, 45000);
        try {
            gatt = device.connectGatt(context, false, gattCb);
        } catch (Exception e) {
            failCurrent("connectGatt failed: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void connectAgain() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { failCurrent("Bluetooth is off"); return; }
        BluetoothDevice device;
        try { device = adapter.getRemoteDevice(currentMac); } catch (IllegalArgumentException e) { failCurrent("Bad MAC " + currentMac); return; }
        emitLog("Retrying connect " + currentMac + " ...");
        try { gatt = device.connectGatt(context, false, gattCb); } catch (Exception e) { failCurrent("connectGatt failed: " + e.getMessage()); }
    }

    private void failCurrent(String why) {
        handler.post(() -> {
            if (macDone) return;
            macDone = true;
            cancelWatchdog();
            failCount++;
            emitStatus("ERROR: " + why);
            emitLog("FAIL " + currentMac + ": " + why);
            closeGatt();
            handler.postDelayed(this::processNext, 1500);
        });
    }

    private void completeCurrent() {
        handler.post(() -> {
            if (macDone) return;
            macDone = true;
            cancelWatchdog();
            okCount++;
            emitLog("SYNC OK: " + currentMac);
            emitStatus("Synced " + currentMac);
            closeGatt();
            handler.postDelayed(this::processNext, 1500);
        });
    }

    private void finishQueue() {
        busy = false;
        boolean anyOk = okCount > 0;
        emitStatus(anyOk
                ? "Sync OK (" + okCount + " ok, " + failCount + " fail)"
                : "ERROR: no watch synced (" + failCount + " fail)");
        Listener l = listener;
        if (l != null) l.onSyncResult(anyOk);
    }

    private void cancelWatchdog() {
        if (watchdog != null) { handler.removeCallbacks(watchdog); watchdog = null; }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) {} gatt = null; }
        writeChar = null;
    }

    public void shutdown() { closeGatt(); }

    // ---- GATT callbacks ----

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitLog("Connected. Waiting before discovery...");
                handler.postDelayed(() -> {
                    if (macDone) return;
                    try { g.discoverServices(); } catch (Exception e) { failCurrent("discoverServices failed"); }
                }, 800);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!macDone) failCurrent("Disconnected, status=" + status);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { failCurrent("Service discovery failed: " + status); return; }
            BluetoothGattService svc = g.getService(SERVICE_UUID);
            if (svc == null) {
                if (serviceRetries < 1) {
                    serviceRetries++;
                    emitLog("PowerWatch service not found — retrying...");
                    closeGatt();
                    handler.postDelayed(() -> { if (!macDone) connectAgain(); }, 600);
                    return;
                }
                failCurrent("PowerWatch service not found");
                return;
            }
            BluetoothGattCharacteristic notify = svc.getCharacteristic(NOTIFY_UUID);
            writeChar = svc.getCharacteristic(PWX_WRITE_UUID);
            if (writeChar == null) writeChar = svc.getCharacteristic(CLASSIC_WRITE_UUID);
            if (writeChar == null) { failCurrent("Write characteristic not found"); return; }
            emitLog("Using write characteristic: " + writeChar.getUuid());
            if (notify != null) {
                boolean ok = g.setCharacteristicNotification(notify, true);
                emitLog("Enable notify local: " + ok);
                BluetoothGattDescriptor desc = notify.getDescriptor(CCCD_UUID);
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    try { g.writeDescriptor(desc); return; } catch (Exception e) { emitLog("descriptor write failed, continuing"); }
                }
            }
            beginSyncWrites();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            emitLog("Notify descriptor write status=" + status);
            beginSyncWrites();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            emitLog("Notify " + c.getUuid() + ": " + hex(v) + "  ascii=" + printable(v));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            handler.post(() -> {
                writeInProgress = false;
                if (status != BluetoothGatt.GATT_SUCCESS) { failCurrent("Write failed: " + status); return; }
                drainNext();
            });
        }
    };

    // ---- write sequence (unchanged protocol) ----

    private void beginSyncWrites() {
        handler.post(() -> {
            if (macDone) return;
            pendingWrites.clear();
            pendingWrites.add(frame(timePayload()));
            pendingWrites.add(frame(new byte[] {(byte)0x84, 0x21}));
            pendingWrites.add(frame(new byte[] {(byte)0x81, 0x0F, 0x05, 0x00, (byte)0x82}));
            emitLog("Queued time + save + SOFF");
            emitStatus("Syncing " + currentMac + " ...");
            drainNext();
        });
    }

    @SuppressLint("MissingPermission")
    private void drainNext() {
        if (writeInProgress || gatt == null || writeChar == null) return;
        if (pendingChunks.isEmpty()) {
            byte[] next = pendingWrites.poll();
            if (next == null) { completeCurrent(); return; }
            pendingChunks.addAll(chunk20(next));
        }
        byte[] chunk = pendingChunks.remove(0);
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        writeChar.setValue(chunk);
        emitLog("Write chunk: " + hex(chunk));
        writeInProgress = true;
        boolean ok = gatt.writeCharacteristic(writeChar);
        if (!ok) failCurrent("writeCharacteristic returned false");
    }

    // ---- protocol helpers (unchanged) ----

    private static byte[] timePayload() {
        long nowMs = System.currentTimeMillis();
        long utc = nowMs / 1000L;
        int offsetMin = TimeZone.getDefault().getOffset(nowMs) / 60000;
        return new byte[] {
                (byte)0x84, 0x01, 0x07,
                (byte)((utc >> 24) & 0xff), (byte)((utc >> 16) & 0xff), (byte)((utc >> 8) & 0xff), (byte)(utc & 0xff),
                (byte)((offsetMin >> 8) & 0xff), (byte)(offsetMin & 0xff)
        };
    }

    private static byte[] frame(byte[] rawPayload) {
        String b64 = Base64.encodeToString(rawPayload, Base64.NO_WRAP);
        byte[] text = ("!" + b64 + "\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(HEADER, 0, HEADER.length);
        out.write((byte) text.length);
        out.write(text, 0, text.length);
        return out.toByteArray();
    }

    private static List<byte[]> chunk20(byte[] msg) {
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < msg.length; i += 20) {
            chunks.add(Arrays.copyOfRange(msg, i, Math.min(i + 20, msg.length)));
        }
        return chunks;
    }

    // ---- misc ----

    @SuppressLint("MissingPermission")
    public static String safeName(BluetoothDevice d) {
        try { String n = d.getName(); return n == null ? "(no name)" : n; } catch (SecurityException e) { return "(name hidden)"; }
    }

    @SuppressLint("MissingPermission")
    public static String safeAddress(BluetoothDevice d) {
        try { return d.getAddress(); } catch (SecurityException e) { return ""; }
    }

    private static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X ", x & 0xff));
        return sb.toString().trim();
    }

    private static String printable(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xff;
            sb.append(c >= 32 && c <= 126 ? (char)c : '.');
        }
        return sb.toString();
    }
    }
        
