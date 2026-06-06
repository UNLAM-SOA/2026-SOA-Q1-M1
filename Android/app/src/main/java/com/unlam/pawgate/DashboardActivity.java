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

        actionBlock = findViewById(R.id.action_block);
        actionBlockIcon = findViewById(R.id.action_block_icon);
        actionBlockLabel = findViewById(R.id.action_block_label);
        doorStatusLabel = findViewById(R.id.dashboard_door_status);
        lastActivityLabel = findViewById(R.id.dashboard_last_activity);

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

        // RECEIVER_NOT_EXPORTED: solo aceptamos broadcasts del propio Service.
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
        try {
            unregisterReceiver(eventUpdateReceiver);
        } catch (IllegalArgumentException ignored) {
            // defensivo: race conditions en lifecycle
        }
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
    // BLOQUEAR / DESBLOQUEAR
    // ============================================================

    private void onBlockOrUnblockClick() {
        if (PrefsHelper.isDoorBlocked(this)) {
            showUnblockDialog();
        } else {
            showBlockDialog();
        }
    }

    private void showBlockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_block_dialog_title)
                .setMessage(R.string.dashboard_block_dialog_message)
                .setPositiveButton(R.string.control_block_dialog_confirm, (dialog, which) -> {
                    PrefsHelper.setDoorBlocked(this, true);
                    PrefsHelper.clearCycle(this); // cancela cualquier ciclo en curso
                    showToast(R.string.toast_action_block);
                    Intent intent = new Intent(this, ControlActivity.class);
                    intent.putExtra(ControlActivity.EXTRA_START_BLOCKED, true);
                    startActivity(intent);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showUnblockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dashboard_unblock_dialog_title)
                .setMessage(R.string.dashboard_unblock_dialog_message)
                .setPositiveButton(R.string.action_unblock, (dialog, which) -> {
                    PrefsHelper.setDoorBlocked(this, false);
                    showToast(R.string.toast_action_unblock);
                    renderDoorState();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
