package com.personal.snowdrift;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки и перевод значений ползунков (0..100) в физику. */
final class Prefs {
    static final String ENABLED = "enabled";
    static final String SPEED = "speed";
    static final String INTENSITY = "intensity";
    static final String DRIFT = "drift_max";
    static final String STORM = "storm";
    static final String ECO = "eco";

    static final int DEF_SPEED = 50;
    static final int DEF_INTENSITY = 35;
    static final int DEF_DRIFT = 35;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("snow", Context.MODE_PRIVATE);
    }

    static boolean enabled(SharedPreferences p) { return p.getBoolean(ENABLED, true); }
    static boolean storm(SharedPreferences p) { return p.getBoolean(STORM, false); }
    static boolean eco(SharedPreferences p) { return p.getBoolean(ECO, false); }
    static int speed(SharedPreferences p) { return p.getInt(SPEED, DEF_SPEED); }
    static int intensity(SharedPreferences p) { return p.getInt(INTENSITY, DEF_INTENSITY); }
    static int drift(SharedPreferences p) { return p.getInt(DRIFT, DEF_DRIFT); }

    /** 0..100 -> множитель скорости 0.3..3 (50 ≈ 0.95). */
    static float speedFactor(int v) { return (float) (0.3 * Math.pow(10, v / 100.0)); }

    /** 0..100 -> число снежинок 10..350. */
    static int flakeCount(int v) { return 10 + Math.round(v * 3.4f); }

    /** 0..100 -> максимальная высота сугроба 20..140 dp. */
    static float driftMaxDp(int v) { return 20 + v * 1.2f; }
}
