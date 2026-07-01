package com.unlam.pawgate;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.NotificationRepository;
import com.unlam.pawgate.api.dto.NotificationDtos;
import com.unlam.pawgate.api.dto.ScheduleDtos;

/**
 * Dashboard - pantalla principal.
 *
 * El estado de la puerta lo computa DoorStateMachine (SharedPreferences + clock).
 * Los eventos del backend llegan via BroadcastReceiver desde PawGatePollingService.
 * El tick local de 1s solo refresca el countdown visual (OPEN/CALLING).
 *
 * Acciones:
 *   - action_open  -> si BLOQUEADA, solo redirige a Control. Sino, inicia ciclo OPEN_DOOR + redirige.
 *   - action_call  -> inicia ciclo CALL y redirige.
 *   - action_block -> dialog Bloquear/Desbloquear (toggle persistente).
 *   - action_schedules -> abre HorariosActivity.
 *   - bell -> abre NotificacionesActivity.
 */
public class DashboardActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Tick local de 1s para refrescar countdown del estado. */
    private final Runnable refreshTickRunnable = new Runnable() {
        @Override public void run() {
            renderDoorState();
            DoorStateMachine.DoorState s = DoorStateMachine.currentState(DashboardActivity.this);
            if (s != DoorStateMachine.DoorState.IDLE
                    && s != DoorStateMachine.DoorState.BLOCKED) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    private LinearLayout actionBlock;
    private ImageView actionBlockIcon;
    private TextView actionBlockLabel;
    private TextView doorStatusLabel;
    private TextView lastActivityLabel;
    private TextView openingsCountLabel;
    private View actionOpen;
    private View actionCall;

    private DeviceRepository deviceRepo;
    private ShakeDetector shakeDetector;
    private OfflineBanner offlineBanner;
    private String deviceId;
    private boolean toggleInFlight;

    private static final long STATE_POLL_INTERVAL_MS = 3_000L;

    /** Tick local que pollea /state cada 3s y sincroniza el flag BLOQUEADO. */
    private final Runnable statePollRunnable = new Runnable() {
        @Override public void run() {
            pollDeviceState();
            handler.postDelayed(this, STATE_POLL_INTERVAL_MS);
        }
    };

    private void pollDeviceState() {
        deviceRepo.getDeviceState(deviceId, new ApiCallback<ScheduleDtos.DeviceStateResponse>() {
            @Override
            public void onSuccess(ScheduleDtos.DeviceStateResponse state) {
                if (state == null || state.lock_state == null) return;
                // "AUTO_UNBLOCKED".contains("BLOCKED") es true; chequeo explicito.
                boolean shouldBeBlocked = "AUTO_BLOCKED".equals(state.lock_state)
                        || "MANUAL_BLOCKED".equals(state.lock_state);
                boolean locallyBlocked = PrefsHelper.isDoorBlocked(DashboardActivity.this);
                if (shouldBeBlocked != locallyBlocked) {
                    PrefsHelper.setDoorBlocked(DashboardActivity.this, shouldBeBlocked);
                    if (shouldBeBlocked) PrefsHelper.clearCycle(DashboardActivity.this);
                    renderDoorState();
                }
            }
            @Override public void onError(String message) { /* silencio */ }
        });
    }

    /** Recibe los broadcasts de PawGatePollingService con los eventos del backend. */
    private final BroadcastReceiver eventUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleEventUpdate(intent);
        }
    };

    /**
     * Recibe el broadcast LOCAL de NotificacionesActivity cuando el user marca
     * notifs como leidas (tap individual, "Leer todo", o tap del push).
     *
     * El refresh se hace con postDelayed(700ms) porque el POST mark-read
     * de la otra activity es UX optimista — todavia esta en vuelo cuando
     * llega este broadcast. 700ms es el roundtrip tipico al API Gateway
     * desde un device. Si fallo, en el proximo onResume se reconcilia.
     */
    private final BroadcastReceiver notifsReadChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.postDelayed(DashboardActivity.this::refreshUnreadBadge, 700);
        }
    };

    /** Permiso POST_NOTIFICATIONS (runtime desde Android 13). Si el user niega,
     *  el Service sigue funcionando pero su notification no se muestra. */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> { /* no-op: funcionamos igual sin la notification */ });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        offlineBanner = OfflineBanner.attach(this);

        // Sincronizar el FCM token con el backend. Cubre el caso 'la app ya
        // estaba instalada cuando agregamos push' donde onNewToken no se
        // dispara mas porque el token ya existe. Idempotente: si el endpoint
        // SNS ya esta registrado para este user, hace upsert silencioso.
        FcmTokenSync.syncIfLoggedIn(this);

        actionBlock = findViewById(R.id.action_block);
        actionBlockIcon = findViewById(R.id.action_block_icon);
        actionBlockLabel = findViewById(R.id.action_block_label);
        doorStatusLabel = findViewById(R.id.dashboard_door_status);
        lastActivityLabel = findViewById(R.id.dashboard_last_activity);
        openingsCountLabel = findViewById(R.id.dashboard_openings_count);
        actionOpen = findViewById(R.id.action_open);
        actionCall = findViewById(R.id.action_call);

        deviceRepo = new DeviceRepository(this);
        deviceId = getString(R.string.default_device_id);

        // Greeting inicial; tambien se re-renderiza en onResume para que tome
        // los cambios cuando el user vuelve de PerfilActivity (singleTop no
        // dispara onCreate y el saludo se quedaba con el nombre viejo).
        renderGreeting();

        ensureNotificationPermission();

        findViewById(R.id.dashboard_notification).setOnClickListener(
                v -> startActivity(new Intent(this, NotificacionesActivity.class)));
        actionOpen.setOnClickListener(v -> onActionOpenClick());
        actionBlock.setOnClickListener(v -> onBlockOrUnblockClick());
        actionCall.setOnClickListener(v -> onActionCallClick());
        findViewById(R.id.action_schedules).setOnClickListener(
                v -> startActivity(new Intent(this, HorariosActivity.class)));

        BottomNavHelper.bind(this, R.id.nav_inicio);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-render del saludo: si el user cambio su nombre en PerfilActivity
        // y vuelve aca, queremos ver el nombre nuevo sin necesidad de matar
        // el Dashboard.
        renderGreeting();
        refreshTickRunnable.run();
        statePollRunnable.run();
        // Render INICIAL del badge de luz con el ultimo estado conocido
        // (isLightOn esta persistido en prefs por el state machine). Despues
        // loadDailyMetrics() lo actualiza con el snapshot del server.
        renderLightBadge(PrefsHelper.isLightOn(this) ? "on" : "off");
        loadDailyMetrics();
        refreshUnreadBadge();

        IntentFilter filter = new IntentFilter(PawGatePollingService.ACTION_EVENT_UPDATE);
        ContextCompat.registerReceiver(
                this,
                eventUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // LocalBroadcast desde NotificacionesActivity cuando el user marca
        // notifs como leidas. Usa LocalBroadcastManager para mantener el
        // broadcast IN-PROCESS (no expuesto a otras apps).
        androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this)
                .registerReceiver(notifsReadChangedReceiver,
                        new IntentFilter(NotificacionesActivity.ACTION_NOTIFS_READ_CHANGED));

        if (offlineBanner != null) offlineBanner.start();

        // Shake-to-call (Fase 19): solo si el user lo activo en Ajustes.
        // Re-leemos el setting en cada onResume para que si volvio de Ajustes
        // con el toggle cambiado, tome efecto inmediato.
        if (PrefsHelper.isShakeToCallEnabled(this)) {
            if (shakeDetector == null) {
                shakeDetector = new ShakeDetector(this, this::onShakeDetected);
            }
            shakeDetector.start();
        }
    }

    /**
     * Callback del ShakeDetector. Dispara cmd/call y abre ControlActivity para
     * que el user vea el ciclo de llamada en vivo (countdown 'BUZZER ACTIVO').
     *
     * Flow completo:
     *   1) Vibracion 150ms — feedback haptic instantaneo de que la app
     *      registro el shake (antes de esperar el network round-trip).
     *   2) Toast corto ("Llamando a tu mascota") con LENGTH_SHORT.
     *   3) POST /devices/{id}/cmd/call al backend, que publica al topic MQTT
     *      cmd/call. El simulator/firmware lo recibe y arranca el buzzer 3s.
     *   4) Al success del cmd, abrir ControlActivity. ControlActivity tiene
     *      su propio polling de state y va a renderizar el ciclo
     *      calling -> call_ending -> idle con countdown visible.
     */
    private void onShakeDetected() {
        android.util.Log.i("DashboardActivity", "shake detected -> sending cmd/call");

        // 1) Feedback haptic instantaneo (antes incluso del network round-trip).
        vibrateShort();

        // 2) Marcar el ciclo LOCAL como CALL en SharedPrefs. Esto es lo que
        //    ControlActivity lee para renderizar 'BUZZER ACTIVO · 3s' con
        //    countdown. Si no lo seteamos antes del Intent, Control abre y
        //    no sabe que estamos llamando -> queda en idle visual aunque
        //    el cmd haya llegado al simulator.
        PrefsHelper.startCycle(this, PrefsHelper.CYCLE_CALL);

        // 3) Navegar a Control INMEDIATAMENTE para que el user vea el ciclo
        //    sin esperar al network round-trip. La UI de Control va a
        //    arrancar el countdown apenas se abre.
        startActivity(new android.content.Intent(this, ControlActivity.class));

        // 4) Disparar el cmd al backend en background. Si falla, limpiamos el
        //    ciclo local y mostramos error. Si exito, el simulator ya esta
        //    sonando el buzzer en paralelo.
        deviceRepo.sendCommand(deviceId, DeviceRepository.CMD_CALL,
                java.util.Collections.emptyMap(),
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse>() {
                    @Override public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse r) {
                        android.util.Log.i("DashboardActivity", "shake cmd/call OK");
                        PawGatePollingService.requestPollNow(DashboardActivity.this);
                    }
                    @Override public void onError(String message) {
                        android.util.Log.w("DashboardActivity", "shake call error: " + message);
                        // Revertir el ciclo local para que Control no quede
                        // mostrando un fake call que nunca llego al device.
                        PrefsHelper.clearCycle(DashboardActivity.this);
                        android.widget.Toast.makeText(DashboardActivity.this,
                                message, android.widget.Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Vibracion corta (150ms). Compatible con API 26+. */
    private void vibrateShort() {
        android.os.Vibrator v = (android.os.Vibrator)
                getSystemService(android.content.Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            v.vibrate(android.os.VibrationEffect.createOneShot(
                    150, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            // Path legacy, igual lo dejamos por las dudas
            v.vibrate(150);
        }
    }

    // ============================================================
    // Metricas del dia (aperturas hoy + ultima actividad)
    // ============================================================

    /** Fetch a /history con ventana desde el comienzo del dia para:
     *  - contar eventos opened (aperturas hoy)
     *  - tomar el evento mas reciente y mostrar "Ultima actividad: hace X". */
    private void loadDailyMetrics() {
        // Endpoint dedicado /metrics/today: el backend itera todas las paginas
        // de DDB y devuelve el conteo de aperturas + el ultimo door event +
        // tiempo de luz encendida hoy + estado actual de luz.
        deviceRepo.metricsToday(deviceId,
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.MetricsTodayResponse>() {
            @Override
            public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.MetricsTodayResponse result) {
                if (result == null) return;
                int opens = result.openings_today;
                String lastIso = result.last_door_event_at;
                int lightMin = result.light_minutes_today;
                String lightState = result.light_state;
                android.util.Log.d("DashboardMetrics",
                        "metricsToday: opens=" + opens + " lightMin=" + lightMin
                        + " lightState=" + lightState);

                if (openingsCountLabel != null) {
                    openingsCountLabel.setText(String.valueOf(opens));
                }
                renderLightTime(lightMin);
                // NO renderizamos el badge desde el server aca para evitar
                // flicker. El server puede ir 200-500ms atras del estado real
                // (el push light_on llego pero el server todavia no lo
                // proceso). El badge se renderiza UNICAMENTE desde
                // PrefsHelper.isLightOn — que el state machine actualiza al
                // instante cuando llega el push o el polling trae el event.
                // Aca solo SEMBRAMOS el estado local si nunca tuvimos info:
                // si server dice 'on' y nuestro local nunca se seteo, lo
                // tomamos como inicial. Despues los events ganan.
                if (lightState != null
                        && PrefsHelper.getLastDoorEventAt(DashboardActivity.this) == 0L) {
                    PrefsHelper.setLightOn(DashboardActivity.this,
                            "on".equals(lightState));
                    renderLightBadge(lightState);
                }

                if (lastActivityLabel != null) {
                    if (lastIso != null) {
                        String rel = HistorialMapper.relativeTimeFor(lastIso, System.currentTimeMillis());
                        lastActivityLabel.setText(getString(
                                R.string.dashboard_last_activity_template, rel));
                    } else {
                        lastActivityLabel.setText(R.string.dashboard_no_recent_activity);
                    }
                }
            }
            @Override public void onError(String message) {
                android.util.Log.w("DashboardMetrics", "metricsToday error: " + message);
            }
        });
    }

    /** Renderiza minutos de luz en el formato "X min" o "Xh Ym" segun magnitud. */
    private void renderLightTime(int totalMinutes) {
        TextView lightTimeLabel = findViewById(R.id.dashboard_light_time);
        if (lightTimeLabel == null) return;
        if (totalMinutes < 60) {
            lightTimeLabel.setText(getString(
                    R.string.dashboard_light_minutes_format, totalMinutes));
        } else {
            int h = totalMinutes / 60;
            int m = totalMinutes % 60;
            lightTimeLabel.setText(getString(
                    R.string.dashboard_light_hm_format, h, m));
        }
    }

    /**
     * Renderiza el badge de "Luz encendida / apagada" en la card de la puerta.
     * Verde (bg_pill_status, accent_neon) si encendida; gris (bg_pill_warning,
     * text_secondary) si apagada. lightState viene del server ("on"/"off"),
     * pero los eventos light_on/light_off del firmware tambien actualizan el
     * badge via DoorStateMachine + broadcast.
     */
    /**
     * Renderiza el icono de lampara al lado del minutaje:
     *   - encendida: ic_lamp_on con tinte amarillo + bg_lamp_glow (sombra
     *     radial dorada que sugiere "luz emitida")
     *   - apagada:   ic_lamp_off (contorno solamente) con tinte gris claro
     *
     * Minimalista, sin texto ni pill. El semantico contentDescription cambia
     * para accesibilidad (TalkBack).
     */
    /**
     * Render del saludo ("Hola, X") basado en el nombre persistido.
     * Se llama en onCreate y en cada onResume, para que cuando el user vuelva
     * de PerfilActivity con un nombre editado, el Dashboard refleje el cambio
     * (Dashboard es singleTop -> al volver NO se ejecuta onCreate, solo onResume).
     *
     * Prioridad de resolucion del nombre:
     *   1. PrefsHelper.getUserName  (lo setea el login y PerfilActivity al editar)
     *   2. JwtUtils.extractName(idToken)  (fallback para auto-logins viejos)
     *   3. EXTRA_USER del Intent (email del Login, ultimo recurso)
     */
    private void renderGreeting() {
        String displayName = PrefsHelper.getUserName(this);
        android.util.Log.d("DashboardGreeting",
                "renderGreeting: getUserName='" + displayName + "'");
        if (displayName == null || displayName.isEmpty()) {
            String idToken = PrefsHelper.getIdToken(this);
            if (idToken != null) {
                displayName = com.unlam.pawgate.api.JwtUtils.extractName(idToken);
                if (displayName != null) PrefsHelper.setUserName(this, displayName);
            }
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = getIntent().getStringExtra(LoginActivity.EXTRA_USER);
        }
        if (displayName == null) return;
        TextView greeting = findViewById(R.id.dashboard_greeting);
        if (greeting == null) return;
        greeting.setText(getString(R.string.dashboard_greeting_template, displayName));
        android.util.Log.d("DashboardGreeting",
                "renderGreeting: textView updated to '" + displayName + "'");
    }

    private void renderLightBadge(String lightState) {
        android.widget.ImageView lamp = findViewById(R.id.dashboard_light_badge);
        if (lamp == null) return;
        boolean isOn = "on".equals(lightState);
        if (isOn) {
            lamp.setImageResource(R.drawable.ic_lamp_on);
            // Tinte ambar / amarillo "warm" (Material amber 600)
            lamp.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFB300));
            lamp.setBackgroundResource(R.drawable.bg_lamp_glow);
            lamp.setContentDescription(getString(R.string.dashboard_light_on));
        } else {
            lamp.setImageResource(R.drawable.ic_lamp_off);
            lamp.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.text_secondary, getTheme())));
            lamp.setBackground(null);
            lamp.setContentDescription(getString(R.string.dashboard_light_off));
        }
    }

    /**
     * Refresca el badge rojo encima del bell con el conteo de notifs no leidas.
     *
     * Se llama en onResume(): cubre el caso comun de "vine de Notificaciones,
     * marque varias como leidas, vuelvo al Dashboard, el badge tiene que
     * actualizarse". Tambien cubre push notifications que llegan mientras la
     * app esta en background — al volver a foreground se ve el badge nuevo.
     *
     * Reglas de visibilidad:
     *   count == 0 -> GONE
     *   count <= 9 -> texto = count
     *   count >  9 -> texto = "9+"  (evita overflow en el badge chiquito)
     *
     * Errores son silenciosos: el badge simplemente no se actualiza. No
     * vale interrumpir al user con un toast por algo de UX.
     */
    /** Ventana de validez del override local (ver PrefsHelper.setUnreadOverride). */
    private static final long UNREAD_OVERRIDE_TTL_MS = 30_000L;

    private void refreshUnreadBadge() {
        TextView badge = findViewById(R.id.dashboard_notification_badge);
        if (badge == null) return;
        new NotificationRepository(this).unreadCount(
                new ApiCallback<NotificationDtos.UnreadCountResponse>() {
            @Override public void onSuccess(NotificationDtos.UnreadCountResponse result) {
                int serverCount = result != null ? result.unread : 0;
                int finalCount = applyLocalOverride(serverCount);
                renderBadge(badge, finalCount);
            }
            @Override public void onError(String message) {
                android.util.Log.w("DashboardBadge", "unreadCount error: " + message);
            }
        });
    }

    /**
     * Aplica el override local de NotificacionesActivity. Cubre el caso
     * donde el user acaba de marcar como leida y el POST aun no impactó:
     *
     *   serverCount=3 (no proceso), override=2 (local), -> MIN = 2
     *   serverCount=5 (llegaron push nuevos), override=2 (stale), -> MIN = 2
     *     hasta que pasen 30s y se confie en server (5 unread).
     *   serverCount=2 (proceso), override=2 -> los limpiamos.
     *   override no existe / expiro -> serverCount tal cual.
     */
    private int applyLocalOverride(int serverCount) {
        long overrideAt = PrefsHelper.getUnreadOverrideAt(this);
        if (overrideAt == 0L) return serverCount;
        long age = System.currentTimeMillis() - overrideAt;
        if (age > UNREAD_OVERRIDE_TTL_MS) {
            PrefsHelper.clearUnreadOverride(this);
            return serverCount;
        }
        int override = PrefsHelper.getUnreadOverride(this);
        if (override < 0) return serverCount;
        if (serverCount <= override) {
            // server ya proceso (o estamos en sync): limpiar override y confiar.
            PrefsHelper.clearUnreadOverride(this);
            return serverCount;
        }
        // server tiene un count mayor: o no proceso aun, o llegaron push nuevos.
        // En cualquiera de los dos casos preferimos mostrar el override (al
        // user no le gusta ver el badge volver a subir).
        return override;
    }

    private void renderBadge(TextView badge, int n) {
        if (n <= 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setVisibility(View.VISIBLE);
        badge.setText(n > 9 ? "9+" : String.valueOf(n));
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTickRunnable);
        handler.removeCallbacks(statePollRunnable);
        try {
            unregisterReceiver(eventUpdateReceiver);
        } catch (IllegalArgumentException ignored) {
            // defensivo: race conditions en lifecycle
        }
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(this)
                    .unregisterReceiver(notifsReadChangedReceiver);
        } catch (IllegalArgumentException ignored) { /* defensivo */ }
        if (shakeDetector != null) shakeDetector.stop();
        if (offlineBanner != null) offlineBanner.stop();
        super.onPause();
    }

    // ============================================================
    // PERMISO RUNTIME: POST_NOTIFICATIONS
    // ============================================================

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    // ============================================================
    // BROADCAST: actualizacion del backend desde el Service
    // ============================================================

    private void handleEventUpdate(Intent intent) {
        // EXTRA_CREATED_AT_ISO solo viene cuando el evento es de actividad de
        // PUERTA (opened/closed/blocked/unblocked). Para light_on/light_off el
        // PollingService omite ese extra a proposito — la luz tiene su propia
        // metrica y no debe pisar este label. Si no hay ISO, dejamos el valor
        // que tenia y loadDailyMetrics() abajo lo refresca con el snapshot
        // autoritativo del server (last_door_event_at).
        String createdAtIso = intent.getStringExtra(PawGatePollingService.EXTRA_CREATED_AT_ISO);
        if (createdAtIso != null && lastActivityLabel != null) {
            String rel = HistorialMapper.relativeTimeFor(createdAtIso, System.currentTimeMillis());
            lastActivityLabel.setText(getString(R.string.dashboard_last_activity_template, rel));
        }
        renderDoorState();
        // Refrescar el badge de luz INMEDIATO usando el estado persistido
        // por DoorStateMachine (light_on/light_off ya actualizaron isLightOn).
        // Sin esperar al /metrics/today que puede tardar 100ms mas.
        renderLightBadge(PrefsHelper.isLightOn(this) ? "on" : "off");
        // Refrescar el contador de "Aperturas hoy" cuando hay un evento nuevo,
        // asi se actualiza en vivo (la mayoria de los broadcasts del Service son
        // de events no-door, pero cualquier door event nuevo podria ser un opened).
        loadDailyMetrics();
        // Si el broadcast es por un evento nuevo, lo mas probable es que el
        // backend tambien acabe de persistir una notif en pawgate_notifications,
        // asi que refrescamos el badge tambien.
        refreshUnreadBadge();
    }

    // ============================================================
    // RENDER DOOR STATE
    // ============================================================

    private void renderDoorState() {
        DoorStateMachine.DoorState state = DoorStateMachine.currentState(this);
        String direction = PrefsHelper.getCycleDirection(this);

        String doorLabel;
        switch (state) {
            case OPENING:
                if ("in".equals(direction)) {
                    doorLabel = getString(R.string.dashboard_door_opening_in);
                } else if ("out".equals(direction)) {
                    doorLabel = getString(R.string.dashboard_door_opening_out);
                } else {
                    doorLabel = getString(R.string.dashboard_door_opening);
                }
                break;
            case OPEN: {
                int sec = DoorStateMachine.secondsRemainingInCountdown(this);
                if ("in".equals(direction)) {
                    doorLabel = getString(R.string.dashboard_door_open_in_countdown, sec);
                } else if ("out".equals(direction)) {
                    doorLabel = getString(R.string.dashboard_door_open_out_countdown, sec);
                } else {
                    doorLabel = getString(R.string.dashboard_door_open_countdown, sec);
                }
                break;
            }
            case CLOSING:
                if ("in".equals(direction)) {
                    doorLabel = getString(R.string.dashboard_door_closing_in);
                } else if ("out".equals(direction)) {
                    doorLabel = getString(R.string.dashboard_door_closing_out);
                } else {
                    doorLabel = getString(R.string.dashboard_door_closing);
                }
                break;
            case BLOCKED:
                doorLabel = getString(R.string.dashboard_door_locked);
                break;
            case CALLING:
                doorLabel = getString(R.string.dashboard_door_calling_countdown,
                        DoorStateMachine.secondsRemainingInCountdown(this));
                break;
            case CALL_ENDING:
                doorLabel = getString(R.string.dashboard_door_ending_call);
                break;
            case IDLE:
            default:
                doorLabel = getString(R.string.dashboard_door_closed);
                break;
        }
        doorStatusLabel.setText(doorLabel);

        // Toggle Bloquear/Desbloquear
        if (state == DoorStateMachine.DoorState.BLOCKED) {
            actionBlock.setBackgroundResource(R.drawable.bg_button_secondary);
            actionBlockIcon.setImageResource(R.drawable.ic_lock_open);
            actionBlockIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
            actionBlockLabel.setText(R.string.action_unblock);
            actionBlockLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        } else {
            actionBlock.setBackgroundResource(R.drawable.bg_button_outline_danger);
            actionBlockIcon.setImageResource(R.drawable.ic_lock);
            actionBlockIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_block));
            actionBlockLabel.setText(R.string.action_block);
            actionBlockLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_block));
        }

        // Bloquear acciones Abrir/Llamar cuando hay un ciclo activo (cualquier
        // estado distinto de IDLE/BLOCKED). Asi el user no puede encadenar
        // 'Llamar -> Abrir' rapido y dejar la puerta en estado inconsistente.
        boolean busy = state != DoorStateMachine.DoorState.IDLE
                && state != DoorStateMachine.DoorState.BLOCKED;
        setQuickActionsBusy(busy);
    }

    /** Aplica un visual + funcional disabled a las cards Abrir y Llamar
     *  mientras la puerta este en un ciclo (opening/open/closing/calling/etc). */
    private void setQuickActionsBusy(boolean busy) {
        float alpha = busy ? 0.5f : 1.0f;
        if (actionOpen != null) {
            actionOpen.setAlpha(alpha);
            actionOpen.setClickable(!busy);
        }
        if (actionCall != null) {
            actionCall.setAlpha(alpha);
            actionCall.setClickable(!busy);
        }
    }

    // ============================================================
    // ACCIONES
    // ============================================================

    private void onActionOpenClick() {
        if (isBusyCycle()) return;
        if (PrefsHelper.isDoorBlocked(this)) {
            openControl(null);
            return;
        }
        OpenDirectionBottomSheet.show(getSupportFragmentManager(), direction -> {
            PrefsHelper.startCycle(this, PrefsHelper.CYCLE_OPEN_DOOR);
            openControl(direction);
        });
    }

    private void onActionCallClick() {
        if (isBusyCycle()) return;
        // En BLOCKED no llamamos. isBusyCycle excluia BLOCKED a proposito
        // (era para el caso 'estoy en un ciclo de apertura, no quiero llamar')
        // pero deja pasar BLOCKED. Aca cortamos explicito.
        if (DoorStateMachine.currentState(this) == DoorStateMachine.DoorState.BLOCKED) {
            Toast.makeText(this, R.string.dashboard_call_blocked_toast,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 1) UX optimista: arrancar el ciclo local YA para feedback inmediato.
        PrefsHelper.startCycle(this, PrefsHelper.CYCLE_CALL);

        // 2) Abrir ControlActivity (sigue mostrando el countdown del CALLING).
        openControl(null);

        // 3) Disparar el cmd al backend que publica al topic MQTT cmd/call.
        //    El firmware lo recibe y activa el buzzer 3s. Sin este sendCommand
        //    el ciclo local corria pero la mascota nunca era llamada — el
        //    bug que viste tocando "Llamar" desde el dashboard.
        deviceRepo.sendCommand(deviceId, DeviceRepository.CMD_CALL,
                java.util.Collections.emptyMap(),
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse>() {
                    @Override public void onSuccess(
                            com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse r) {
                        android.util.Log.i("DashboardActivity",
                                "cmd/call OK from dashboard button");
                        // Poll inmediato para que la confirmacion del firmware
                        // (eventos relacionados) lleguen rapido.
                        PawGatePollingService.requestPollNow(DashboardActivity.this);
                    }
                    @Override public void onError(String message) {
                        android.util.Log.w("DashboardActivity",
                                "cmd/call error: " + message);
                        // Revertir el ciclo local para no mostrar un fake call
                        // que el device nunca recibio.
                        PrefsHelper.clearCycle(DashboardActivity.this);
                        Toast.makeText(DashboardActivity.this, message,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Hay un ciclo de puerta o llamada en curso? */
    private boolean isBusyCycle() {
        DoorStateMachine.DoorState s = DoorStateMachine.currentState(this);
        return s != DoorStateMachine.DoorState.IDLE
                && s != DoorStateMachine.DoorState.BLOCKED;
    }

    private void openControl(String direction) {
        Intent i = new Intent(this, ControlActivity.class);
        if (direction != null) i.putExtra(ControlActivity.EXTRA_OPEN_DIRECTION, direction);
        startActivity(i);
    }

    // ============================================================
    // BLOQUEAR / DESBLOQUEAR (con awareness de horario natural)
    // ============================================================

    /**
     * Flujo del toggle:
     *   1) Refetch del lock_state + currently_in_horario al backend.
     *   2) Mostrar el dialog apropiado:
     *      - Block dentro de horario  -> dialog WARNING (override)
     *      - Block fuera de horario   -> dialog estandar
     *      - Unblock dentro de horario -> dialog estandar
     *      - Unblock fuera de horario -> dialog WARNING (override)
     *   3) Al confirmar, llamar al endpoint correspondiente (cmd o override).
     */
    private void onBlockOrUnblockClick() {
        if (toggleInFlight) return;
        boolean wantToBlock = !PrefsHelper.isDoorBlocked(this);
        toggleInFlight = true;
        deviceRepo.getDeviceState(deviceId, new ApiCallback<ScheduleDtos.DeviceStateResponse>() {
            @Override
            public void onSuccess(ScheduleDtos.DeviceStateResponse state) {
                toggleInFlight = false;
                boolean inHorario = state != null && state.currently_in_horario;
                if (wantToBlock) {
                    if (inHorario) showOverrideBlockDialog();
                    else showRegularBlockDialog();
                } else {
                    if (inHorario) showRegularUnblockDialog();
                    else showOverrideUnblockDialog();
                }
            }
            @Override
            public void onError(String message) {
                toggleInFlight = false;
                // Fallback: si no podemos consultar el state, asumimos que estamos
                // fuera de horario (caso mas comun) y mostramos dialog regular.
                if (wantToBlock) showRegularBlockDialog();
                else showRegularUnblockDialog();
            }
        });
    }

    // ---- BLOCK ----

    private void showRegularBlockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_block_dialog_title)
                .setMessage(R.string.dashboard_block_dialog_message)
                .setPositiveButton(R.string.control_block_dialog_confirm,
                        (d, w) -> executeBlock(false))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showOverrideBlockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.override_block_title)
                .setMessage(R.string.override_block_message)
                .setPositiveButton(R.string.override_block_confirm,
                        (d, w) -> executeBlock(true))
                .setNegativeButton(R.string.override_block_cancel, null)
                .show();
    }

    private void executeBlock(boolean useOverride) {
        ApiCallback<ScheduleDtos.OverrideUnblockResponse> cb =
                new ApiCallback<ScheduleDtos.OverrideUnblockResponse>() {
            @Override public void onSuccess(ScheduleDtos.OverrideUnblockResponse result) {
                PrefsHelper.setDoorBlocked(DashboardActivity.this, true);
                PrefsHelper.clearCycle(DashboardActivity.this);
                showToast(useOverride
                        ? R.string.override_block_toast_ok
                        : R.string.toast_action_block);
                renderDoorState();
            }
            @Override public void onError(String message) {
                Toast.makeText(DashboardActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
        if (useOverride) deviceRepo.overrideBlock(deviceId, cb);
        else deviceRepo.sendCommand(deviceId, DeviceRepository.CMD_BLOCK,
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse>() {
            @Override public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse r) {
                PrefsHelper.setDoorBlocked(DashboardActivity.this, true);
                PrefsHelper.clearCycle(DashboardActivity.this);
                showToast(R.string.toast_action_block);
                renderDoorState();
                PawGatePollingService.requestPollNow(DashboardActivity.this);
            }
            @Override public void onError(String message) {
                Toast.makeText(DashboardActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- UNBLOCK ----

    private void showRegularUnblockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_unblock_dialog_title)
                .setMessage(R.string.dashboard_unblock_dialog_message)
                .setPositiveButton(R.string.action_unblock,
                        (d, w) -> executeUnblock(false))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showOverrideUnblockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.override_unblock_title)
                .setMessage(R.string.override_unblock_message)
                .setPositiveButton(R.string.override_unblock_confirm,
                        (d, w) -> executeUnblock(true))
                .setNegativeButton(R.string.override_unblock_cancel, null)
                .show();
    }

    private void executeUnblock(boolean useOverride) {
        ApiCallback<ScheduleDtos.OverrideUnblockResponse> overrideCb =
                new ApiCallback<ScheduleDtos.OverrideUnblockResponse>() {
            @Override public void onSuccess(ScheduleDtos.OverrideUnblockResponse result) {
                PrefsHelper.setDoorBlocked(DashboardActivity.this, false);
                showToast(R.string.override_unblock_toast_ok);
                renderDoorState();
            }
            @Override public void onError(String message) {
                Toast.makeText(DashboardActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
        if (useOverride) {
            deviceRepo.overrideUnblock(deviceId, overrideCb);
        } else {
            deviceRepo.sendCommand(deviceId, DeviceRepository.CMD_UNBLOCK,
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse>() {
                @Override public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse r) {
                    PrefsHelper.setDoorBlocked(DashboardActivity.this, false);
                    showToast(R.string.toast_action_unblock);
                    renderDoorState();
                    PawGatePollingService.requestPollNow(DashboardActivity.this);
                }
                @Override public void onError(String message) {
                    Toast.makeText(DashboardActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
