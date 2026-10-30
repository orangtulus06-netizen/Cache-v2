package com.example.autocachecleaner;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.*;
import java.io.File;
import java.util.concurrent.TimeUnit;

public class CleanerService extends Service {
    static final String ACTION_STOP = "com.example.autocachecleaner.STOP";
    private static final String CHANNEL = "cache_cleaner";
    private static final int NOTIFICATION_ID = 7;
    private static final String ROOT_CMD =
        "for d in /data/user/*/*/cache /data/media/*/Android/data/*/cache; do " +
        "[ -d \"$d\" ] && find \"$d\" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + 2>/dev/null; " +
        "done; exit 0";

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

    private String cleanCache() {
        runs++;
        if (rootMode) {
               java.lang.Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"su", "-c", ROOT_CMD});
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "Root timeout (run #" + runs + ")";
                }
                return p.exitValue() == 0 ? "Cache semua aplikasi dibersihkan (root), run #" + runs
                                          : "Root ditolak/gagal (kode " + p.exitValue() + ")";
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
