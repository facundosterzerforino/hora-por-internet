package org.librehora;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Asks a few internet time servers what time it is and, if the phone is off
 * by one second or more, sets the clock as root with the system's own
 * {@code date -s}. On Android it goes through /dev/alarm, so the hardware
 * clock is also set and the system sends the "time changed" broadcast.
 */
public class SyncService extends IntentService {
    static final String TAG = "LibreHora";
    static final String EXTRA_FORCE = "force";
    static final String PREFS = "estado";
    static final String KEY_LAST_OK = "ultima_ok";           // wall time of the last good sync
    static final String KEY_LAST_TRY_TICK = "ultimo_intento"; // elapsedRealtime of the last try
    static final String KEY_STATUS = "estado";               // text shown in the app

    /** Servers asked, in order. Plain NTP: no certificates, so it works even with a wrong date. */
    private static final String[] SERVERS = {"pool.ntp.org", "time.cloudflare.com", "time.google.com"};
    private static final long SYNC_EVERY_MS = 12 * AlarmManager.INTERVAL_HOUR;
    private static final long RETRY_AFTER_MS = 15 * 60 * 1000L;
    private static final long MAX_ROUND_TRIP_MS = 2000;
    private static final long MIN_FIX_MS = 1000;
    /** No server answer before this date is believed (the app was written on 2026-11-16). */
    private static final long EARLIEST_BELIEVABLE_MS = 1794787200000L;

    public SyncService() {
        super("LibreHora");
    }

    /** Called on boot, on connection changes and by the 12 h alarm. */
    static void maybeStart(Context c, boolean force) {
        scheduleCheck(c);
        if (!force && !isDue(c)) return;
        if (!isOnline(c)) return;
        c.startService(new Intent(c, SyncService.class).putExtra(EXTRA_FORCE, force));
    }

    static boolean isOnline(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    private static boolean isDue(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        long now = System.currentTimeMillis(), tick = SystemClock.elapsedRealtime();
        if (now < EARLIEST_BELIEVABLE_MS) return true; // surely wrong date: always try
        long lastTry = p.getLong(KEY_LAST_TRY_TICK, -1);
        if (lastTry >= 0 && lastTry <= tick && tick - lastTry < RETRY_AFTER_MS) return false;
        long lastOk = p.getLong(KEY_LAST_OK, 0);
        return lastOk == 0 || now < lastOk || now - lastOk >= SYNC_EVERY_MS;
    }

    /** A non-waking alarm: when the phone is asleep it simply runs at the next wake-up. */
    private static void scheduleCheck(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, NetReceiver.class).setAction(NetReceiver.ACTION_CHECK), 0);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR, pi);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        if (!force && !isDue(this)) return;
        SharedPreferences p = getSharedPreferences(PREFS, 0);
        p.edit().putLong(KEY_LAST_TRY_TICK, SystemClock.elapsedRealtime()).commit();
        status("Consultando la hora en internet…");

        List<Sntp.Answer> answers = askServers();
        if (answers.isEmpty()) {
            status("No se pudo consultar la hora (¿hay Wi-Fi?). Se vuelve a probar sola.");
            return;
        }
        long offset = answers.get(answers.size() / 2).offsetMs; // median
        long correct = System.currentTimeMillis() + offset;
        if (correct < EARLIEST_BELIEVABLE_MS) {
            status("Los servidores dieron una hora imposible; no se cambió nada.");
            return;
        }
        String done;
        if (Math.abs(offset) < MIN_FIX_MS) {
            done = "La hora estaba bien.";
        } else {
            String err = setClock(offset);
            if (err != null) {
                status("No se pudo cambiar la hora: " + err);
                return;
            }
            done = "Se corrigió la hora " + describe(offset) + ".";
        }
        // Only failures wait RETRY_AFTER_MS before the next try.
        p.edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).remove(KEY_LAST_TRY_TICK).commit();
        status(done + " Última vez: " + new SimpleDateFormat("d/M/yyyy HH:mm").format(new Date()) + ".");
    }

    private List<Sntp.Answer> askServers() {
        List<Sntp.Answer> answers = new ArrayList<Sntp.Answer>();
        for (String s : SERVERS) {
            try {
                Sntp.Answer a = Sntp.query(s, 4000);
                if (a.roundTripMs < 0 || a.roundTripMs > MAX_ROUND_TRIP_MS) {
                    Log.w(TAG, s + ": round trip " + a.roundTripMs + " ms, ignored");
                    continue;
                }
                Log.i(TAG, s + ": offset " + a.offsetMs + " ms, round trip " + a.roundTripMs + " ms");
                answers.add(a);
            } catch (Exception e) {
                Log.w(TAG, s + ": " + e);
            }
        }
        Collections.sort(answers, new Comparator<Sntp.Answer>() {
            public int compare(Sntp.Answer x, Sntp.Answer y) {
                return x.offsetMs < y.offsetMs ? -1 : x.offsetMs == y.offsetMs ? 0 : 1;
            }
        });
        if (answers.size() == 3) {
            // With three answers, drop one that disagrees by more than 2 s with the median.
            long mid = answers.get(1).offsetMs;
            for (int i = 2; i >= 0; i -= 2)
                if (Math.abs(answers.get(i).offsetMs - mid) > 2000) answers.remove(i);
        }
        return answers;
    }

    /**
     * Runs {@code date -s YYYYMMDD.hhmmss} in a root shell exactly when the
     * correct time reaches a whole second (date only takes whole seconds).
     * Returns null on success or a short error text.
     */
    private String setClock(long offset) {
        Process su = null;
        try {
            su = new ProcessBuilder("su").redirectErrorStream(true).start();
            OutputStream in = su.getOutputStream();
            BufferedReader out = new BufferedReader(new InputStreamReader(su.getInputStream()));
            in.write("id\n".getBytes());
            in.flush();
            // Waits for the Superuser answer. su may print blank lines first.
            String id;
            do {
                id = out.readLine();
            } while (id != null && !id.contains("uid="));
            if (id == null || !id.contains("uid=0")) return "no hay permiso de root";

            long correctNow = System.currentTimeMillis() + offset;
            long target = (correctNow + 1300) / 1000 * 1000;
            Thread.sleep(target - correctNow);
            String stamp = new SimpleDateFormat("yyyyMMdd.HHmmss", Locale.US).format(new Date(target));
            in.write(("date -s " + stamp + " >/dev/null; echo resultado=$?; exit\n").getBytes());
            in.flush();
            String line;
            while ((line = out.readLine()) != null) {
                if (line.startsWith("resultado=")) {
                    if (!line.equals("resultado=0")) return "date falló (" + line + ")";
                    Log.i(TAG, "clock set to " + stamp + " (offset " + offset + " ms)");
                    return null;
                }
            }
            return "el shell de root se cerró";
        } catch (Exception e) {
            return e.toString();
        } finally {
            if (su != null) su.destroy();
        }
    }

    private static String describe(long offsetMs) {
        long s = Math.abs(offsetMs) / 1000;
        String amount;
        if (s < 120) amount = s + (s == 1 ? " segundo" : " segundos");
        else if (s < 7200) amount = (s / 60) + " minutos";
        else if (s < 2 * 86400) amount = (s / 3600) + " horas";
        else amount = (s / 86400) + " días";
        return (offsetMs > 0 ? "(estaba atrasada " : "(estaba adelantada ") + amount + ")";
    }

    private void status(String text) {
        Log.i(TAG, text);
        getSharedPreferences(PREFS, 0).edit().putString(KEY_STATUS, text).commit();
    }
}
