package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The homelab server's health, from the JSON severino-metrics serves (CPU, RAM, disk, temperature, IO, containers).
 *
 * <p>Optional: without {@code homelab_url} in config.properties nothing is fetched and nothing is drawn. When the server
 * stops answering, {@link #online} goes false and the card disappears — no error state on the home screen, and the last
 * numbers are never shown as if they were current.
 */
final class Homelab {
    interface Listener { void onData(); }

    private static final long EVERY = 30_000, RETRY_AFTER_FAIL = 60_000;
    private static volatile Homelab instance;

    static volatile boolean online;
    static volatile long at;
    static volatile String name = "";
    static volatile long uptimeS = -1;
    static volatile int cores, containers = -1;
    static volatile long memUsedMb = -1, memTotalMb = -1;
    static volatile long diskUsedGb = -1, diskTotalGb = -1;
    static volatile double cpuPct = Double.NaN, load1 = Double.NaN;
    static volatile double memPct = Double.NaN, diskPct = Double.NaN;
    static volatile double tempC = Double.NaN, readKbs = Double.NaN, writeKbs = Double.NaN;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long failedAt;

    private Homelab() {}

    static Homelab get(Context c) {
        if (instance == null) {
            synchronized (Homelab.class) {
                if (instance == null) instance = new Homelab();
            }
        }
        return instance;
    }

    static boolean configured() {
        return !Config.HOMELAB_URL.trim().isEmpty();
    }

    /** Something to draw: configured, answered at least once, and still answering. */
    static boolean shown() {
        return configured() && online;
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Asks for fresh numbers, at most every 30 s (60 s after a failure). Never blocks the caller. */
    void refresh(Context c) {
        if (!configured() || fetching) return;
        long now = System.currentTimeMillis();
        if (now - at < EVERY) return;
        if (failedAt != 0 && now - failedAt < RETRY_AFTER_FAIL) return;
        fetching = true;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = fetch();
            } catch (Throwable ignored) {
                // A server that is down, a wrong port, a broken JSON: all the same to the home screen.
            }
            fetching = false;
            if (!ok) {
                online = false;   // down, timeout, wrong port, broken JSON: the card goes away
                failedAt = System.currentTimeMillis();
            }
            main.post(() -> {
                for (Listener l : new ArrayList<>(listeners)) l.onData();
            });
        }, "homelab").start();
    }

    private boolean fetch() throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(Config.HOMELAB_URL.trim()).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                online = false;
                return false;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                JSONObject j = new JSONObject(out.toString("UTF-8"));
                apply(j);
                online = true;
                at = System.currentTimeMillis();
                failedAt = 0;
                return true;
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void apply(JSONObject j) {
        name = j.optString("name", "");
        uptimeS = j.optLong("uptime_s", -1);
        JSONObject cpu = j.optJSONObject("cpu");
        if (cpu != null) {
            cpuPct = cpu.optDouble("pct", Double.NaN);
            load1 = cpu.optDouble("load1", Double.NaN);
            cores = cpu.optInt("cores", 0);
        }
        JSONObject mem = j.optJSONObject("mem");
        if (mem != null) {
            memUsedMb = mem.optLong("used_mb", -1);
            memTotalMb = mem.optLong("total_mb", -1);
            memPct = mem.optDouble("pct", Double.NaN);
        }
        JSONObject disk = j.optJSONObject("disk");
        if (disk != null) {
            diskUsedGb = disk.optLong("used_gb", -1);
            diskTotalGb = disk.optLong("total_gb", -1);
            diskPct = disk.optDouble("pct", Double.NaN);
        }
        tempC = j.optDouble("temp_c", Double.NaN);
        JSONObject io = j.optJSONObject("io");
        if (io != null) {
            readKbs = io.optDouble("read_kbs", Double.NaN);
            writeKbs = io.optDouble("write_kbs", Double.NaN);
        }
        JSONObject cont = j.optJSONObject("containers");
        if (cont != null) containers = cont.optInt("running", -1);
    }
}
