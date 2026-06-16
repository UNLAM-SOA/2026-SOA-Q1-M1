package com.unlam.pawgate;

import android.content.Context;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessaging;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

/**
 * Helper para sincronizar el FCM token con el backend.
 *
 * FirebaseMessagingService.onNewToken() SOLO se dispara cuando Google genera
 * un token nuevo (primera instalacion, reinstalacion, cambio de cuenta de
 * Google, etc). Si el token ya estaba asignado y la primera registracion
 * fallo (por ejemplo: backend no estaba listo y devolvio 401), el callback
 * no vuelve a disparar nunca y el endpoint queda sin registrar.
 *
 * Para cubrir ese caso, llamamos FirebaseMessaging.getInstance().getToken()
 * proactivamente desde LoginActivity (despues del login) y desde
 * DashboardActivity (al abrir la app). Si el token ya fue registrado
 * previamente, SNS hace upsert silencioso.
 */
public final class FcmTokenSync {

    private static final String TAG = "FcmTokenSync";

    private FcmTokenSync() {}

    /**
     * Pide el FCM token actual a Firebase y lo registra con el backend.
     * Si no hay user logueado (idToken vacio), no hace nada.
     * Idempotente: SNS.createPlatformEndpoint upsert si el ARN ya existe.
     */
    public static void syncIfLoggedIn(Context ctx) {
        String idToken = PrefsHelper.getIdToken(ctx);
        if (idToken == null || idToken.isEmpty()) {
            Log.i(TAG, "no user session, skip FCM sync");
            return;
        }
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    if (token == null || token.isEmpty()) {
                        Log.w(TAG, "Firebase returned empty token");
                        return;
                    }
                    Log.i(TAG, "Got FCM token from Firebase: "
                            + token.substring(0, Math.min(20, token.length())) + "...");
                    new DeviceRepository(ctx).registerFcmToken(token,
                            new ApiCallback<DeviceDtos.RegisterFcmTokenResponse>() {
                        @Override
                        public void onSuccess(DeviceDtos.RegisterFcmTokenResponse r) {
                            Log.i(TAG, "FCM endpoint registered: " + r.endpoint_arn);
                            PrefsHelper.clearPendingFcmToken(ctx);
                        }
                        @Override
                        public void onError(String message) {
                            Log.w(TAG, "registerFcmToken failed: " + message);
                            // Lo guardamos como pending por las dudas - retry en el proximo
                            // login o en proxima apertura del Dashboard.
                            PrefsHelper.setPendingFcmToken(ctx, token);
                        }
                    });
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "FirebaseMessaging.getToken failed: " + e.getMessage()));
    }
}
