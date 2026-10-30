package com.example.autocachecleaner;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

public class CleanerService extends Service {
    static final String ACTION_STOP = "com.example.autocachecleaner.STOP";
    private static final String CHANNEL = "cache_cleaner";
    private static final int NOTIFICATION_ID = 7;

    private Handler handler;
    private HandlerThread thread;
    private long intervalMs = 900000L;
    private boolean rootMode = false;
    private long totalFreed = 0;
    private int runs = 0;
    private Runnable task;
    private NotificationManager nm;

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Auto Cache Cleaner",
                NotificationManager.IMPORTANCE_LOW));
        }
        thread = new HandlerThread("CacheCleanerWorker", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs().edit().putBoolean("running", false).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            intervalMs = Math.max(10000L, intent.getLongExtra("interval_ms", 900000L));
            rootMode = intent.getBooleanExtra("root_mode", false);
        }
        prefs().edit().putBoolean("running", true).apply();
        startForeground(NOTIFICATION_ID, notification("Aktif, pembersihan pertama segera dimulai"));
        if (task != null) handler.removeCallbacks(task);
        task = new Runnable() {
            @Override public void run() {
                String result = cleanCache();
                nm.notify(NOTIFICATION_ID, notification(result));
                handler.postDelayed(this, intervalMs);
            }
        };
        handler.post(task);
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** Menyusun skrip shell untuk mode root sesuai pengaturan terbaru. Mengembalikan null jika tidak ada target. */
    private String buildRootCmd() {
        SharedPreferences p = prefs();
        boolean selectedOnly = p.getBoolean("selected_only", false);
        boolean skipRunning = p.getBoolean("skip_running", false);
        String pk;
        if (selectedOnly) {
            StringBuilder sb = new StringBuilder();
            for (String s : p.getStringSet("targets", new HashSet<String>())) {
                if (s.matches("[A-Za-z0-9_.]+")) sb.append(s).append(' ');
            }
            if (sb.length() == 0) return null;
            pk = "'" + sb.toString().trim() + "'";
        } else {
            pk = "\"$(ls /data/user/0 2>/dev/null)\"";
        }
        return "PK=" + pk + "; SKIP=" + (skipRunning ? 1 : 0) + "; " +
            "D=$(for p in $PK; do " +
            "[ \"$p\" = com.example.autocachecleaner ] && continue; " +
            "if [ $SKIP = 1 ] && pidof \"$p\" >/dev/null 2>&1; then continue; fi; " +
            "for d in /data/user/*/$p/cache /data/media/*/Android/data/$p/cache; do " +
            "[ -d \"$d\" ] && echo \"$d\"; done; done); " +
            "[ -n \"$D\" ] || { echo \"RESULT 0 0\"; exit 0; }; " +
            "S() { t=0; for k in $(du -sk $D 2>/dev/null | cut -f1); do t=$((t+k)); done; echo $t; }; " +
            "B=$(S); " +
            "for d in $D; do find \"$d\" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + 2>/dev/null; done; " +
            "A=$(S); echo \"RESULT $B $A\"; exit 0";
    }

    private String cleanCache() {
        runs++;
        if (rootMode) {
            String cmd = buildRootCmd();
            if (cmd == null) return "Pilih minimal satu aplikasi dulu (mode aplikasi pilihan aktif)";
            java.lang.Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "Root timeout (run #" + runs + ")";
                }
                if (p.exitValue() != 0) return "Root ditolak/gagal (kode " + p.exitValue() + ")";
                long freedKb = -1;
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("RESULT ")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            try {
                                long before = Long.parseLong(parts[1]);
                                long after = Long.parseLong(parts[2]);
                                freedKb = Math.max(0, before - after);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                if (freedKb < 0) return "Run #" + runs + ": pembersihan root selesai (jumlah tidak terbaca)";
                long freed = freedKb * 1024L;
                totalFreed += freed;
                return "Run #" + runs + ": " + fmt(freed) + " dibersihkan (root), total " + fmt(totalFreed);
            } catch (Exception e) {
                return "Root tidak tersedia: " + e.getClass().getSimpleName();
            } finally {
                if (p != null) p.destroy();
            }
        }
        long freed = deleteChildren(getCacheDir()) + deleteChildren(getExternalCacheDir());
        totalFreed += freed;
        return "Run #" + runs + ": " + fmt(freed) + " dibersihkan, total " + fmt(totalFreed);
    }

    private long deleteChildren(File dir) {
        if (dir == null) return 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        long freed = 0;
        for (File f : children) {
            if (f.isDirectory()) freed += deleteChildren(f);
            long len = f.length();
            if (f.delete()) freed += len;
        }
        return freed;
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format("%.1f MB", b / 1048576.0);
    }

    private Notification notification(String text) {
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), fl);
        PendingIntent stop = PendingIntent.getService(this, 1,
            new Intent(this, CleanerService.class).setAction(ACTION_STOP), fl);
        Notification.Builder b = new Notification.Builder(this, CHANNEL);
        return b.setContentTitle("Auto Cache Cleaner")
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Hentikan", stop).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
