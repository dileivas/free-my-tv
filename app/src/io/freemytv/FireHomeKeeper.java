package io.freemytv;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fire TV (Fire OS): Amazon's launcher keeps the Home button (`priority=950` on `CATEGORY_HOME`), and
 * `cmd package set-home-activity` answers "Success" without changing anything. This service puts our home screen back
 * on top instead: it reads the foreground package from UsageStats and, when Amazon's launcher comes to the front, brings
 * our own task forward with {@link ActivityManager.AppTask#moveToFront()}.
 *
 * <p>Same idea as dumbtv's Keeper (MIT, github.com/humblemuzzu/dumbtv): a plain service, no accessibility, because
 * Fire OS 6.7 refuses third-party accessibility services. The cost is the Amazon home screen showing for a fraction of a
 * second before ours replaces it — that is the Fire OS behaviour, not something this app can hide.
 *
 * <p>Off by default. Turn it on with:
 * <pre>
 *   adb shell appops set io.freemytv GET_USAGE_STATS allow
 *   adb shell am start-foreground-service -n io.freemytv/.FireHomeKeeper --ez enable true
 * </pre>
 * and off with the same command passing {@code --ez enable false}.
 */
public final class FireHomeKeeper extends Service {
    /** Intent extra: true turns the takeover on (and remembers it), false turns it off. */
    public static final String EXTRA_ENABLE = "enable";

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    /** Fire OS 6's Home goes through HomeActivity_vNext; older builds answer with the launcher package alone. */
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long POLL_MS = 250;
    private static final long DEBOUNCE_MS = 1500;

    /** Posts the app-ops grant once, off the UI thread. */
    private static final AtomicBoolean granting = new AtomicBoolean();

    private HandlerThread thread;
    private Handler worker;
    private UsageStatsManager usage;
    private long lastLaunch;
    private long lastLog;
    private static final int LOG_EVERY = 0;   // 1 = verbose keeper logging to logcat
    private boolean running;
    private String lastForeground = "";

    /** True when the user asked for the takeover on this TV. */
    static boolean enabled() {
        return HomeMode.AMAZON && Prefs.fireHome();
    }

    /** Starts it, if it is wanted and this is a Fire TV. */
    static void start(Context c) {
        if (!enabled()) return;
        Intent i = new Intent(c, FireHomeKeeper.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, FireHomeKeeper.class));
    }

    /** Turns it on or off from adb (see the class comment) and remembers the choice. */
    static void set(Context c, boolean on) {
        Prefs.setFireHome(on);
        if (on) start(c); else stop(c);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_ENABLE)) {
            boolean on = intent.getBooleanExtra(EXTRA_ENABLE, true);
            Prefs.setFireHome(on);
            if (!on) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (!enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        if (!running) {
            running = true;
            thread = new HandlerThread("fire-home-keeper");
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(poll);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                String pkg = foregroundPackage();
                if (AMAZON_LAUNCHER.equals(pkg)) bringBack();
                else if (LOG_EVERY != 0 && SystemClock.uptimeMillis() - lastLog > 5000) {
                    lastLog = SystemClock.uptimeMillis();
                    AppLog.w("fire-keeper: foreground=" + pkg + " tasks=" + taskCount());
                }
            } catch (Throwable t) {
                AppLog.w("fire-home-keeper: " + t);
            }
            if (running) worker.postDelayed(this, POLL_MS);
        }
    };

    /** The package of the last window to come to the front, by whichever source Fire OS answers. */
    private String foregroundPackage() {
        if (usage == null) usage = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        if (usage != null) {
            UsageEvents events = usage.queryEvents(now - 4_000, now);
            if (events != null) {
                UsageEvents.Event event = new UsageEvents.Event();
                String last = null;
                int n = 0;
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    n++;
                    if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = event.getPackageName();
                }
                if (last != null) {
                    dbg("events=" + n + " last=" + last);
                    lastForeground = last;
                    return last;
                }
            }
            // Fire OS 6 answers queryEvents with nothing for a sideloaded app: try the aggregated stats.
            String byStats = byUsageStats(now);
            if (byStats != null) {
                dbg("stats=" + byStats);
                lastForeground = byStats;
                return byStats;
            }
        }
        // Last resort (deprecated, and Fire OS may refuse it): the running tasks list.
        String byTasks = byRunningTask();
        if (byTasks != null) {
            dbg("runningTask=" + byTasks);
            lastForeground = byTasks;
            return byTasks;
        }
        dbg("no source answered");
        if (lastForeground.isEmpty()) grantUsage();
        return lastForeground;
    }

    private String byUsageStats(long now) {
        try {
            java.util.List<android.app.usage.UsageStats> list = usage.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 120_000, now);
            if (list == null) return null;
            android.app.usage.UsageStats best = null;
            for (android.app.usage.UsageStats u : list) {
                if (best == null || u.getLastTimeUsed() > best.getLastTimeUsed()) best = u;
            }
            return best == null ? null : best.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String byRunningTask() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am == null ? null : am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) return null;
            ActivityManager.RunningTaskInfo info = tasks.get(0);
            return info.topActivity == null ? null : info.topActivity.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Brings our home screen back to the front, at most once every {@link #DEBOUNCE_MS}. */
    private void bringBack() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < DEBOUNCE_MS) return;
        lastLaunch = now;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.AppTask> tasks = am == null ? null : am.getAppTasks();
            if (tasks != null) {
                for (ActivityManager.AppTask task : tasks) {
                    ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                    if (info == null || info.baseIntent == null || info.baseIntent.getComponent() == null) continue;
                    String cls = info.baseIntent.getComponent().getClassName();
                    if (cls != null && cls.endsWith("HomeActivity")) {
                        AppLog.w("fire-keeper: moveToFront " + cls);
                        task.moveToFront();
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.w("fire-home-keeper moveToFront: " + t);
        }
        AppLog.w("fire-keeper: no task, starting FireHomeActivity");
        // No task yet (first start after a boot): start it. FireHomeActivity has no resumeWhilePausing, which on Fire OS
        // is what keeps window focus on Amazon's paused launcher.
        try {
            startActivity(new Intent(this, FireHomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Throwable t) {
            AppLog.w("fire-home-keeper start: " + t);
        }
    }

    /** Fire OS has no screen for this permission: ADB grants it, the same way the sleep overlay gets its own. */
    private void dbg(String m) {
        if (LOG_EVERY != 0 && SystemClock.uptimeMillis() - lastLog > 3000) {
            lastLog = SystemClock.uptimeMillis();
            AppLog.w("fire-keeper: " + m);
        }
    }

    private int taskCount() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            return am == null || am.getAppTasks() == null ? -1 : am.getAppTasks().size();
        } catch (Throwable t) {
            return -2;
        }
    }

    private void grantUsage() {
        if (!granting.compareAndSet(false, true)) return;
        new Thread(() -> {
            try (Adb adb = Adb.connect(this, 10_000, null)) {
                adb.shell("appops set " + getPackageName() + " GET_USAGE_STATS allow");
            } catch (Throwable ignored) {
                // No ADB answer (the TV never allowed this app's key): the adb line in the class comment still works.
            }
        }).start();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder note;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("firehome", Ui.tr("Home screen"),
                    NotificationManager.IMPORTANCE_MIN));
            note = new Notification.Builder(this, "firehome");
        } else {
            note = new Notification.Builder(this);
        }
        startForeground(2, note.setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Home screen")).build());
    }
}
