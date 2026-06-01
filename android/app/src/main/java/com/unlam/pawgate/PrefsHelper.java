package com.unlam.pawgate;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Wrapper sobre SharedPreferences para evitar repetir las keys / nombre del file
 * en distintas Activities. Mantiene en un solo lugar:
 *   - door_blocked  -> true si la puerta esta en modo seguridad
 *   - push_enabled  -> true si las notificaciones push estan activadas
 *
 * Patron clasico de "Repository" simplificado. Cuando agreguemos backend en
 * Fase 17, este helper queda como la unica capa que toca disco, asi swapear
 * a Cognito o a un Singleton de Cache es trivial.
 */
public final class PrefsHelper {

    private static final String PREFS = "pawgate_prefs";
    private static final String KEY_DOOR_BLOCKED = "door_blocked";
    private static final String KEY_PUSH_ENABLED = "push_enabled";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_CYCLE_TYPE = "cycle_type";
    private static final String KEY_CYCLE_START = "cycle_start_ms";

    /** Tipos de ciclo persistente. NONE = ningun ciclo activo. */
    public static final String CYCLE_NONE = "NONE";
    public static final String CYCLE_OPEN_DOOR = "OPEN_DOOR";
    public static final String CYCLE_CALL = "CALL";

    private PrefsHelper() { /* no instanciar */ }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ===== door_blocked =====

    public static boolean isDoorBlocked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DOOR_BLOCKED, false);
    }

    public static void setDoorBlocked(Context ctx, boolean blocked) {
        prefs(ctx).edit().putBoolean(KEY_DOOR_BLOCKED, blocked).apply();
    }

    // ===== push_enabled =====

    public static boolean isPushEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PUSH_ENABLED, true);
    }

    public static void setPushEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_PUSH_ENABLED, enabled).apply();
    }

    // ===== user_email =====

    public static String getUserEmail(Context ctx) {
        return prefs(ctx).getString(KEY_USER_EMAIL, null);
    }

    public static void setUserEmail(Context ctx, String email) {
        prefs(ctx).edit().putString(KEY_USER_EMAIL, email).apply();
    }

    // ===== ciclo de puerta / llamada =====
    // Guardamos: tipo de ciclo + timestamp de cuando empezo. Asi cualquier
    // Activity puede computar en que estado esta la puerta AHORA mismo,
    // sin necesidad de mantener un timer corriendo entre navegaciones.

    public static String getCycleType(Context ctx) {
        return prefs(ctx).getString(KEY_CYCLE_TYPE, CYCLE_NONE);
    }

    public static long getCycleStartMs(Context ctx) {
        return prefs(ctx).getLong(KEY_CYCLE_START, 0L);
    }

    /** Arranca un ciclo nuevo (cycleType + timestamp = now).
     *  Usa SystemClock.elapsedRealtime() (monotonic) en vez de
     *  System.currentTimeMillis() para que cambios de hora del usuario
     *  no afecten la medicion de la duracion del ciclo. */
    public static void startCycle(Context ctx, String type) {
        prefs(ctx).edit()
                .putString(KEY_CYCLE_TYPE, type)
                .putLong(KEY_CYCLE_START, SystemClock.elapsedRealtime())
                .apply();
    }

    /** Cancela el ciclo actual (cycleType = NONE). */
    public static void clearCycle(Context ctx) {
        prefs(ctx).edit()
                .putString(KEY_CYCLE_TYPE, CYCLE_NONE)
                .putLong(KEY_CYCLE_START, 0L)
                .apply();
    }
}
