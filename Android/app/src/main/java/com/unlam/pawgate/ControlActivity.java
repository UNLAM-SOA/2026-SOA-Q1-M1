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
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

/**
 * Control de puerta - render del estado que computa DoorStateMachine.
 *
 * La Activity no mantiene el estado in-memory. En cada onResume y cada tick
 * (1s) consulta a DoorStateMachine.currentState(ctx) y rendera. Asi el
 * estado se mantiene aunque el user navegue Dashboard <-> Control: la fuente
 * de verdad es SharedPreferences + clock.
 *
 * Reglas de interaccion (por requerimiento del producto):
 *
 *   BigBtn (ABRIR/ABRIENDO/ABIERTA/CERRANDO/etc):
 *     - IDLE      -> arranca ciclo OPEN_DOOR
 *     - OPENING   -> cancela ciclo (tap big btn = mismo efecto que tap Cancelar)
 *     - OPEN      -> NO clickable
 *     - CLOSING   -> NO clickable
 *     - BLOCKED   -> no hace nada (visualmente no clickable)
 *     - CALLING   -> no hace nada
 *     - CALL_ENDING -> no hace nada
 *
 *   Cards Bloquear y Llamar:
 *     - Disabled (no clickable, dim al 50%) en OPENING / OPEN / CLOSING
 *       (durante el ciclo de apertura no se puede ni bloquear ni llamar)
 *     - Habilitadas en el resto (con la logica interna de cada listener)
 *
 *   Boton secundario abajo:
 *     - OPENING        -> "Cancelar"
 *     - BLOCKED        -> "Desbloquear"
 *     - CALLING / CALL_ENDING -> "Detener llamada"
 *     - resto          -> oculto
 *
 *   Info strip abajo:
 *     - BLOCKED        -> "Modo seguridad activado"
 *     - CALLING/CALL_ENDING -> "Tu mascota fue notificada"
 *     - resto          -> "Ultima apertura: ahora · Manual · desde Control"
 *       (reemplaza a la info del sensor de proximidad)
 */
public class ControlActivity extends AppCompatActivity {

