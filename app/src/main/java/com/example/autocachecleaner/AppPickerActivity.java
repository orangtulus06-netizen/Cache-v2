package com.example.autocachecleaner;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

public class AppPickerActivity extends Activity {
    private final List<String[]> apps = new ArrayList<>();
    private final List<String[]> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private ListView list;
    private EditText search;
    private TextView counter;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        selected.addAll(prefs.getStringSet("targets", new HashSet<String>()));

        PackageManager pm = getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            apps.add(new String[]{String.valueOf(pm.getApplicationLabel(ai)), ai.packageName});
        }
        Collections.sort(apps, (x, y) -> x[0].compareToIgnoreCase(y[0]));

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        counter = new TextView(this);
        counter.setTextSize(18);
        root.addView(counter, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(this);
        search.setHint("Cari aplikasi atau nama paket");
        search.setSingleLine(true);
        root.addView(search, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable e) { refresh(); }
        });

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            String pkg = shown.get(pos)[1];
            if (list.isItemChecked(pos)) selected.add(pkg); else selected.remove(pkg);
            prefs.edit().putStringSet("targets", new HashSet<>(selected)).apply();
            updateCounter();
        });
        root.addView(list, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button done = new Button(this);
        done.setText("Selesai");
        done.setOnClickListener(v -> finish());
        root.addView(done, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refresh();
    }

    private void refresh() {
        String q = search.getText().toString().trim().toLowerCase();
        shown.clear();
        List<String> labels = new ArrayList<>();
        for (String[] a : apps) {
            if (q.isEmpty() || a[0].toLowerCase().contains(q) || a[1].toLowerCase().contains(q)) {
                shown.add(a);
                labels.add(a[0] + "\n" + a[1]);
            }
        }
        list.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_multiple_choice, labels));
        for (int i = 0; i < shown.size(); i++) {
            list.setItemChecked(i, selected.contains(shown.get(i)[1]));
        }
        updateCounter();
    }

    private void updateCounter() {
        counter.setText("Dipilih: " + selected.size() + " aplikasi");
    }
}
