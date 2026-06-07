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
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_CYCLE_TYPE = "cycle_type";
    private static final String KEY_CYCLE_START = "cycle_start_ms";

    // Tokens JWT de Cognito (vienen del endpoint POST /auth/login)
    private static final String KEY_ID_TOKEN = "auth_id_token";
    private static final String KEY_ACCESS_TOKEN = "auth_access_token";
    private static final String KEY_REFRESH_TOKEN = "auth_refresh_token";
    private static final String KEY_TOKEN_EXPIRES_AT = "auth_token_expires_at_ms";

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

    // ===== user_name =====

    public static String getUserName(Context ctx) {
        return prefs(ctx).getString(KEY_USER_NAME, null);
    }

    public static void setUserName(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_USER_NAME, name).apply();
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

    // ===== tokens JWT de Cognito =====

    /** Guarda los 3 tokens devueltos por POST /auth/login + calcula expiresAt absoluto. */
    public static void setTokens(Context ctx,
                                 String idToken,
                                 String accessToken,
                                 String refreshToken,
                                 int expiresInSeconds) {
        long expiresAtMs = System.currentTimeMillis() + (expiresInSeconds * 1000L);
        prefs(ctx).edit()
                .putString(KEY_ID_TOKEN, idToken)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_TOKEN_EXPIRES_AT, expiresAtMs)
                .apply();
    }

    /** El token que el API Gateway Cognito Authorizer espera en el header Authorization. */
    public static String getIdToken(Context ctx) {
        return prefs(ctx).getString(KEY_ID_TOKEN, null);
    }

    public static String getAccessToken(Context ctx) {
        return prefs(ctx).getString(KEY_ACCESS_TOKEN, null);
    }

    public static String getRefreshToken(Context ctx) {
        return prefs(ctx).getString(KEY_REFRESH_TOKEN, null);
    }

    /** True si hay un token guardado y todavia no vencio. */
    public static boolean isLoggedIn(Context ctx) {
        String idToken = getIdToken(ctx);
        if (idToken == null) return false;
        long expiresAt = prefs(ctx).getLong(KEY_TOKEN_EXPIRES_AT, 0L);
        return System.currentTimeMillis() < expiresAt;
    }

    /** Borra los 3 tokens + email + nombre. Llamar al hacer logout. */
    public static void clearAuth(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_ID_TOKEN)
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_TOKEN_EXPIRES_AT)
                .remove(KEY_USER_EMAIL)
                .remove(KEY_USER_NAME)
                .apply();
    }
}
