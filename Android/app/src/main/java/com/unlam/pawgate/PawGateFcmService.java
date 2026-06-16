package com.unlam.pawgate;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

import java.util.Map;

/**
 * Servicio que escucha mensajes FCM (Firebase Cloud Messaging).
 *
 * Dos eventos de Firebase nos interesan:
 *
 *  - onNewToken(token): cuando Google le asigna un device token al cliente
 *    (primera instalacion, reinstalacion, o cuando Google rota el token).
 *    Tenemos que mandarlo al backend para que SNS lo registre como endpoint
 *    de la Platform Application, asociado al user logueado.
 *
 *  - onMessageReceived(message): cuando llega una notif push estando la app
 *    abierta (foreground). Si la app esta cerrada, Android Sistema muestra
 *    la notif automaticamente desde el payload "notification" del mensaje;
 *    pero nosotros mandamos solo "data" desde el backend (ver eventIngest)
 *    para tener UI consistente, asi este callback dispara en ambos casos.
 *
 * Registrado en AndroidManifest.xml con intent-filter de MESSAGING_EVENT.
 */
public class PawGateFcmService extends FirebaseMessagingService {

    private static final String TAG = "PawGateFcm";
    public static final String CHANNEL_ID = "pawgate_door_events";

    // ============================================================
    // Token lifecycle
    // ============================================================

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "onNewToken: " + token.substring(0, Math.min(20, token.length())) + "...");
        registerTokenIfLoggedIn(token);
    }

    /**
     * Manda el token al backend si hay un user logueado. Si no hay sesion
     * (caso boot inicial o post-logout), guardamos el token en SharedPrefs
     * y lo registramos despues del primer login exitoso.
     */
    private void registerTokenIfLoggedIn(String token) {
        // SIEMPRE guardamos el token como pending primero. Asi si el call al
        // backend falla (401 por token vencido, 5xx, sin red, etc), queda
        // persistido y LoginActivity.registerPendingFcmTokenIfAny() lo
        // reintenta en el proximo login. clearPendingFcmToken solo se llama
        // en el path de exito.
        PrefsHelper.setPendingFcmToken(getApplicationContext(), token);

        String idToken = PrefsHelper.getIdToken(getApplicationContext());
        if (idToken == null || idToken.isEmpty()) {
            Log.i(TAG, "No logged-in user yet, deferring FCM token registration");
            return;
        }
        DeviceRepository repo = new DeviceRepository(getApplicationContext());
        repo.registerFcmToken(token, new ApiCallback<DeviceDtos.RegisterFcmTokenResponse>() {
            @Override public void onSuccess(DeviceDtos.RegisterFcmTokenResponse result) {
                Log.i(TAG, "FCM token registered with backend: " + result.endpoint_arn);
                PrefsHelper.clearPendingFcmToken(getApplicationContext());
            }
            @Override public void onError(String message) {
                Log.w(TAG, "FCM token registration failed: " + message);
                // pending queda seteado, se reintenta en el proximo login.
            }
        });
    }

    // ============================================================
    // Mensajes recibidos
    // ============================================================

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        // Respetar el toggle del user. Si Ajustes -> Push esta OFF,
        // descartamos la notificacion (el backend no sabe del toggle).
        if (!PrefsHelper.isPushEnabled(getApplicationContext())) {
            Log.i(TAG, "Push received but disabled by user setting, dropping");
            return;
        }

        Map<String, String> data = message.getData();
        String title = data.getOrDefault("title", "PawGate");
        String body  = data.getOrDefault("body", "Nueva actividad");
        String eventType = data.get("event_type");
        // notif_id permite que al tap el push, NotificacionesActivity sepa
        // exactamente cual entrada marcar como leida (sin marcar todas).
        // El backend (eventIngest._notify_owners) lo incluye en el payload
        // per-endpoint, asi cada user recibe el push con SU notif_id.
        String notifId = data.get("notif_id");

        Log.i(TAG, "onMessageReceived: " + title + " - " + body
                + " (" + eventType + ", notif_id=" + notifId + ")");
        showNotification(title, body, notifId);
    }

    private void showNotification(String title, String body, String notifId) {
        Context ctx = getApplicationContext();
        ensureChannel(ctx);

        // Intent que abre Notificaciones al tocar la notif. Le pasamos el
        // notif_id como extra para que la activity lo marque como leido sin
        // necesidad del tap manual. Si notifId es null o vacio (push viejo
        // sin notif_id), igual abre Notificaciones, pero sin auto-mark.
        Intent intent = new Intent(ctx, NotificacionesActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (notifId != null && !notifId.isEmpty()) {
            intent.putExtra(NotificacionesActivity.EXTRA_NOTIF_ID_TO_READ, notifId);
        }
        // requestCode distinto por notif para que el PendingIntent NO se
        // reutilice entre pushes distintos (con FLAG_UPDATE_CURRENT, dos
        // PendingIntents con requestCode=0 colapsan en uno solo).
        int requestCode = notifId != null
                ? notifId.hashCode()
                : (int) (System.currentTimeMillis() % Integer.MAX_VALUE);
        PendingIntent pending = PendingIntent.getActivity(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_dog)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending);

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        // ID = current time. Asi cada push es una notif nueva en lugar de
        // overwrite. Si la inundacion es un problema, ponemos un id fijo.
        nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), builder.build());
    }

    /** Crea el notification channel si no existe. Idempotente. */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Eventos de la puerta",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Avisos cuando la puerta se abre, cierra o bloquea");
        nm.createNotificationChannel(channel);
    }
}
