package org.librehora;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;

/** One screen: what the app does, the current time, the last result and a button. */
public class MainActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private TextView mClock, mStatus;
    private SharedPreferences mPrefs;
    private final Runnable mTick = new Runnable() {
        public void run() {
            mClock.setText(new SimpleDateFormat("d/M/yyyy  HH:mm:ss").format(new Date()));
            mClock.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView about = text("Pone el reloj en hora solo, con la hora de internet, "
                + "cada vez que el teléfono se conecta al Wi-Fi (como máximo cada 12 horas).", 18);
        mClock = text("", 30);
        mClock.setPadding(0, pad, 0, pad);
        mStatus = text("", 18);
        Button now = new Button(this);
        now.setText("Poner en hora ahora");
        now.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        now.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!SyncService.isOnline(MainActivity.this)) {
                    mStatus.setText("No hay conexión. Conectá el Wi-Fi y probá de nuevo.");
                    return;
                }
                SyncService.maybeStart(MainActivity.this, true);
            }
        });
        root.addView(about);
        root.addView(mClock);
        root.addView(now);
        root.addView(mStatus);
        mStatus.setPadding(0, pad, 0, 0);
        setContentView(root);
        mPrefs = getSharedPreferences(SyncService.PREFS, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPrefs.registerOnSharedPreferenceChangeListener(this);
        showStatus();
        mTick.run();
        SyncService.maybeStart(this, false);
    }

    @Override
    protected void onPause() {
        mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        mClock.removeCallbacks(mTick);
        super.onPause();
    }

    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        runOnUiThread(new Runnable() {
            public void run() {
                showStatus();
            }
        });
    }

    private void showStatus() {
        mStatus.setText(mPrefs.getString(SyncService.KEY_STATUS,
                "Todavía no se consultó la hora. La primera vez el teléfono pide permiso de "
                        + "administrador (root): tocá \"Permitir\"."));
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
