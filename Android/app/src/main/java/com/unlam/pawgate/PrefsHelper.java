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
    /** Estado actual de la luz (sincronizado desde events light_on/off del
     *  firmware). El badge del Dashboard lo lee para mostrar verde/gris en
     *  vivo, sin esperar al proximo /metrics/today. */
    private static final String KEY_LIGHT_ON = "light_on";
    private static final String KEY_PUSH_ENABLED = "push_enabled";
    private static final String KEY_SHAKE_TO_CALL = "shake_to_call";
    private static final String KEY_PENDING_FCM_TOKEN = "pending_fcm_token";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_CYCLE_TYPE = "cycle_type";
    private static final String KEY_CYCLE_START = "cycle_start_ms";
    /** Direction de la apertura actual: "in" / "out" / null. Se setea cuando
     *  arranca un ciclo OPEN_DOOR (sea por cmd del user o por evento del
     *  firmware como respuesta a la mascota). Se usa para mostrar el label
     *  "Abriendo HACIA ADENTRO/AFUERA" en Dashboard y Control. */
    private static final String KEY_CYCLE_DIRECTION = "cycle_direction";
    /** created_at (epoch ms) del ultimo door event procesado por el sync con
     *  DoorStateMachine. Evita rearrancar el ciclo en cada polling cuando el
     *  mismo evento sigue siendo el mas reciente. */
    private static final String KEY_LAST_DOOR_EVENT_AT = "last_door_event_at_ms";

    // Tokens JWT de Cognito (vienen del endpoint POST /auth/login)
    // Override local del unread-count, usado por el badge del Dashboard
    // cuando el user acaba de marcar notifs como leidas y el POST al backend
    // todavia no impactó. Ver DashboardActivity.refreshUnreadBadge.
    private static final String KEY_UNREAD_OVERRIDE       = "notif_unread_override";
    private static final String KEY_UNREAD_OVERRIDE_AT_MS = "notif_unread_override_at_ms";

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

    public static boolean isLightOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_LIGHT_ON, false);
    }

    public static void setLightOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_LIGHT_ON, on).apply();
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

    // ===== shake_to_call =====
    // Default OFF para no sorprender al user con un cmd/call la primera vez
    // que mueva el telefono. Se activa explicitamente desde Ajustes.

    public static boolean isShakeToCallEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SHAKE_TO_CALL, false);
    }

    public static void setShakeToCallEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_SHAKE_TO_CALL, enabled).apply();
    }

    // ===== pending_fcm_token =====
    // Si Firebase nos da un token antes de que el user este logueado (ej:
    // boot inicial), lo guardamos aca y lo registramos al backend despues
    // del primer login exitoso.

    public static String getPendingFcmToken(Context ctx) {
        return prefs(ctx).getString(KEY_PENDING_FCM_TOKEN, null);
    }

    public static void setPendingFcmToken(Context ctx, String token) {
        prefs(ctx).edit().putString(KEY_PENDING_FCM_TOKEN, token).apply();
    }

    public static void clearPendingFcmToken(Context ctx) {
        prefs(ctx).edit().remove(KEY_PENDING_FCM_TOKEN).apply();
    }

    // ===== unread override (badge del Dashboard) =====
    //
    // El user marca una notif como leida en NotificacionesActivity. Eso
    // dispara un POST mark-read async. Cuando vuelve al Dashboard,
    // refreshUnreadBadge() pide GET unread-count al server. Pero el POST
    // todavia puede no haber impactado: el server devuelve el count VIEJO.
    //
    // Solucion: NotificacionesActivity guarda el count que YO se que tiene
    // que ser (le restamos lo que el user marco) como "override" en prefs,
    // con timestamp. El Dashboard, al refrescar el badge, hace:
    //   final_count = MIN(server_count, override) si override es reciente
    //   final_count = server_count                 si override es viejo
    //
    // El MIN cubre los dos casos:
    //   - server lento (no proceso el POST aun): server=3, override=2 -> 2 OK
    //   - llegaron push nuevos mientras tanto:   server=5, override=2 -> 2
    //     (esto es lo unico molesto: el badge dice 2 hasta que el override
    //      expira, pero el real es 5. Maximo 30s de inconsistencia. Aceptable.)
    //
    // Se invalida en cada onSuccess del unread-count si el server ya
    // devolvio un count <= override (significa que ya proceso nuestros POSTs).

    public static void setUnreadOverride(Context ctx, int unread) {
        prefs(ctx).edit()
                .putInt(KEY_UNREAD_OVERRIDE, Math.max(0, unread))
                .putLong(KEY_UNREAD_OVERRIDE_AT_MS, System.currentTimeMillis())
                .apply();
    }

    public static int getUnreadOverride(Context ctx) {
        return prefs(ctx).getInt(KEY_UNREAD_OVERRIDE, -1);
    }

    public static long getUnreadOverrideAt(Context ctx) {
        return prefs(ctx).getLong(KEY_UNREAD_OVERRIDE_AT_MS, 0L);
    }

    public static void clearUnreadOverride(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_UNREAD_OVERRIDE)
                .remove(KEY_UNREAD_OVERRIDE_AT_MS)
                .apply();
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
        startCycle(ctx, type, null);
    }

    /** Arranca un ciclo con direction asociada (in/out). Usado en ciclos
     *  OPEN_DOOR para que la UI muestre 'Abriendo hacia X'. */
    public static void startCycle(Context ctx, String type, String direction) {
        prefs(ctx).edit()
                .putString(KEY_CYCLE_TYPE, type)
                .putLong(KEY_CYCLE_START, SystemClock.elapsedRealtime())
                .putString(KEY_CYCLE_DIRECTION, direction)
                .apply();
    }

    /** Ajusta el inicio del ciclo a un valor especifico (millis monotonic).
     *  Usado para "saltar" a un sub-estado: si llega un evento closed del
     *  firmware y queremos forzar el estado CLOSING ahora mismo, calculamos
     *  el offset que pone elapsed justo al borde de CLOSING y lo seteamos. */
    public static void setCycleStartMs(Context ctx, long startMs) {
        prefs(ctx).edit().putLong(KEY_CYCLE_START, startMs).apply();
    }

    public static String getCycleDirection(Context ctx) {
        return prefs(ctx).getString(KEY_CYCLE_DIRECTION, null);
    }

    public static long getLastDoorEventAt(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_DOOR_EVENT_AT, 0L);
    }

    public static void setLastDoorEventAt(Context ctx, long ms) {
        prefs(ctx).edit().putLong(KEY_LAST_DOOR_EVENT_AT, ms).apply();
    }

    /** Cancela el ciclo actual (cycleType = NONE). */
    public static void clearCycle(Context ctx) {
        prefs(ctx).edit()
                .putString(KEY_CYCLE_TYPE, CYCLE_NONE)
                .putLong(KEY_CYCLE_START, 0L)
                .remove(KEY_CYCLE_DIRECTION)
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
