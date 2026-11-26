package org.openpowerwatch.sync;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private TextView status;
    private TextView logView;
    private EditText macInput;
    private SyncEngine engine;

    private final SyncEngine.Listener engineListener = new SyncEngine.Listener() {
        @Override public void onStatus(String s) { setStatus(s); }
        @Override public void onLog(String s) { log(s); }
        @Override public void onSyncResult(boolean anyOk) {
            log("Result: " + (anyOk ? "OK" : "FAILED"));
        }
        @Override public void onScanFinished(Map<String, BluetoothDevice> found) {
            showDeviceChooser(found);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = SyncEngine.get(this);
        engine.setListener(engineListener);
        buildUi();
        requestNeededPermissions();
        setStatus("Ready");
        if (getPrefs().getBoolean("autosync", false)) {
            AutoSyncWorker.schedule(this, 12);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (engine != null) engine.setListener(null);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Open PowerWatch Sync");
        title.setTextSize(24);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Clean-room BLE time sync utility. No cloud, no vendor assets.");
        root.addView(sub);

        macInput = new EditText(this);
        macInput.setHint("Optional MAC address (AA:BB:CC:DD:EE:FF)");
        macInput.setSingleLine(true);
        macInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        macInput.setText(getPrefs().getString("last_mac", ""));
        root.addView(macInput);

        Button scanButton = new Button(this);
        scanButton.setText("Scan / choose watch");
        scanButton.setOnClickListener(v -> {
            if (!hasPermissions()) { requestNeededPermissions(); return; }
            engine.startScan(8000);
        });
        root.addView(scanButton);

        Button syncButton = new Button(this);
        syncButton.setText("Sync time now");
        syncButton.setOnClickListener(v -> syncNow());
        root.addView(syncButton);

        Button syncAllButton = new Button(this);
        syncAllButton.setText("Sync ALL saved watches");
        syncAllButton.setOnClickListener(v -> syncAll());
        root.addView(syncAllButton);

        Button testAutoButton = new Button(this);
        testAutoButton.setText("Test auto-sync now");
        testAutoButton.setOnClickListener(v -> {
            if (SyncEngine.savedMacs(this).isEmpty()) { fail("No saved watches. Use 'Scan / choose watch' first."); return; }
            log("Launching one-time auto-sync task...");
            AutoSyncWorker.runOnceNow(this);
        });
        root.addView(testAutoButton);

        Button clearButton = new Button(this);
        clearButton.setText("Clear saved watch list");
        clearButton.setOnClickListener(v -> {
            SyncEngine.clearSavedMacs(this);
            log("Saved watch list cleared");
        });
        root.addView(clearButton);

        CheckBox autoBox = new CheckBox(this);
        autoBox.setText("Auto-sync every 12 hours");
        autoBox.setChecked(getPrefs().getBoolean("autosync", false));
        autoBox.setOnCheckedChangeListener((b, on) -> {
            getPrefs().edit().putBoolean("autosync", on).apply();
            if (on) {
                AutoSyncWorker.schedule(this, 12);
                log("Auto-sync enabled: every 12 h (survives reboot)");
            } else {
                AutoSyncWorker.cancel(this);
                log("Auto-sync disabled");
            }
        });
        root.addView(autoBox);

        status = new TextView(this);
        status.setTextSize(18);
        root.addView(status);

        logView = new TextView(this);
        logView.setTextSize(13);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private SharedPreferences getPrefs() { return getSharedPreferences("settings", MODE_PRIVATE); }

    private void setStatus(String s) { runOnUiThread(() -> status.setText("Status: " + s)); }

    private void log(String s) {
        String line = String.format(Locale.US, "%tT  %s\n", new Date(), s);
        runOnUiThread(() -> logView.append(line));
    }

    private void fail(String msg) {
        setStatus("ERROR");
        log("ERROR: " + msg);
    }

    private void showDeviceChooser(Map<String, BluetoothDevice> found) {
        if (found.isEmpty()) { fail("No BLE devices found. Try entering MAC manually."); return; }
        List<String> keys = new ArrayList<>(found.keySet());
        ArrayAdapter<String> arr = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, keys);
        new AlertDialog.Builder(this)
                .setTitle("Choose watch (saved for auto-sync)")
                .setAdapter(arr, (d, which) -> {
                    BluetoothDevice dev = found.get(keys.get(which));
                    String addr = SyncEngine.safeAddress(dev);
                    macInput.setText(addr);
                    getPrefs().edit().putString("last_mac", addr).apply();
                    SyncEngine.addSavedMac(this, addr);
                    log("Selected and saved: " + keys.get(which));
                    setStatus("Selected");
                })
                .show();
    }

    private void syncNow() {
        String mac = macInput.getText().toString().trim().toUpperCase(Locale.US);
        if (mac.length() != 17) { fail("Choose device or enter MAC first"); return; }
        engine.clearQueue();
        engine.queueMac(mac);
        engine.startQueue(false);
    }

    private void syncAll() {
        Set<String> macs = SyncEngine.savedMacs(this);
        if (macs.isEmpty()) { fail("No saved watches. Use 'Scan / choose watch' first."); return; }
        engine.clearQueue();
        for (String m : macs) engine.queueMac(m);
        log("Syncing " + macs.size() + " watch(es)...");
        engine.startQueue(true);
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] { Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT }, 42);
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 42);
        }
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    }
    
