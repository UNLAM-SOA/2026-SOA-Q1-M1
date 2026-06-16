package com.unlam.pawgate;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.unlam.pawgate.api.AuthInterceptor;

/**
 * Clase Application del proyecto. Se registra en AndroidManifest con
 * android:name=".PawGateApp" sobre la <application>.
 *
 * Responsabilidad: hostear listeners globales que tienen que vivir mas alla
 * de cualquier Activity individual. En particular, el handler de session
 * expired que dispara cuando el AuthInterceptor detecta que el refresh
 * token tambien fallo.
 *
 * Mas adelante este lugar es donde van a ir cosas como:
 *  - Crash reporting init.
 *  - Analytics SDK.
 *  - Logger config.
 *  - Notification channel creation (lo hacemos en FCM service por ahora).
 */
public class PawGateApp extends Application {

    private static final String TAG = "PawGateApp";

    private final BroadcastReceiver sessionExpiredReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Log.i(TAG, "Session expired received — forcing logout");
            handleSessionExpired();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Registrar el receiver del session expired. Como es Application
        // context, sobrevive a cambios de Activity y solo se desregistra al
        // matar el proceso.
        IntentFilter filter = new IntentFilter(AuthInterceptor.ACTION_SESSION_EXPIRED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionExpiredReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sessionExpiredReceiver, filter);
        }
    }

    /**
     * Se llama cuando el refresh token falla (sesion irrecuperable):
     *   1. Frena el polling service.
     *   2. Limpia los tokens en SharedPrefs.
     *   3. Toast informando.
     *   4. Lanza LoginActivity en una task nueva, cleareando todo el back stack.
     *
     * Como esto puede llegar desde un thread de OkHttp (no main), saltamos al
     * main thread con Handler antes de hacer cualquier UI / Toast / startActivity.
     */
    private void handleSessionExpired() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                PawGatePollingService.stop(this);
            } catch (Throwable t) {
                Log.w(TAG, "stop polling failed: " + t.getMessage());
            }
            PrefsHelper.clearAuth(this);
            PrefsHelper.clearPendingFcmToken(this);

            Toast.makeText(this, R.string.session_expired, Toast.LENGTH_LONG).show();

            Intent loginIntent = new Intent(this, LoginActivity.class);
            loginIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TASK |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(loginIntent);
        });
    }
}
