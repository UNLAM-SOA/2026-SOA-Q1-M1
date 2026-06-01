package com.unlam.pawgate;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Control de puerta - pantalla con 5 estados visuales:
 *
 *   IDLE     -> default, puerta cerrada, listo para abrir.
 *   OPENING  -> W06a, abriendo (transient, dura 2s antes de pasar a OPEN).
 *   OPEN     -> W06b, abierta, se auto-cierra en 5s.
 *   BLOCKED  -> W06c, modo seguridad activo (hasta desbloquear manualmente).
 *   CALLING  -> W06d, buzzer activo (dura 3s antes de volver a IDLE).
 *
 * El estado se cambia via transitionTo(state) que dispara render(state).
 * Los timers de transicion automatica usan Handler.postDelayed.
 *
 * En onDestroy() limpiamos los callbacks pendientes para no leakear la Activity.
 */
public class ControlActivity extends AppCompatActivity {

    private enum ControlState {
        IDLE, OPENING, OPEN, BLOCKED, CALLING
    }

    // Duraciones de las transiciones automaticas (ms)
    private static final long OPENING_DURATION_MS = 2_000L;
    private static final long OPEN_DURATION_MS = 5_000L;
    private static final long CALLING_DURATION_MS = 3_000L;

    // Estado actual + handler para transiciones temporizadas
    private ControlState currentState = ControlState.IDLE;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Refs a Views (cacheadas en bindViews() para no llamar findViewById en cada render)
    private TextView title;
    private LinearLayout statusPill;
    private View statusDot;
    private TextView statusText;
    private LinearLayout bigBtn;
    private ImageView bigBtnIcon;
    private TextView bigBtnLabel;
    private TextView hint;
    private TextView btnSecondary;
    private LinearLayout cardBlock;
    private TextView cardBlockSubtitle;
    private LinearLayout cardCall;
    private TextView cardCallSubtitle;
    private ImageView infoIcon;
    private TextView infoTitle;
    private TextView infoSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);
        BottomNavHelper.markActive(this, R.id.nav_puerta_icon, R.id.nav_puerta_label);

        bindViews();

        // Listeners de la pantalla
        bigBtn.setOnClickListener(v -> onBigBtnClick());
        cardBlock.setOnClickListener(v -> onBlockCardClick());
        cardCall.setOnClickListener(v -> onCallCardClick());
        btnSecondary.setOnClickListener(v -> onSecondaryBtnClick());

        // Listeners del BottomNav
        findViewById(R.id.nav_inicio).setOnClickListener(v -> finish());
        findViewById(R.id.nav_historial).setOnClickListener(v -> showToast(R.string.toast_coming_soon));
        findViewById(R.id.nav_ajustes).setOnClickListener(v -> showToast(R.string.toast_coming_soon));

        // Render inicial (IDLE)
        render(currentState);
    }

    @Override
    protected void onDestroy() {
        // Cancelar callbacks pendientes para evitar leak/crash al destruir la Activity
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ============================================================
    // VIEW BINDING
    // ============================================================

    private void bindViews() {
        title = findViewById(R.id.control_title);
        statusPill = findViewById(R.id.control_status_pill);
        statusDot = findViewById(R.id.control_status_dot);
        statusText = findViewById(R.id.control_status_text);
        bigBtn = findViewById(R.id.control_btn_open);
        bigBtnIcon = findViewById(R.id.control_btn_icon);
        bigBtnLabel = findViewById(R.id.control_btn_label);
        hint = findViewById(R.id.control_btn_hint);
        btnSecondary = findViewById(R.id.control_btn_secondary);
        cardBlock = findViewById(R.id.control_card_block);
        cardBlockSubtitle = findViewById(R.id.control_card_block_subtitle);
        cardCall = findViewById(R.id.control_card_call);
        cardCallSubtitle = findViewById(R.id.control_card_call_subtitle);
        infoIcon = findViewById(R.id.control_info_icon);
        infoTitle = findViewById(R.id.control_info_title);
        infoSubtitle = findViewById(R.id.control_info_subtitle);
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private void onBigBtnClick() {
        switch (currentState) {
            case IDLE:
                // IDLE -> OPENING -> OPEN -> IDLE (ciclo completo simulado)
                transitionTo(ControlState.OPENING);
                handler.postDelayed(() -> transitionTo(ControlState.OPEN), OPENING_DURATION_MS);
                handler.postDelayed(() -> transitionTo(ControlState.IDLE), OPENING_DURATION_MS + OPEN_DURATION_MS);
                break;
            case OPENING:
            case OPEN:
                // Cancelar ciclo actual y volver a IDLE
                handler.removeCallbacksAndMessages(null);
                transitionTo(ControlState.IDLE);
                break;
            case BLOCKED:
            case CALLING:
                // No se puede abrir mientras esta bloqueado/llamando
                break;
        }
    }

    private void onBlockCardClick() {
        if (currentState == ControlState.BLOCKED) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.control_block_dialog_title)
                .setMessage(R.string.control_block_dialog_message)
                .setPositiveButton(R.string.control_block_dialog_confirm,
                        (dialog, which) -> {
                            handler.removeCallbacksAndMessages(null);
                            transitionTo(ControlState.BLOCKED);
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void onCallCardClick() {
        if (currentState == ControlState.CALLING || currentState == ControlState.BLOCKED) {
            return;
        }
        handler.removeCallbacksAndMessages(null);
        transitionTo(ControlState.CALLING);
        handler.postDelayed(() -> transitionTo(ControlState.IDLE), CALLING_DURATION_MS);
    }

    private void onSecondaryBtnClick() {
        // El boton secundario solo se muestra en BLOCKED ("Desbloquear")
        // o en CALLING ("Detener llamada"). En ambos casos vuelve a IDLE.
        if (currentState == ControlState.BLOCKED || currentState == ControlState.CALLING) {
            handler.removeCallbacksAndMessages(null);
            transitionTo(ControlState.IDLE);
        }
    }

    // ============================================================
    // STATE MACHINE
    // ============================================================

    private void transitionTo(ControlState newState) {
        currentState = newState;
        render(newState);
    }

    private void render(ControlState state) {
        // Limpiar animacion previa (en caso de venir desde OPENING con spinner girando)
        bigBtnIcon.clearAnimation();

        switch (state) {
            case IDLE:    renderIdle();    break;
            case OPENING: renderOpening(); break;
            case OPEN:    renderOpen();    break;
            case BLOCKED: renderBlocked(); break;
            case CALLING: renderCalling(); break;
        }
    }

    private void renderIdle() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.text_muted);
        statusText.setText(R.string.control_status_live);
        statusText.setTextColor(color(R.color.text_muted));

        bigBtn.setBackgroundResource(R.drawable.bg_button_primary);
        bigBtnIcon.setImageResource(R.drawable.ic_door_open);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_open);

        hint.setText(R.string.control_btn_open_hint);
        btnSecondary.setVisibility(View.GONE);

        cardBlock.setBackgroundResource(R.drawable.bg_card);
        cardBlockSubtitle.setText(R.string.control_card_block_subtitle);
        cardBlockSubtitle.setTextColor(color(R.color.text_muted));

        cardCall.setBackgroundResource(R.drawable.bg_card);
        cardCallSubtitle.setText(R.string.control_card_call_subtitle);
        cardCallSubtitle.setTextColor(color(R.color.text_muted));

        infoIcon.setImageResource(R.drawable.ic_radio);
        infoIcon.setColorFilter(color(R.color.text_primary));
        infoTitle.setText(R.string.control_sensor_title);
        infoSubtitle.setText(R.string.control_sensor_subtitle);
    }

    private void renderOpening() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.accent_neon);
        statusText.setText(R.string.control_status_opening);
        statusText.setTextColor(color(R.color.accent_neon));

        bigBtn.setBackgroundResource(R.drawable.bg_button_primary);
        bigBtnIcon.setImageResource(R.drawable.ic_loader_circle);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        startSpinningIcon();
        bigBtnLabel.setText(R.string.control_btn_opening);

        hint.setText(R.string.control_hint_opening);
        btnSecondary.setVisibility(View.GONE);

        cardBlock.setBackgroundResource(R.drawable.bg_card);
        cardBlockSubtitle.setText(R.string.control_card_block_subtitle);
        cardBlockSubtitle.setTextColor(color(R.color.text_muted));

        cardCall.setBackgroundResource(R.drawable.bg_card);
        cardCallSubtitle.setText(R.string.control_card_call_subtitle);
        cardCallSubtitle.setTextColor(color(R.color.text_muted));

        infoIcon.setImageResource(R.drawable.ic_radio);
        infoIcon.setColorFilter(color(R.color.text_primary));
        infoTitle.setText(R.string.control_sensor_title);
        infoSubtitle.setText(R.string.control_sensor_subtitle);
    }

    private void renderOpen() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.accent_success);
        statusText.setText(getString(R.string.control_status_open, OPEN_DURATION_MS / 1000));
        statusText.setTextColor(color(R.color.accent_success));

        bigBtn.setBackgroundResource(R.drawable.bg_button_open_dark);
        bigBtnIcon.setImageResource(R.drawable.ic_check);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_open_state);

        hint.setText(getString(R.string.control_hint_open, OPEN_DURATION_MS / 1000));
        btnSecondary.setVisibility(View.GONE);

        cardBlock.setBackgroundResource(R.drawable.bg_card);
        cardBlockSubtitle.setText(R.string.control_card_block_subtitle);
        cardBlockSubtitle.setTextColor(color(R.color.text_muted));

        cardCall.setBackgroundResource(R.drawable.bg_card);
        cardCallSubtitle.setText(R.string.control_card_call_subtitle);
        cardCallSubtitle.setTextColor(color(R.color.text_muted));

        infoIcon.setImageResource(R.drawable.ic_check_check);
        infoIcon.setColorFilter(color(R.color.accent_success));
        infoTitle.setText(R.string.control_info_last_open_title);
        infoSubtitle.setText(R.string.control_info_last_open_subtitle);
    }

    private void renderBlocked() {
        title.setText(R.string.control_title_blocked);

        statusPill.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill_outlined_danger));
        tintDot(R.color.accent_block);
        statusText.setText(R.string.control_status_blocked);
        statusText.setTextColor(color(R.color.accent_block));

        bigBtn.setBackgroundResource(R.drawable.bg_button_blocked);
        bigBtnIcon.setImageResource(R.drawable.ic_lock);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_blocked);

        hint.setText(R.string.control_hint_blocked);

        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(R.string.control_secondary_unblock);

        cardBlock.setBackgroundResource(R.drawable.bg_card_active_danger);
        cardBlockSubtitle.setText(R.string.control_card_active);
        cardBlockSubtitle.setTextColor(color(R.color.accent_block));

        cardCall.setBackgroundResource(R.drawable.bg_card);
        cardCallSubtitle.setText(R.string.control_card_call_subtitle);
        cardCallSubtitle.setTextColor(color(R.color.text_muted));

        infoIcon.setImageResource(R.drawable.ic_shield_check);
        infoIcon.setColorFilter(color(R.color.accent_block));
        infoTitle.setText(R.string.control_info_blocked_title);
        infoSubtitle.setText(R.string.control_info_blocked_subtitle);
    }

    private void renderCalling() {
        title.setText(R.string.control_title_calling);

        statusPill.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill_outlined_info));
        tintDot(R.color.accent_cyan);
        statusText.setText(getString(R.string.control_status_calling, CALLING_DURATION_MS / 1000));
        statusText.setTextColor(color(R.color.accent_cyan));

        bigBtn.setBackgroundResource(R.drawable.bg_button_calling);
        bigBtnIcon.setImageResource(R.drawable.ic_megaphone);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_calling);

        hint.setText(getString(R.string.control_hint_calling, CALLING_DURATION_MS / 1000));

        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(R.string.control_secondary_stop_call);

        cardBlock.setBackgroundResource(R.drawable.bg_card);
        cardBlockSubtitle.setText(R.string.control_card_block_subtitle);
        cardBlockSubtitle.setTextColor(color(R.color.text_muted));

        cardCall.setBackgroundResource(R.drawable.bg_card_active_info);
        cardCallSubtitle.setText(R.string.control_card_active);
        cardCallSubtitle.setTextColor(color(R.color.accent_cyan));

        infoIcon.setImageResource(R.drawable.ic_bell);
        infoIcon.setColorFilter(color(R.color.accent_cyan));
        infoTitle.setText(R.string.control_info_calling_title);
        infoSubtitle.setText(getString(R.string.control_info_calling_subtitle, CALLING_DURATION_MS / 1000));
    }

    // ============================================================
    // HELPERS
    // ============================================================

    /** Tinta el dot del pill con el color indicado (usa background tint). */
    private void tintDot(int colorRes) {
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color(colorRes)));
    }

    /** Aplica una RotateAnimation infinita (1s por vuelta) al icono del bigBtn. */
    private void startSpinningIcon() {
        RotateAnimation rotate = new RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
        );
        rotate.setDuration(1000);
        rotate.setRepeatCount(Animation.INFINITE);
        rotate.setInterpolator(new LinearInterpolator());
        bigBtnIcon.startAnimation(rotate);
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
