package com.unlam.pawgate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

/**
 * Foreground Service que pollea el backend cada 3s y emite broadcasts con el
 * ultimo evento. Sobrevive a la navegacion entre Activities y al background.
 *
 * Comunicacion con Activities: sendBroadcast con action ACTION_EVENT_UPDATE.
 * Las Activities se suscriben con un BroadcastReceiver registrado en onResume.
 */
public class PawGatePollingService extends Service {

    private static final String TAG = "PawGatePollingSvc";

    public static final String ACTION_EVENT_UPDATE = "com.unlam.pawgate.ACTION_EVENT_UPDATE";
    public static final String EXTRA_EVENT_TYPE = "event_type";
    public static final String EXTRA_CREATED_AT_ISO = "created_at";
    public static final String EXTRA_CREATED_AT_MS = "created_at_ms";

    private static final String CHANNEL_ID = "pawgate_polling_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static final long POLL_INTERVAL_MS = 3_000L;
    private static final long POLL_WINDOW_MS = 5L * 60L * 1000L;

    private Handler handler;
    private DeviceRepository deviceRepo;
    private String deviceId;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            pollBackend();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        this.handler = new Handler(Looper.getMainLooper());
        this.deviceRepo = new DeviceRepository(this);
        this.deviceId = getString(R.string.default_device_id);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        // startForeground tiene que llamarse dentro de los 5s post startForegroundService
        // o el sistema mata la app con ForegroundServiceDidNotStartInTimeException.
        Notification notification = buildNotification("Monitoreando puerta…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        handler.removeCallbacks(pollRunnable); // evitar duplicados
        handler.post(pollRunnable);

        // START_STICKY: si el OS nos mata, nos recrea cuando haya memoria.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (handler != null) {
            handler.removeCallbacks(pollRunnable);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // este Service no es bindable
    }

    // ============================================================
    // POLLING
    // ============================================================

    private void pollBackend() {
        long now = System.currentTimeMillis();
        Long from = now - POLL_WINDOW_MS;
        deviceRepo.history(deviceId, from, now, new ApiCallback<DeviceDtos.HistoryResponse>() {
            @Override
            public void onSuccess(DeviceDtos.HistoryResponse result) {
                if (result == null || result.events == null || result.events.isEmpty()) {
                    return;
                }
                DeviceDtos.Event mostRecent = pickMostRecent(result.events);
                if (mostRecent == null) return;

                String label = humanLabelFor(mostRecent.event_type);
                updateNotification("Último: " + label);
                reconcileBlockedFlag(mostRecent);
                broadcastEvent(mostRecent);
            }
            @Override
            public void onError(String message) {
                Log.w(TAG, "poll error: " + message);
            }
        });
    }

    private DeviceDtos.Event pickMostRecent(java.util.List<DeviceDtos.Event> events) {
        DeviceDtos.Event best = null;
        long bestMs = -1L;
        for (DeviceDtos.Event e : events) {
            long ms = HistorialMapper.parseIsoToMs(e.created_at);
            if (ms > bestMs) {
                bestMs = ms;
                best = e;
            }
        }
        return best;
    }

    private void reconcileBlockedFlag(DeviceDtos.Event e) {
        if (e.event_type == null) return;
        boolean locallyBlocked = PrefsHelper.isDoorBlocked(this);
        if ("blocked".equals(e.event_type) && !locallyBlocked) {
            PrefsHelper.setDoorBlocked(this, true);
            PrefsHelper.clearCycle(this);
        } else if ("unblocked".equals(e.event_type) && locallyBlocked) {
            PrefsHelper.setDoorBlocked(this, false);
        }
    }

    private void broadcastEvent(DeviceDtos.Event e) {
        Intent broadcast = new Intent(ACTION_EVENT_UPDATE);
        broadcast.setPackage(getPackageName()); // restringir solo a nuestra app
        broadcast.putExtra(EXTRA_EVENT_TYPE, e.event_type);
        broadcast.putExtra(EXTRA_CREATED_AT_ISO, e.created_at);
        broadcast.putExtra(EXTRA_CREATED_AT_MS, HistorialMapper.parseIsoToMs(e.created_at));
        sendBroadcast(broadcast);
    }

    // ============================================================
    // NOTIFICATION
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "PawGate Monitoreo",
                    NotificationManager.IMPORTANCE_LOW // sin sonido ni vibracion
            );
            channel.setDescription("Notifica que la app esta monitoreando la puerta en segundo plano");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText) {
        Intent openAppIntent = new Intent(this, DashboardActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("PawGate activo")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_door_open)
                .setOngoing(true)
                .setContentIntent(contentPi)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(contentText));
        }
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private String humanLabelFor(String eventType) {
        if (eventType == null) return "evento";
        switch (eventType) {
            case "opened":           return "puerta abierta";
            case "closed":           return "puerta cerrada";
            case "blocked":          return "bloqueada";
            case "unblocked":        return "desbloqueada";
            case "calling":          return "llamando";
            case "call_ended":       return "llamada finalizada";
            case "light_on":         return "luz encendida";
            case "light_off":        return "luz apagada";
            case "wifi_reconnected": return "wifi reconectado";
            case "schedule_fired":   return "horario ejecutado";
            default:                 return eventType;
        }
    }

    // ============================================================
    // STATIC HELPERS
    // ============================================================

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, PawGatePollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, PawGatePollingService.class);
        ctx.stopService(i);
    }
}
