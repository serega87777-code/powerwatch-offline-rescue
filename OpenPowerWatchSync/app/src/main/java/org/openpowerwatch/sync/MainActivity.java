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
            log("Результат: " + (anyOk ? "ОК" : "ОШИБКА"));
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
        setStatus("Готово");
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
        title.setText("Синк PowerWatch");
        title.setTextSize(24);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Офлайн-синхронизация времени по BLE. Без облака и файлов производителя.");
        root.addView(sub);

        macInput = new EditText(this);
        macInput.setHint("MAC-адрес (необязательно, AA:BB:CC:DD:EE:FF)");
        macInput.setSingleLine(true);
        macInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        macInput.setText(getPrefs().getString("last_mac", ""));
        root.addView(macInput);

        Button scanButton = new Button(this);
        scanButton.setText("Сканировать / выбрать часы");
        scanButton.setOnClickListener(v -> {
            if (!hasPermissions()) { requestNeededPermissions(); return; }
            engine.startScan(8000);
        });
        root.addView(scanButton);

        Button syncButton = new Button(this);
        syncButton.setText("Синхронизировать время");
        syncButton.setOnClickListener(v -> syncNow());
        root.addView(syncButton);

        Button syncAllButton = new Button(this);
        syncAllButton.setText("Синхронизировать все часы");
        syncAllButton.setOnClickListener(v -> syncAll());
        root.addView(syncAllButton);

        Button testAutoButton = new Button(this);
        testAutoButton.setText("Тест автосинка сейчас");
        testAutoButton.setOnClickListener(v -> {
            if (SyncEngine.savedMacs(this).isEmpty()) { fail("Нет сохранённых часов. Сначала нажмите «Сканировать / выбрать часы»."); return; }
            log("Запуск разовой задачи автосинка...");
            AutoSyncWorker.runOnceNow(this);
        });
        root.addView(testAutoButton);

        Button clearButton = new Button(this);
        clearButton.setText("Очистить список часов");
        clearButton.setOnClickListener(v -> {
            SyncEngine.clearSavedMacs(this);
            log("Список сохранённых часов очищен");
        });
        root.addView(clearButton);

        CheckBox autoBox = new CheckBox(this);
        autoBox.setText("Автосинк каждые 12 часов");
        autoBox.setChecked(getPrefs().getBoolean("autosync", false));
        autoBox.setOnCheckedChangeListener((b, on) -> {
            getPrefs().edit().putBoolean("autosync", on).apply();
            if (on) {
                AutoSyncWorker.schedule(this, 12);
                log("Автосинк включён: каждые 12 часов (переживает перезагрузку)");
            } else {
                AutoSyncWorker.cancel(this);
                log("Автосинк выключен");
            }
        });
        root.addView(autoBox);

        CheckBox buzzBox = new CheckBox(this);
        buzzBox.setText("Вибрация при автосинке (телефон)");
        buzzBox.setChecked(getPrefs().getBoolean("vibrate_on_sync", true));
        buzzBox.setOnCheckedChangeListener((b, on) -> {
            getPrefs().edit().putBoolean("vibrate_on_sync", on).apply();
            log(on ? "Вибрация при автосинке включена" : "Вибрация при автосинке выключена");
        });
        root.addView(buzzBox);

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

    private void setStatus(String s) { runOnUiThread(() -> status.setText("Статус: " + s)); }

    private void log(String s) {
        String line = String.format(Locale.US, "%tT  %s\n", new Date(), s);
        runOnUiThread(() -> logView.append(line));
    }

    private void fail(String msg) {
        setStatus("ОШИБКА");
        log("ОШИБКА: " + msg);
    }

    private void showDeviceChooser(Map<String, BluetoothDevice> found) {
        if (found.isEmpty()) { fail("BLE-устройства не найдены. Попробуйте ввести MAC вручную."); return; }
        List<String> keys = new ArrayList<>(found.keySet());
        ArrayAdapter<String> arr = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, keys);
        new AlertDialog.Builder(this)
                .setTitle("Выберите часы (сохранятся для автосинка)")
                .setAdapter(arr, (d, which) -> {
                    BluetoothDevice dev = found.get(keys.get(which));
                    String addr = SyncEngine.safeAddress(dev);
                    macInput.setText(addr);
                    getPrefs().edit().putString("last_mac", addr).apply();
                    SyncEngine.addSavedMac(this, addr);
                    log("Выбрано и сохранено: " + keys.get(which));
                    setStatus("Выбрано");
                })
                .show();
    }

    private void syncNow() {
        String mac = macInput.getText().toString().trim().toUpperCase(Locale.US);
        if (mac.length() != 17) { fail("Сначала выберите устройство или введите MAC"); return; }
        engine.clearQueue();
        engine.queueMac(mac);
        engine.startQueue(false);
    }

    private void syncAll() {
        Set<String> macs = SyncEngine.savedMacs(this);
        if (macs.isEmpty()) { fail("Нет сохранённых часов. Сначала нажмите «Сканировать / выбрать часы»."); return; }
        engine.clearQueue();
        for (String m : macs) engine.queueMac(m);
        log("Синхронизация " + macs.size() + " час(ов)...");
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
