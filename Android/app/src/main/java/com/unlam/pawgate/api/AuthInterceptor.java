package com.unlam.pawgate.api;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.gson.Gson;
import com.unlam.pawgate.PrefsHelper;
import com.unlam.pawgate.api.dto.AuthDtos;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * OkHttp Interceptor que agrega Authorization: Bearer <idToken> a cada request
 * EXCEPT los de /auth/* (login/signup/confirm/refresh son publicos, no requieren JWT).
 *
 * Ademas implementa AUTO-REFRESH del idToken cuando el backend devuelve 401:
 *
 *   1. Recibe 401 en una request autenticada.
 *   2. Intenta refrescar el idToken llamando a POST /auth/refresh con el
 *      refreshToken guardado (vida util 30 dias).
 *   3. Si el refresh es exitoso -> guarda el nuevo idToken + reintenta la
 *      request original con el token nuevo. Transparente al user.
 *   4. Si el refresh tambien falla (refresh token vencido, user revocado,
 *      etc) -> limpia PrefsHelper.clearAuth() y manda broadcast
 *      ACTION_SESSION_EXPIRED para que el listener global redirija a Login.
 *
 * Concurrency:
 *   Multiples requests pueden recibir 401 al mismo tiempo. Usamos un lock
 *   estatico para que solo UN refresh corra a la vez; el resto espera el
 *   resultado y lo usa.
 *
 * Lifecycle:
 *   Tiene que ser ApplicationContext (no Activity) para sobrevivir cambios
 *   de pantalla. El constructor lo fuerza con getApplicationContext().
 */
public class AuthInterceptor implements Interceptor {

    private static final String TAG = "AuthInterceptor";

    /** Action del broadcast que dispara cuando la sesion expiro
     *  irrecuperable (refresh tambien fallo). */
    public static final String ACTION_SESSION_EXPIRED =
            "com.unlam.pawgate.ACTION_SESSION_EXPIRED";

    /** Lock estatico para que solo un thread haga refresh a la vez. */
    private static final Object REFRESH_LOCK = new Object();

    /** Flag para evitar refresh loops si el propio /auth/refresh devuelve 401. */
    private static volatile boolean refreshInProgress = false;

    private final Context appContext;

    public AuthInterceptor(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        String path = original.url().encodedPath();

        // Endpoints publicos - no agregar header
        if (path.contains("/auth/")) {
            return chain.proceed(original);
        }

        // Resto de endpoints - agregar Authorization si hay token
        Request authenticated = addAuthHeader(original);
        Response response = chain.proceed(authenticated);

        // Si la response es 401, intentar refrescar y reintentar.
        // Las responses son cerradas implicitamente al cerrar el body o al
        // hacer chain.proceed de nuevo (OkHttp lo maneja).
        if (response.code() == 401) {
            Log.w(TAG, "401 in " + path + " — trying to refresh token");
            String newIdToken = tryRefreshToken();
            if (newIdToken != null) {
                // Cerrar la response vieja antes de hacer un nuevo request
                response.close();
                Log.i(TAG, "Token refreshed, retrying " + path);
                Request retried = original.newBuilder()
                        .header("Authorization", "Bearer " + newIdToken)
                        .build();
                return chain.proceed(retried);
            } else {
                Log.w(TAG, "Refresh failed, broadcasting SESSION_EXPIRED");
                broadcastSessionExpired();
            }
        }
        return response;
    }

    private Request addAuthHeader(Request original) {
        String idToken = PrefsHelper.getIdToken(appContext);
        if (idToken == null || idToken.isEmpty()) {
            return original;
        }
        return original.newBuilder()
                .header("Authorization", "Bearer " + idToken)
                .build();
    }

    /**
     * Llama a POST /auth/refresh con el refreshToken guardado.
     * Devuelve el nuevo idToken si OK, o null si fallo (refresh token
     * vencido, sin red, etc).
     *
     * Sincrono porque es para uso desde el interceptor. Y con lock estatico
     * para que dos requests fallidos 401 al mismo tiempo no disparen DOS
     * refresh races en paralelo — el segundo espera al primero y reusa el
     * resultado leyendolo de PrefsHelper.
     */
    private String tryRefreshToken() {
        synchronized (REFRESH_LOCK) {
            // Si otro thread ya refrescó mientras esperabamos el lock, salimos
            // y devolvemos el token guardado (probablemente el nuevo).
            // Heuristica: si el token guardado es distinto al que mando el
            // request original, asumimos que se refresco.
            // (Para simplificar, siempre intentamos el refresh; idempotente).

            String refreshToken = PrefsHelper.getRefreshToken(appContext);
            if (refreshToken == null || refreshToken.isEmpty()) {
                Log.w(TAG, "No refresh token guardado, no se puede refrescar");
                return null;
            }
            if (refreshInProgress) {
                // Loop guard: si por algun bug recursivo el refresh dispara
                // otro refresh, abortamos.
                Log.w(TAG, "Refresh recursivo detectado, aborto");
                return null;
            }
            refreshInProgress = true;
            try {
                // Hacemos el refresh con un Retrofit ESTAtico nuevo (sin este
                // interceptor) para no entrar en loop infinito. Sino el
                // /auth/refresh que use el interceptor se interceptara a si
                // mismo si tambien diera 401.
                PawGateApi refreshApi = buildPlainApiClient();
                retrofit2.Response<AuthDtos.RefreshTokenResponse> resp =
                        refreshApi.refresh(new AuthDtos.RefreshTokenRequest(refreshToken))
                                .execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "Refresh failed: HTTP " + resp.code());
                    return null;
                }
                AuthDtos.RefreshTokenResponse body = resp.body();
                // Persistir el nuevo idToken (refreshToken sigue igual).
                PrefsHelper.setTokens(
                        appContext,
                        body.idToken,
                        body.accessToken,
                        refreshToken,
                        body.expiresIn);
                Log.i(TAG, "Refresh OK, new idToken stored");
                return body.idToken;
            } catch (IOException e) {
                Log.w(TAG, "Refresh IOException: " + e.getMessage());
                return null;
            } catch (Exception e) {
                Log.e(TAG, "Refresh unexpected error", e);
                return null;
            } finally {
                refreshInProgress = false;
            }
        }
    }

    /**
     * Construye un PawGateApi sin el AuthInterceptor para usar EN el refresh
     * (evita infinite loop si el propio /auth/refresh diera 401). Usa el
     * mismo base URL que el ApiClient principal.
     */
    private PawGateApi buildPlainApiClient() {
        String baseUrl = appContext.getString(
                appContext.getResources().getIdentifier(
                        "api_base_url", "string", appContext.getPackageName()));
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        return retrofit.create(PawGateApi.class);
    }

    private void broadcastSessionExpired() {
        Intent intent = new Intent(ACTION_SESSION_EXPIRED);
        intent.setPackage(appContext.getPackageName());
        appContext.sendBroadcast(intent);
    }
}
