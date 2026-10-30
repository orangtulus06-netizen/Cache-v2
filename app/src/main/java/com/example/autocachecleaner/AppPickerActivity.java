package com.example.autocachecleaner;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;
import java.util.HashSet;

public class MainActivity extends Activity {
    static final String PREFS = "settings";
    private EditText intervalInput;
    private CheckBox rootMode, selectedOnly, skipRunning;
    private Button pickApps;
    private TextView status;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad * 2, pad, pad);
        scroll.addView(layout);

        TextView title = new TextView(this);
        title.setText("Auto Cache Cleaner");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, matchWrap());

        TextView info = new TextView(this);
        info.setText("Mode biasa membersihkan cache aplikasi ini (internal + eksternal). "
            + "Mode root membersihkan cache aplikasi lain, semuanya atau hanya yang dipilih. "
            + "Disarankan interval 5–30 menit agar hemat baterai.");
        info.setTextSize(14);
        info.setPadding(0, pad, 0, pad);
        layout.addView(info, matchWrap());

        TextView label = new TextView(this);
        label.setText("Interval (detik, minimum 10):");
        layout.addView(label, matchWrap());

        intervalInput = new EditText(this);
        intervalInput.setSingleLine(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setText(String.valueOf(prefs.getInt("interval", 900)));
        layout.addView(intervalInput, matchWrap());

        rootMode = new CheckBox(this);
        rootMode.setText("Gunakan akses root (su) untuk cache aplikasi lain");
        rootMode.setChecked(prefs.getBoolean("root", false));
        layout.addView(rootMode, matchWrap());

        selectedOnly = new CheckBox(this);
        selectedOnly.setText("Mode root: hanya aplikasi pilihan");
        selectedOnly.setChecked(prefs.getBoolean("selected_only", false));
        selectedOnly.setOnCheckedChangeListener((v, c) ->
            prefs.edit().putBoolean("selected_only", c).apply());
        layout.addView(selectedOnly, matchWrap());

        skipRunning = new CheckBox(this);
        skipRunning.setText("Mode root: lewati aplikasi yang sedang berjalan");
        skipRunning.setChecked(prefs.getBoolean("skip_running", false));
        skipRunning.setOnCheckedChangeListener((v, c) ->
            prefs.edit().putBoolean("skip_running", c).apply());
        layout.addView(skipRunning, matchWrap());

        pickApps = new Button(this);
        pickApps.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        layout.addView(pickApps, matchWrap());

        Button start = new Button(this);
        start.setText("Mulai pembersihan otomatis");
        start.setOnClickListener(v -> startCleaner());
        layout.addView(start, matchWrap());

        Button stop = new Button(this);
        stop.setText("Hentikan");
        stop.setOnClickListener(v -> {
            startService(new Intent(this, CleanerService.class).setAction(CleanerService.ACTION_STOP));
            status.setText("Status: dihentikan");
        });
        layout.addView(stop, matchWrap());

        status = new TextView(this);
        status.setPadding(0, pad, 0, 0);
        layout.addView(status, matchWrap());

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        int n = prefs.getStringSet("targets", new HashSet<String>()).size();
        pickApps.setText("Pilih aplikasi (" + n + " dipilih)");
        status.setText(isServiceRunning()
            ? "Status: berjalan setiap " + prefs.getInt("interval", 900) + " detik"
            : "Status: belum berjalan");
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo s : am.getRunningServices(50))
            if (CleanerService.class.getName().equals(s.service.getClassName())) return true;
        return false;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void startCleaner() {
        int seconds;
        try {
            seconds = Integer.parseInt(intervalInput.getText().toString().trim());
        } catch (Exception e) {
            status.setText("Masukkan interval angka yang valid.");
            return;
        }
        if (seconds < 10) {
            status.setText("Interval minimum adalah 10 detik untuk menghindari beban berlebihan.");
            return;
        }
        prefs.edit().putInt("interval", seconds).putBoolean("root", rootMode.isChecked()).apply();
        Intent intent = new Intent(this, CleanerService.class)
            .putExtra("interval_ms", seconds * 1000L)
            .putExtra("root_mode", rootMode.isChecked());
        startForegroundService(intent);
        status.setText("Status: berjalan setiap " + seconds + " detik. Lihat notifikasi untuk detail.");
    }
}
