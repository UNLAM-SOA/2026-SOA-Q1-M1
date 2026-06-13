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
    private LinearLayout actionBlock;
    private ImageView actionBlockIcon;
    private TextView actionBlockLabel;
    private TextView doorStatusLabel;
    private TextView lastActivityLabel;
    private DeviceRepository deviceRepo;
    private String deviceId;
    private boolean toggleInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        actionBlock = findViewById(R.id.action_block);
        actionBlockIcon = findViewById(R.id.action_block_icon);
        actionBlockLabel = findViewById(R.id.action_block_label);
        doorStatusLabel = findViewById(R.id.dashboard_door_status);
        lastActivityLabel = findViewById(R.id.dashboard_last_activity);

        deviceRepo = new DeviceRepository(this);
        deviceId = getString(R.string.default_device_id);

        // Greeting
        String user = getIntent().getStringExtra(LoginActivity.EXTRA_USER);
        if (user != null) {
            TextView greeting = findViewById(R.id.dashboard_greeting);
            greeting.setText(getString(R.string.dashboard_greeting_template, user));
        }

        ensureNotificationPermission();

        findViewById(R.id.dashboard_notification).setOnClickListener(
                v -> startActivity(new Intent(this, NotificacionesActivity.class)));
        findViewById(R.id.action_open).setOnClickListener(v -> onActionOpenClick());
        actionBlock.setOnClickListener(v -> onBlockOrUnblockClick());
        findViewById(R.id.action_call).setOnClickListener(v -> onActionCallClick());
        findViewById(R.id.action_schedules).setOnClickListener(
                v -> startActivity(new Intent(this, HorariosActivity.class)));

        BottomNavHelper.bind(this, R.id.nav_inicio);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTickRunnable.run();
        // Poll directo a /state cada 3s mientras el Dashboard sea visible.
        // Independiente del PawGatePollingService.
        statePollRunnable.run();

        IntentFilter filter = new IntentFilter(PawGatePollingService.ACTION_EVENT_UPDATE);
        ContextCompat.registerReceiver(
                this,
                eventUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
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
        super.onPause();
    }

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
        // El Service ya escribio el flag BLOQUEADO en SharedPreferences si correspondia.
        renderDoorState();
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
    }

    // ============================================================
    // ACCIONES
    // ============================================================

    private void onActionOpenClick() {
        if (PrefsHelper.isDoorBlocked(this)) {
            // Bloqueada: NO iniciamos el ciclo, solo redirigimos a Control
            // (donde el user vera el estado BLOQUEADO y puede desbloquear).
            openControl();
            return;
        }
        // No bloqueada: arrancar ciclo de apertura y redirigir a Control.
        PrefsHelper.startCycle(this, PrefsHelper.CYCLE_OPEN_DOOR);
        openControl();
    }

    private void onActionCallClick() {
        // Por requerimiento de UX: la llamada se ejecuta directo y redirige a Control,
        // independiente del estado actual de la puerta.
        PrefsHelper.startCycle(this, PrefsHelper.CYCLE_CALL);
        openControl();
    }

    private void openControl() {
        startActivity(new Intent(this, ControlActivity.class));
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
