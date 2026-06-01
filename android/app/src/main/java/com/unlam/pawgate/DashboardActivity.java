package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dashboard - pantalla principal.
 *
 * Lee el estado de la puerta desde DoorStateMachine (fuente de verdad:
 * SharedPreferences + clock) y lo muestra en:
 *   - La card "Puerta · X" (label que refleja TODOS los estados).
 *   - El toggle Bloquear/Desbloquear con borde rojo o gris.
 *
 * Tick de 1s en onResume mantiene la card sincronizada mientras el user
 * esta en Dashboard (la puerta puede pasar de ABRIENDO -> ABIERTA mientras
 * el user mira el Dashboard).
 *
 * Acciones:
 *   - dashboard_door_card: NO clickable (solo informacion).
 *   - action_open  -> si BLOQUEADA, solo redirige a Control. Sino, inicia
 *                     el ciclo OPEN_DOOR y redirige.
 *   - action_call  -> inicia el ciclo CALL y redirige.
 *   - action_block -> dialog Bloquear/Desbloquear (toggle persistente).
 *   - action_schedules -> abre HorariosActivity.
 *   - bell -> abre NotificacionesActivity.
 */
public class DashboardActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTickRunnable = new Runnable() {
        @Override public void run() {
            renderDoorState();
            // Solo re-tickear si estamos en un estado dinamico (cycle activo).
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        actionBlock = findViewById(R.id.action_block);
        actionBlockIcon = findViewById(R.id.action_block_icon);
        actionBlockLabel = findViewById(R.id.action_block_label);
        doorStatusLabel = findViewById(R.id.dashboard_door_status);

        // Greeting
        String user = getIntent().getStringExtra(LoginActivity.EXTRA_USER);
        if (user != null) {
            TextView greeting = findViewById(R.id.dashboard_greeting);
            greeting.setText(getString(R.string.dashboard_greeting_template, user));
        }

        // dashboard_door_card: SIN listener (no clickable, solo informativa).
        // (Dejamos el ripple visual del XML por estetica, pero no responde a tap.)

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
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTickRunnable);
        super.onPause();
    }

    // ============================================================
    // RENDER DOOR STATE
    // ============================================================

    private void renderDoorState() {
        DoorStateMachine.DoorState state = DoorStateMachine.currentState(this);

        // Label de la card "Puerta · X"
        int doorStringRes;
        switch (state) {
            case OPENING:     doorStringRes = R.string.dashboard_door_opening; break;
            case OPEN:        doorStringRes = R.string.dashboard_door_open; break;
            case CLOSING:     doorStringRes = R.string.dashboard_door_closing; break;
            case BLOCKED:     doorStringRes = R.string.dashboard_door_locked; break;
            case CALLING:     doorStringRes = R.string.dashboard_door_calling; break;
            case CALL_ENDING: doorStringRes = R.string.dashboard_door_ending_call; break;
            case IDLE:
            default:          doorStringRes = R.string.dashboard_door_closed; break;
        }
        doorStatusLabel.setText(doorStringRes);

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