    /** Si el Dashboard lanza Control con este extra=true, fuerza estado BLOCKED al arrancar. */
    public static final String EXTRA_START_BLOCKED = "start_blocked";

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Tick que re-renderiza el estado actual cada 1s.
     *  Se autosuspende cuando llegamos a un estado terminal (IDLE / BLOCKED). */
    private final Runnable refreshTickRunnable = new Runnable() {
        @Override public void run() {
            DoorStateMachine.DoorState now = currentState();
            render(now);
            if (now != DoorStateMachine.DoorState.IDLE
                    && now != DoorStateMachine.DoorState.BLOCKED) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    // ===== Backend =====
    private DeviceRepository deviceRepo;
    private String deviceId;

    // ===== Views cacheadas =====
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
    private ImageView cardBlockArrow;
    private LinearLayout cardCall;
    private TextView cardCallSubtitle;
    private ImageView cardCallArrow;
    private ImageView infoIcon;
    private TextView infoTitle;
    private TextView infoSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        bindViews();

        // Backend hookup
        this.deviceRepo = new DeviceRepository(this);
        this.deviceId = getString(R.string.default_device_id);

        // Listeners
        bigBtn.setOnClickListener(v -> onBigBtnClick());
        cardBlock.setOnClickListener(v -> onBlockCardClick());
        cardCall.setOnClickListener(v -> onCallCardClick());
        btnSecondary.setOnClickListener(v -> onSecondaryBtnClick());
        findViewById(R.id.control_back).setOnClickListener(v -> finish());

        BottomNavHelper.bind(this, R.id.nav_puerta);

        // Si el Dashboard pidio arrancar BLOQUEADO, forzamos el flag antes del render.
        if (getIntent().getBooleanExtra(EXTRA_START_BLOCKED, false)) {
            PrefsHelper.setDoorBlocked(this, true);
            PrefsHelper.clearCycle(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Arranca el tick. La primera ejecucion es inmediata (renderiza el estado actual)
        // y se reagenda solo si el estado no es terminal.
        refreshTickRunnable.run();
    }

    @Override
    protected void onPause() {
        // Pausamos el tick: ya no hace falta refrescar la UI cuando la Activity no es visible.
        // El estado de la puerta sigue vivo en SharedPreferences; cuando volvamos lo re-leemos.
        handler.removeCallbacks(refreshTickRunnable);
        super.onPause();
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
        cardBlockArrow = findViewById(R.id.control_card_block_arrow);
        cardCall = findViewById(R.id.control_card_call);
        cardCallSubtitle = findViewById(R.id.control_card_call_subtitle);
        cardCallArrow = findViewById(R.id.control_card_call_arrow);
        infoIcon = findViewById(R.id.control_info_icon);
        infoTitle = findViewById(R.id.control_info_title);
        infoSubtitle = findViewById(R.id.control_info_subtitle);
    }

    private DoorStateMachine.DoorState currentState() {
        return DoorStateMachine.currentState(this);
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private void onBigBtnClick() {
        DoorStateMachine.DoorState s = currentState();
        if (s == DoorStateMachine.DoorState.IDLE) {
            // 1) Feedback inmediato: arrancamos ciclo local (UI optimista)
            PrefsHelper.startCycle(this, PrefsHelper.CYCLE_OPEN_DOOR);
            refreshTickRunnable.run();
            // 2) Disparamos el POST real al backend en paralelo
            dispatchCommand(DeviceRepository.CMD_OPEN);
        } else if (s == DoorStateMachine.DoorState.OPENING) {
            // Cancelar el ciclo en curso (solo local - el backend no tiene "cancelar")
            PrefsHelper.clearCycle(this);
            refreshTickRunnable.run();
        }
        // En OPEN, CLOSING, BLOCKED, CALLING, CALL_ENDING -> no hace nada
    }

    private void onBlockCardClick() {
        DoorStateMachine.DoorState s = currentState();
        // Si estamos en un ciclo de apertura, las cards estan disabled
        // (igualmente esto es safety net por si el setClickable falla).
        if (s == DoorStateMachine.DoorState.OPENING
                || s == DoorStateMachine.DoorState.OPEN
                || s == DoorStateMachine.DoorState.CLOSING
                || s == DoorStateMachine.DoorState.BLOCKED) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.control_block_dialog_title)
                .setMessage(R.string.control_block_dialog_message)
                .setPositiveButton(R.string.control_block_dialog_confirm, (dialog, which) -> {
                    PrefsHelper.setDoorBlocked(this, true);
                    PrefsHelper.clearCycle(this);
                    refreshTickRunnable.run();
                    dispatchCommand(DeviceRepository.CMD_BLOCK);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void onCallCardClick() {
        DoorStateMachine.DoorState s = currentState();
        if (s == DoorStateMachine.DoorState.OPENING
                || s == DoorStateMachine.DoorState.OPEN
                || s == DoorStateMachine.DoorState.CLOSING
                || s == DoorStateMachine.DoorState.BLOCKED
                || s == DoorStateMachine.DoorState.CALLING
                || s == DoorStateMachine.DoorState.CALL_ENDING) {
            return;
        }
        PrefsHelper.startCycle(this, PrefsHelper.CYCLE_CALL);
        refreshTickRunnable.run();
        dispatchCommand(DeviceRepository.CMD_CALL);
    }

    private void onSecondaryBtnClick() {
        DoorStateMachine.DoorState s = currentState();
        switch (s) {
            case BLOCKED:
                // Desbloquear: local + POST unblock
                PrefsHelper.setDoorBlocked(this, false);
                PrefsHelper.clearCycle(this);
                refreshTickRunnable.run();
                dispatchCommand(DeviceRepository.CMD_UNBLOCK);
                break;
            case OPENING:
            case CALLING:
            case CALL_ENDING:
                // Cancelacion local. El backend no tiene "cancelar" - el simulador
                // termina su ciclo igual. En una version siguiente podriamos mandar
                // un cmd "stop" si el contrato MQTT lo soporta.
                PrefsHelper.clearCycle(this);
                refreshTickRunnable.run();
                break;
            default:
                // No deberia llegar (el boton solo es visible en los anteriores)
                break;
        }
    }

    // ============================================================
    // BACKEND DISPATCH
    // ============================================================

    /**
     * Manda el comando al backend. UX optimista: la UI ya cambio antes de
     * llamar este metodo (startCycle local). Si el POST falla, solo toast,
     * NO revertimos el estado local (el polling de /history terminara
     * conciliando si el simulador realmente no recibio el cmd).
     */
    private void dispatchCommand(String cmd) {
        deviceRepo.sendCommand(deviceId, cmd, new ApiCallback<DeviceDtos.CommandResponse>() {
            @Override
            public void onSuccess(DeviceDtos.CommandResponse result) {
                // Silencio en exito - el feedback visual ya lo dio el ciclo local.
                // Si quisieramos log: Log.d(TAG, "cmd queued: " + result.topic);
            }
            @Override
            public void onError(String message) {
                Toast.makeText(ControlActivity.this,
                        "Error al enviar comando: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    // ============================================================
    // RENDER
    // ============================================================

    private void render(DoorStateMachine.DoorState state) {
        // Limpia animacion previa del icono (spinner).
        bigBtnIcon.clearAnimation();

        switch (state) {
            case IDLE:        renderIdle();       break;
            case OPENING:     renderOpening();    break;
            case OPEN:        renderOpen();       break;
            case CLOSING:     renderClosing();    break;
            case BLOCKED:     renderBlocked();    break;
            case CALLING:     renderCalling();    break;
            case CALL_ENDING: renderCallEnding(); break;
        }
    }

    private void renderIdle() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.text_muted);
        statusText.setText(R.string.control_status_live);
        statusText.setTextColor(color(R.color.text_muted));

        bigBtn.setBackgroundResource(R.drawable.bg_button_primary);
        bigBtn.setClickable(true);
        bigBtnIcon.setImageResource(R.drawable.ic_door_open);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_open);

        hint.setVisibility(View.GONE);
        btnSecondary.setVisibility(View.GONE);

        setActionCardsEnabled(true);
        setCardsArrowsVisible(true);
        renderCardBlockInactive();
        renderCardCallInactive();
        renderInfoLastOpen();
    }

    private void renderOpening() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.accent_neon);
        statusText.setText(R.string.control_status_opening);
        statusText.setTextColor(color(R.color.accent_neon));

        bigBtn.setBackgroundResource(R.drawable.bg_button_primary);
        bigBtn.setClickable(true); // tap = cancelar (igual que el boton secundario)
        bigBtnIcon.setImageResource(R.drawable.ic_loader_circle);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        startSpinningIcon();
        bigBtnLabel.setText(R.string.control_btn_opening);

        hint.setVisibility(View.GONE);

        // "Cancelar" abajo
        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(R.string.control_secondary_cancel);

        // Cards disabled durante el ciclo de apertura
        setActionCardsEnabled(false);
        setCardsArrowsVisible(true);
        renderCardBlockInactive();
        renderCardCallInactive();
        renderInfoLastOpen();
    }

    private void renderOpen() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.accent_success);
        statusText.setTextColor(color(R.color.accent_success));

        bigBtn.setBackgroundResource(R.drawable.bg_button_open_dark);
        bigBtn.setClickable(false); // NO clickable en estado ABIERTA
        bigBtnIcon.setImageResource(R.drawable.ic_check);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_open_state);

        hint.setVisibility(View.VISIBLE);
        btnSecondary.setVisibility(View.GONE);

        setActionCardsEnabled(false);
        setCardsArrowsVisible(true);
        renderCardBlockInactive();
        renderCardCallInactive();
        renderInfoLastOpen();

        // Countdown (5..1) computado desde el helper.
        int remaining = DoorStateMachine.secondsRemainingInCountdown(this);
        statusText.setText(getString(R.string.control_status_open, remaining));
        hint.setText(getString(R.string.control_hint_open, remaining));
    }

    private void renderClosing() {
        title.setText(R.string.control_title);

        statusPill.setBackground(null);
        tintDot(R.color.accent_success);
        statusText.setText(R.string.control_status_closing);
        statusText.setTextColor(color(R.color.accent_success));

        bigBtn.setBackgroundResource(R.drawable.bg_button_open_dark);
        bigBtn.setClickable(false); // NO clickable en estado CERRANDO
        bigBtnIcon.setImageResource(R.drawable.ic_loader_circle);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        startSpinningIcon();
        bigBtnLabel.setText(R.string.control_btn_closing);

        hint.setVisibility(View.GONE);
        btnSecondary.setVisibility(View.GONE);

        setActionCardsEnabled(false);
        setCardsArrowsVisible(true);
        renderCardBlockInactive();
        renderCardCallInactive();
        renderInfoLastOpen();
    }

    private void renderBlocked() {
        title.setText(R.string.control_title_blocked);

        statusPill.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill_outlined_danger));
        tintDot(R.color.accent_block);
        statusText.setText(R.string.control_status_blocked);
        statusText.setTextColor(color(R.color.accent_block));

        bigBtn.setBackgroundResource(R.drawable.bg_button_blocked);
        bigBtn.setClickable(false); // No se puede abrir mientras esta bloqueada
        bigBtnIcon.setImageResource(R.drawable.ic_lock);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_blocked);

        hint.setVisibility(View.VISIBLE);
        hint.setText(R.string.control_hint_blocked);

        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(R.string.control_secondary_unblock);

        // Las cards no funcionan en BLOCKED (logica interna en cada listener),
        // pero las dejamos clickables para no hacerlas ver disabled.
        setActionCardsEnabled(true);
        setCardsArrowsVisible(false); // sin flechita: indican que no hay accion

        cardBlock.setBackgroundResource(R.drawable.bg_card_active_danger);
        cardBlockSubtitle.setText(R.string.control_card_active);
        cardBlockSubtitle.setTextColor(color(R.color.accent_block));

        renderCardCallInactive();

        // Info strip propio del modo seguridad
        infoIcon.setImageResource(R.drawable.ic_shield_check);
        infoIcon.setColorFilter(color(R.color.accent_block));
        infoTitle.setText(R.string.control_info_blocked_title);
        infoSubtitle.setText(R.string.control_info_blocked_subtitle);
    }

