package com.unlam.pawgate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import com.unlam.pawgate.api.dto.ScheduleDtos;

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

    /** Si el Dashboard ya pregunto la direccion ("in"|"out"), la pasa aca para
     *  que dispatchemos el cmd/open con body inmediatamente sin re-preguntar. */
    public static final String EXTRA_OPEN_DIRECTION = "open_direction";

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

    /** Recibe los broadcasts del PawGatePollingService cuando el lock cambia. */
    private final BroadcastReceiver eventUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // El Service ya sincronizo PrefsHelper.isDoorBlocked.
            // Solo necesitamos re-renderizar leyendo el state actual.
            refreshTickRunnable.run();
        }
    };

    /** Tick local que pollea /state cada 3s mientras Control sea visible.
     *  Independiente del PawGatePollingService. */
    private static final long STATE_POLL_INTERVAL_MS = 3_000L;
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
                boolean shouldBeBlocked = "AUTO_BLOCKED".equals(state.lock_state)
                        || "MANUAL_BLOCKED".equals(state.lock_state);
                boolean locallyBlocked = PrefsHelper.isDoorBlocked(ControlActivity.this);
                if (shouldBeBlocked != locallyBlocked) {
                    PrefsHelper.setDoorBlocked(ControlActivity.this, shouldBeBlocked);
                    if (shouldBeBlocked) PrefsHelper.clearCycle(ControlActivity.this);
                    refreshTickRunnable.run();
                }
            }
            @Override public void onError(String message) { /* silencio */ }
        });
    }

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

        // Si el Dashboard ya pregunto la direccion, el ciclo local ya esta arrancado.
        // Solo nos queda disparar el cmd/open con body.direction. Lo hacemos UNA vez
        // (consumimos el extra para que no se re-dispare en config changes).
        String direction = getIntent().getStringExtra(EXTRA_OPEN_DIRECTION);
        if (direction != null && savedInstanceState == null) {
            dispatchOpen(direction);
            getIntent().removeExtra(EXTRA_OPEN_DIRECTION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTickRunnable.run();
        statePollRunnable.run();

        // Receiver para que el broadcast del Service nos despierte el render
        // sin tener que esperar al tick local.
        IntentFilter filter = new IntentFilter(PawGatePollingService.ACTION_EVENT_UPDATE);
        ContextCompat.registerReceiver(this, eventUpdateReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshTickRunnable);
        handler.removeCallbacks(statePollRunnable);
        try {
            unregisterReceiver(eventUpdateReceiver);
        } catch (IllegalArgumentException ignored) {
            // defensivo
        }
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
            // Preguntar al user hacia donde abrir, despues disparar el ciclo.
            OpenDirectionBottomSheet.show(getSupportFragmentManager(), direction -> {
                PrefsHelper.startCycle(this, PrefsHelper.CYCLE_OPEN_DOOR);
                refreshTickRunnable.run();
                dispatchOpen(direction);
            });
        } else if (s == DoorStateMachine.DoorState.OPENING) {
            // Cancelar el ciclo en curso (solo local - el backend no tiene "cancelar")
            PrefsHelper.clearCycle(this);
            refreshTickRunnable.run();
        }
        // En OPEN, CLOSING, BLOCKED, CALLING, CALL_ENDING -> no hace nada
    }

    /** Despacha cmd/open con direction en el body. */
    private void dispatchOpen(String direction) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        if (direction != null) body.put("direction", direction);
        deviceRepo.sendCommand(deviceId, DeviceRepository.CMD_OPEN, body,
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse>() {
            @Override public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.CommandResponse r) {}
            @Override public void onError(String message) {
                Toast.makeText(ControlActivity.this,
                        "Error al abrir: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onBlockCardClick() {
        DoorStateMachine.DoorState s = currentState();
        if (s == DoorStateMachine.DoorState.OPENING
                || s == DoorStateMachine.DoorState.OPEN
                || s == DoorStateMachine.DoorState.CLOSING
                || s == DoorStateMachine.DoorState.BLOCKED) {
            return;
        }
        promptBlockWithHorarioAwareness();
    }

    /** Refetch state -> dialog regular o de override segun in_horario. */
    private void promptBlockWithHorarioAwareness() {
        deviceRepo.getDeviceState(deviceId, new ApiCallback<ScheduleDtos.DeviceStateResponse>() {
            @Override public void onSuccess(ScheduleDtos.DeviceStateResponse state) {
                boolean inHorario = state != null && state.currently_in_horario;
                if (inHorario) showOverrideBlockDialog();
                else showRegularBlockDialog();
            }
            @Override public void onError(String message) {
                // Si no podemos consultar el state, asumimos fuera de horario.
                showRegularBlockDialog();
            }
        });
    }

    private void showRegularBlockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.control_block_dialog_title)
                .setMessage(R.string.control_block_dialog_message)
                .setPositiveButton(R.string.control_block_dialog_confirm,
                        (d, w) -> applyBlock(false))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showOverrideBlockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.override_block_title)
                .setMessage(R.string.override_block_message)
                .setPositiveButton(R.string.override_block_confirm,
                        (d, w) -> applyBlock(true))
                .setNegativeButton(R.string.override_block_cancel, null)
                .show();
    }

    private void applyBlock(boolean useOverride) {
        // UI optimista igual que antes
        PrefsHelper.setDoorBlocked(this, true);
        PrefsHelper.clearCycle(this);
        refreshTickRunnable.run();

        if (useOverride) {
            deviceRepo.overrideBlock(deviceId, new ApiCallback<ScheduleDtos.OverrideUnblockResponse>() {
                @Override public void onSuccess(ScheduleDtos.OverrideUnblockResponse r) { /* ok */ }
                @Override public void onError(String message) {
                    Toast.makeText(ControlActivity.this,
                            "Error al bloquear: " + message, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            dispatchCommand(DeviceRepository.CMD_BLOCK);
        }
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
                // Desbloquear con awareness del horario natural.
                promptUnblockWithHorarioAwareness();
                break;
            case OPENING:
            case CALLING:
            case CALL_ENDING:
                // Cancelacion local. El backend no tiene "cancelar" - el device
                // termina su ciclo igual. En una version siguiente podriamos
                // mandar un cmd "stop" si el contrato MQTT lo soporta.
                PrefsHelper.clearCycle(this);
                refreshTickRunnable.run();
                break;
            default:
                // No deberia llegar (el boton solo es visible en los anteriores)
                break;
        }
    }

    // ============================================================
    // UNBLOCK con awareness de horario natural
    // ============================================================

    private void promptUnblockWithHorarioAwareness() {
        deviceRepo.getDeviceState(deviceId, new ApiCallback<ScheduleDtos.DeviceStateResponse>() {
            @Override public void onSuccess(ScheduleDtos.DeviceStateResponse state) {
                boolean inHorario = state != null && state.currently_in_horario;
                if (inHorario) {
                    // Dentro de horario: desbloquear es coherente con el natural.
                    applyUnblock(false);
                } else {
                    // Fuera de horario: confirmar override.
                    showOverrideUnblockDialog();
                }
            }
            @Override public void onError(String message) {
                // Si no podemos consultar, default a override (mas conservador).
                showOverrideUnblockDialog();
            }
        });
    }

    private void showOverrideUnblockDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.override_unblock_title)
                .setMessage(R.string.override_unblock_message)
                .setPositiveButton(R.string.override_unblock_confirm,
                        (d, w) -> applyUnblock(true))
                .setNegativeButton(R.string.override_unblock_cancel, null)
                .show();
    }

    private void applyUnblock(boolean useOverride) {
        PrefsHelper.setDoorBlocked(this, false);
        PrefsHelper.clearCycle(this);
        refreshTickRunnable.run();

        if (useOverride) {
            deviceRepo.overrideUnblock(deviceId, new ApiCallback<ScheduleDtos.OverrideUnblockResponse>() {
                @Override public void onSuccess(ScheduleDtos.OverrideUnblockResponse r) { /* ok */ }
                @Override public void onError(String message) {
                    Toast.makeText(ControlActivity.this,
                            "Error al desbloquear: " + message, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            dispatchCommand(DeviceRepository.CMD_UNBLOCK);
        }
    }

    // ============================================================
    // BACKEND DISPATCH
    // ============================================================

    /**
     * Manda el comando al backend. UX optimista: la UI ya cambio antes de
     * llamar este metodo (startCycle local). Si el POST falla, solo toast,
     * NO revertimos el estado local (el polling de /history terminara
     * conciliando si el device realmente no recibio el cmd).
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
