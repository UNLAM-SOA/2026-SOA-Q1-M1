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

        // Greeting: priorizamos el nombre del user (extraido del JWT al login).
        // Si todavia no fue persistido (ej. login viejo pre-fix, o auto-login
        // con token vigente), intentamos extraerlo del idToken almacenado AHORA.
        String displayName = PrefsHelper.getUserName(this);
        if (displayName == null || displayName.isEmpty()) {
            String idToken = PrefsHelper.getIdToken(this);
            if (idToken != null) {
                displayName = com.unlam.pawgate.api.JwtUtils.extractName(idToken);
                if (displayName != null) PrefsHelper.setUserName(this, displayName);
            }
        }
        if (displayName == null || displayName.isEmpty()) {
            // Ultimo recurso: el email del Intent extra.
            displayName = getIntent().getStringExtra(LoginActivity.EXTRA_USER);
        }
        if (displayName != null) {
            TextView greeting = findViewById(R.id.dashboard_greeting);
            greeting.setText(getString(R.string.dashboard_greeting_template, displayName));
        }

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
        refreshTickRunnable.run();
        statePollRunnable.run();
        loadDailyMetrics();

        IntentFilter filter = new IntentFilter(PawGatePollingService.ACTION_EVENT_UPDATE);
        ContextCompat.registerReceiver(
                this,
                eventUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

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

    /** Callback del ShakeDetector. Dispara cmd/call si no estamos en
     *  modo bloqueado (sino el device no va a responder y confunde). */
    private void onShakeDetected() {
        android.util.Log.i("DashboardActivity", "shake detected -> sending cmd/call");
        deviceRepo.sendCommand(deviceId, DeviceRepository.CMD_CALL,
                java.util.Collections.emptyMap(),
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse>() {
                    @Override public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse r) {
                        android.widget.Toast.makeText(DashboardActivity.this,
                                R.string.shake_to_call_triggered,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(String message) {
                        android.util.Log.w("DashboardActivity", "shake call error: " + message);
                    }
                });
    }

    // ============================================================
    // Metricas del dia (aperturas hoy + ultima actividad)
    // ============================================================

    /** Fetch a /history con ventana desde el comienzo del dia para:
     *  - contar eventos opened (aperturas hoy)
     *  - tomar el evento mas reciente y mostrar "Ultima actividad: hace X". */
    private void loadDailyMetrics() {
        // Endpoint dedicado /metrics/today: el backend itera todas las paginas
        // de DDB y devuelve el conteo de aperturas + el ultimo door event.
        // Evita el bug previo en que contabamos solo los primeros 50 events de
        // /history (que solian ser todos sensors, dando opens=0).
        deviceRepo.metricsToday(deviceId,
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.MetricsTodayResponse>() {
            @Override
            public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.MetricsTodayResponse result) {
                int opens = result != null ? result.openings_today : 0;
                String lastIso = result != null ? result.last_door_event_at : null;
                android.util.Log.d("DashboardMetrics",
                        "metricsToday: opens=" + opens + " lastIso=" + lastIso);
                if (openingsCountLabel != null) {
                    openingsCountLabel.setText(String.valueOf(opens));
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

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTickRunnable);
        handler.removeCallbacks(statePollRunnable);
        try {
            unregisterReceiver(eventUpdateReceiver);
        } catch (IllegalArgumentException ignored) {
            // defensivo: race conditions en lifecycle
        }
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
        String createdAtIso = intent.getStringExtra(PawGatePollingService.EXTRA_CREATED_AT_ISO);
        if (createdAtIso != null && lastActivityLabel != null) {
            String rel = HistorialMapper.relativeTimeFor(createdAtIso, System.currentTimeMillis());
            lastActivityLabel.setText(getString(R.string.dashboard_last_activity_template, rel));
        }
        renderDoorState();
        // Refrescar el contador de "Aperturas hoy" cuando hay un evento nuevo,
        // asi se actualiza en vivo (la mayoria de los broadcasts del Service son
        // de events no-door, pero cualquier door event nuevo podria ser un opened).
        loadDailyMetrics();
    }

    // ============================================================
    // RENDER DOOR STATE
    // ============================================================

    private void renderDoorState() {
        DoorStateMachine.DoorState state = DoorStateMachine.currentState(this);

        String doorLabel;
        switch (state) {
            case OPENING:
                doorLabel = getString(R.string.dashboard_door_opening);
                break;
            case OPEN:
                doorLabel = getString(R.string.dashboard_door_open_countdown,
                        DoorStateMachine.secondsRemainingInCountdown(this));
                break;
            case CLOSING:
                doorLabel = getString(R.string.dashboard_door_closing);
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
        PrefsHelper.startCycle(this, PrefsHelper.CYCLE_CALL);
        openControl(null);
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