    private void renderCalling() {
        title.setText(R.string.control_title_calling);

        statusPill.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill_outlined_info));
        tintDot(R.color.accent_cyan);
        statusText.setTextColor(color(R.color.accent_cyan));

        bigBtn.setBackgroundResource(R.drawable.bg_button_calling);
        bigBtn.setClickable(false);
        bigBtnIcon.setImageResource(R.drawable.ic_megaphone);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        bigBtnLabel.setText(R.string.control_btn_calling);

        hint.setVisibility(View.VISIBLE);

        btnSecondary.setVisibility(View.VISIBLE);
        btnSecondary.setText(R.string.control_secondary_stop_call);

        setActionCardsEnabled(true);
        setCardsArrowsVisible(true);
        renderCardBlockInactive();

        cardCall.setBackgroundResource(R.drawable.bg_card_active_info);
        cardCallSubtitle.setText(R.string.control_card_active);
        cardCallSubtitle.setTextColor(color(R.color.accent_cyan));

        // Info strip propio de la llamada
        infoIcon.setImageResource(R.drawable.ic_bell);
        infoIcon.setColorFilter(color(R.color.accent_cyan));
        infoTitle.setText(R.string.control_info_calling_title);

        // Countdown (3..1)
        int remaining = DoorStateMachine.secondsRemainingInCountdown(this);
        statusText.setText(getString(R.string.control_status_calling, remaining));
        hint.setText(getString(R.string.control_hint_calling, remaining));
        infoSubtitle.setText(getString(R.string.control_info_calling_subtitle, remaining));
    }

    private void renderCallEnding() {
        title.setText(R.string.control_title_calling);

        statusPill.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill_outlined_info));
        tintDot(R.color.accent_cyan);
        statusText.setText(R.string.control_status_call_ending);
        statusText.setTextColor(color(R.color.accent_cyan));

        bigBtn.setBackgroundResource(R.drawable.bg_button_calling);
        bigBtn.setClickable(false);
        bigBtnIcon.setImageResource(R.drawable.ic_loader_circle);
        bigBtnIcon.setColorFilter(color(R.color.bg_card));
        startSpinningIcon();
        bigBtnLabel.setText(R.string.control_btn_call_ending);

        hint.setVisibility(View.GONE);
        btnSecondary.setVisibility(View.GONE);

        setActionCardsEnabled(true);
        setCardsArrowsVisible(true);
        renderCardBlockInactive();

        cardCall.setBackgroundResource(R.drawable.bg_card_active_info);
        cardCallSubtitle.setText(R.string.control_card_active);
        cardCallSubtitle.setTextColor(color(R.color.accent_cyan));

        infoIcon.setImageResource(R.drawable.ic_bell);
        infoIcon.setColorFilter(color(R.color.accent_cyan));
        infoTitle.setText(R.string.control_info_calling_title);
        infoSubtitle.setText(getString(R.string.control_info_calling_subtitle, 0));
    }

    // ============================================================
    // RENDER HELPERS (partes reusables)
    // ============================================================

    private void renderCardBlockInactive() {
        cardBlock.setBackgroundResource(R.drawable.bg_card);
        cardBlockSubtitle.setText(R.string.control_card_block_subtitle);
        cardBlockSubtitle.setTextColor(color(R.color.text_muted));
    }

    private void renderCardCallInactive() {
        cardCall.setBackgroundResource(R.drawable.bg_card);
        cardCallSubtitle.setText(R.string.control_card_call_subtitle);
        cardCallSubtitle.setTextColor(color(R.color.text_muted));
    }

    /**
     * Info strip "Ultima apertura: ahora · Manual · desde Control".
     * Reemplaza al sensor de proximidad que mostrabamos antes en IDLE/OPENING.
     */
    private void renderInfoLastOpen() {
        infoIcon.setImageResource(R.drawable.ic_check_check);
        infoIcon.setColorFilter(color(R.color.accent_success));
        infoTitle.setText(R.string.control_info_last_open_title);
        infoSubtitle.setText(R.string.control_info_last_open_subtitle);
    }

    /** Habilita o deshabilita las cards Bloquear y Llamar.
     *  Cuando esta disabled: no responde a clicks + alpha 50% para feedback visual. */
    private void setActionCardsEnabled(boolean enabled) {
        cardBlock.setClickable(enabled);
        cardCall.setClickable(enabled);
        float alpha = enabled ? 1.0f : 0.5f;
        cardBlock.setAlpha(alpha);
        cardCall.setAlpha(alpha);
    }

    private void setCardsArrowsVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        cardBlockArrow.setVisibility(v);
        cardCallArrow.setVisibility(v);
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private void tintDot(int colorRes) {
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color(colorRes)));
    }

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
}
