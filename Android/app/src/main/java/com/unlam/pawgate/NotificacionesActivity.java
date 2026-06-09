package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.NotificationRepository;
import com.unlam.pawgate.api.dto.NotificationDtos;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla "Notificaciones" — bandeja persistente del user (Sub-fase B+C).
 *
 * <p>Fuente de datos: backend real (GET /users/me/notifications). Cada notif
 * vive en DDB (pawgate_notifications) con TTL de 30 dias y se persiste cuando
 * eventIngest procesa un evento del ESP32 o del simulador.
 *
 * <p>Acciones soportadas:
 * <ul>
 *     <li>Filtro "Todas" / "No leídas" — recarga el list con onlyUnread=true|false</li>
 *     <li>Tap individual sobre una notif — marca como leída (POST /{id}/read)</li>
 *     <li>"Marcar leídas" en topbar — marca TODAS como leídas (POST /read)</li>
 * </ul>
 *
 * <p>El badge de no-leidas en el Dashboard se sincroniza al volver: el Dashboard
 * pollea el unread-count, asi que cualquier mark-read en esta pantalla se ve
 * reflejada al hacer back.
 */
public class NotificacionesActivity extends AppCompatActivity {

    /**
     * Extra del Intent. Cuando llega no-null, esta activity hace POST mark-read
     * de esa notif al onCreate. Usado por PawGateFcmService cuando el user
     * tap el push.
     */
    public static final String EXTRA_NOTIF_ID_TO_READ = "notif_id_to_read";

    /**
     * Broadcast emitido cuando se marcan notifs como leidas. El Dashboard lo
     * escucha para actualizar su badge sin esperar al proximo onResume.
     */
    public static final String ACTION_NOTIFS_READ_CHANGED =
            "com.unlam.pawgate.NOTIFS_READ_CHANGED";

    private TextView chipAll;
    private TextView chipUnread;
    private TextView markAllBtn;
    private RecyclerView listView;
    private TextView emptyView;
    private ProgressBar progressView;

    private NotificationRepository repo;
    private NotificacionAdapter adapter;
    private boolean onlyUnread = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notificaciones);

        repo = new NotificationRepository(this);

        markAllBtn = findViewById(R.id.notificaciones_mark_all);
        markAllBtn.setOnClickListener(v -> markAllRead());

        listView = findViewById(R.id.notif_list);
        emptyView = findViewById(R.id.notif_empty);
        progressView = findViewById(R.id.notif_progress);

        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.addItemDecoration(new HistorialActivity.InsetDividerDecoration(this));

        wireChips();
        BottomNavHelper.bind(this, R.id.nav_inicio);

        // Si llegamos desde un tap del push, marcamos la notif especifica como
        // leida ANTES de cargar la lista. Si el POST mark-read llega rapido,
        // cuando loadNotifications hace el GET ya viene read=true.
        handlePushTapIfAny(getIntent());

        loadNotifications();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // SINGLE_TOP: si la activity ya estaba abierta cuando llego el tap
        // del push, no se crea instancia nueva, se reusa con onNewIntent.
        // Aprovechamos para marcar como leida la notif del push tambien aca.
        setIntent(intent);
        handlePushTapIfAny(intent);
        loadNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload al volver de un push tapped o de Dashboard: garantiza que
        // si llegó un push nuevo mientras la pantalla estaba en pause, lo veamos.
        if (adapter != null) loadNotifications();
    }

    /**
     * Si el Intent trae EXTRA_NOTIF_ID_TO_READ (proveniente del tap en push),
     * marca esa notif como leida YA. Es fire-and-forget: si falla por red,
     * el user va a verla como unread y la puede tapear manual.
     *
     * El extra se consume (lo borra del Intent) para que un onResume posterior
     * o cambio de configuracion no la vuelva a procesar.
     */
    private void handlePushTapIfAny(Intent intent) {
        if (intent == null) return;
        String pushNotifId = intent.getStringExtra(EXTRA_NOTIF_ID_TO_READ);
        if (pushNotifId == null || pushNotifId.isEmpty()) return;
        intent.removeExtra(EXTRA_NOTIF_ID_TO_READ);

        repo.markRead(pushNotifId, new ApiCallback<NotificationDtos.MarkReadResponse>() {
            @Override public void onSuccess(NotificationDtos.MarkReadResponse result) {
                notifyUnreadCountChanged();
            }
            @Override public void onError(String message) {
                android.util.Log.w("Notificaciones",
                        "auto markRead from push failed (ignored): " + message);
            }
        });
    }

    /**
     * Avisa que el unread-count local cambio.
     *
     * Hace 2 cosas:
     *   1) Guarda el conteo de NO-leidas que conocemos local (basado en el
     *      adapter) como OVERRIDE en SharedPrefs. El Dashboard al refrescar
     *      el badge va a usar este override (con MIN vs server) hasta que
     *      el server confirme que ya proceso los POSTs.
     *   2) Emite un broadcast LOCAL por si el Dashboard esta vivo en ese
     *      momento (caso raro pero posible: split-screen, etc).
     *
     * La capa 1 cubre el caso comun donde el Dashboard esta paused durante
     * el tap. El receiver del Dashboard del broadcast solo cubre extras.
     */
    private void notifyUnreadCountChanged() {
        if (adapter != null) {
            PrefsHelper.setUnreadOverride(this, countUnreadInAdapter());
        }
        Intent broadcast = new Intent(ACTION_NOTIFS_READ_CHANGED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    /** Cuenta cuantas notifs NO leidas tiene el adapter ahora. */
    private int countUnreadInAdapter() {
        if (adapter == null) return 0;
        int count = 0;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            // Usamos la API publica del adapter: items con noLeida=true.
            // Si en algun momento agregamos un getter, lo cambiamos.
            // Por ahora hacemos un truco: recargamos la list ref local.
        }
        // El adapter ahora expone countUnread(). Si no, contamos localmente.
        return adapter.countUnread();
    }

    // ============================================================
    // CHIPS DE FILTRO
    // ============================================================

    private void wireChips() {
        chipAll = findViewById(R.id.chip_all);
        chipUnread = findViewById(R.id.chip_unread);
        chipAll.setOnClickListener(v -> { onlyUnread = false; seleccionarChip(chipAll); loadNotifications(); });
        chipUnread.setOnClickListener(v -> { onlyUnread = true; seleccionarChip(chipUnread); loadNotifications(); });
    }

    private void seleccionarChip(TextView active) {
        TextView[] chips = {chipAll, chipUnread};
        for (TextView chip : chips) {
            boolean isActive = chip == active;
            chip.setBackgroundResource(isActive ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chip.setTextColor(ContextCompat.getColor(this,
                    isActive ? R.color.bg_card : R.color.text_secondary));
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        }
    }

    // ============================================================
    // DATA LOADING
    // ============================================================

    private void loadNotifications() {
        showState(/*loading=*/true, /*empty=*/false);
        repo.list(50, onlyUnread, new ApiCallback<NotificationDtos.ListResponse>() {
            @Override public void onSuccess(NotificationDtos.ListResponse result) {
                List<NotificacionAdapter.Notificacion> mapped = mapItems(result.items);
                adapter = new NotificacionAdapter(mapped, (item, position) -> onItemTapped(item, position));
                listView.setAdapter(adapter);
                showState(false, mapped.isEmpty());
            }
            @Override public void onError(String message) {
                showState(false, true);
                emptyView.setText(R.string.notif_loading_error);
                Toast.makeText(NotificacionesActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showState(boolean loading, boolean empty) {
        progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
        listView.setVisibility(!loading && !empty ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(!loading && empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyView.setText(onlyUnread ? R.string.notif_empty_unread : R.string.notif_empty);
        }
    }

    // ============================================================
    // TAP HANDLERS
    // ============================================================

    private void onItemTapped(NotificacionAdapter.Notificacion item, int position) {
        if (item.notifId == null || !item.noLeida) return; // ya leida o sin id
        // UX optimista: marcamos local YA, luego avisamos al backend. Si falla,
        // log silencioso (el polling al volver la sincroniza).
        adapter.markRead(position);
        // Emitimos el broadcast ANTES de esperar la respuesta del server. El
        // Dashboard refresca el badge con un delay corto que cubre el roundtrip
        // del POST mark-read. Si el POST falla, el badge se vuelve a sincronizar
        // en el proximo onResume del Dashboard.
        notifyUnreadCountChanged();
        repo.markRead(item.notifId, new ApiCallback<NotificationDtos.MarkReadResponse>() {
            @Override public void onSuccess(NotificationDtos.MarkReadResponse result) { /* ok */ }
            @Override public void onError(String message) {
                android.util.Log.w("Notificaciones", "markRead failed (ignored): " + message);
            }
        });
    }

    private void markAllRead() {
        if (adapter == null || adapter.getItemCount() == 0) return;
        // UX optimista igual que el tap individual.
        adapter.markAllRead();
        notifyUnreadCountChanged();
        repo.markAllRead(new ApiCallback<NotificationDtos.MarkReadResponse>() {
            @Override public void onSuccess(NotificationDtos.MarkReadResponse result) {
                int n = result.updated != null ? result.updated : 0;
                if (n > 0) {
                    Toast.makeText(NotificacionesActivity.this,
                            getString(R.string.notif_marked_all_read, n),
                            Toast.LENGTH_SHORT).show();
                }
                // Si el filtro era "No leidas", recargamos para vaciar la lista.
                if (onlyUnread) loadNotifications();
            }
            @Override public void onError(String message) {
                Toast.makeText(NotificacionesActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ============================================================
    // MAPEO BACKEND -> UI MODEL
    // ============================================================

    private List<NotificacionAdapter.Notificacion> mapItems(List<NotificationDtos.NotificationItem> items) {
        List<NotificacionAdapter.Notificacion> out = new ArrayList<>();
        if (items == null) return out;
        for (NotificationDtos.NotificationItem it : items) {
            int iconRes = iconForType(it.type);
            String titulo = it.title != null ? it.title : titleForType(it.type);
            String subtitulo = buildSubtitle(it);
            out.add(new NotificacionAdapter.Notificacion(
                    it.notifId, iconRes, titulo, subtitulo, !it.read));
        }
        return out;
    }

    /**
     * Mapea cada tipo CANONICO a un icono drawable. Esta es la fuente de
     * verdad de "que notifs aceptamos en la bandeja". Si el backend manda
     * un tipo no listado aca, igual lo muestra con el ic_bell por defecto.
     *
     * Lista canonica (la definida con Fede en la conversacion):
     *
     *   FROM eventIngest:
     *     opened       -> Puerta abierta (mascota, con direction in/out)
     *     blocked      -> Puerta bloqueada
     *     unblocked    -> Puerta desbloqueada
     *     light_on     -> Luz prendida
     *     light_off    -> Luz apagada
     *
     *   FROM apiHandler (acciones del user con actor):
     *     cmd_open     -> Puerta abierta hacia X por NAME
     *     cmd_block    -> Puerta bloqueada por NAME
     *     cmd_unblock  -> Puerta desbloqueada por NAME
     *     cmd_call     -> NAME llamó a la mascota
     *
     *   FROM scheduleExecutor / horarios automaticos:
     *     schedule_activated         -> Horario NAME activado
     *     schedule_deactivated       -> Horario NAME desactivado
     *     schedule_block_end         -> Puerta bloqueada por fin de horario
     *     schedule_unblock_start     -> Puerta desbloqueada por inicio de horario
     */
    static int iconForType(String type) {
        if (type == null) return R.drawable.ic_bell;
        switch (type) {
            // ----- Eventos fisicos del ESP32 / simulador -----
            case "opened":         return R.drawable.ic_door_open;
            case "blocked":        return R.drawable.ic_lock;
            case "unblocked":      return R.drawable.ic_shield_check;
            case "light_on":       return R.drawable.ic_sun;
            case "light_off":      return R.drawable.ic_moon;

            // ----- Acciones manuales del user -----
            case "cmd_open":       return R.drawable.ic_log_in;
            case "cmd_block":      return R.drawable.ic_lock;
            case "cmd_unblock":    return R.drawable.ic_shield_check;
            case "cmd_call":       return R.drawable.ic_phone_call;

            // ----- Horarios automaticos -----
            case "schedule_activated":     return R.drawable.ic_calendar;
            case "schedule_deactivated":   return R.drawable.ic_calendar;
            case "schedule_block_end":     return R.drawable.ic_moon;
            case "schedule_unblock_start": return R.drawable.ic_sunrise;

            default:               return R.drawable.ic_bell;
        }
    }

    /** Fallback de titulo si el backend no mandó uno. */
    private String titleForType(String type) {
        return type != null ? type : "Notificación";
    }

    /** Subtitulo = tiempo relativo + actor si existe. Ej: "hace 3m · por federico@..." */
    private String buildSubtitle(NotificationDtos.NotificationItem it) {
        String time = formatRelative(it.createdAt);
        String actor = formatActor(it.actor);
        if (actor == null) return time;
        return time + " · " + actor;
    }

    private String formatRelative(String iso8601) {
        if (iso8601 == null || iso8601.isEmpty()) return "";
        try {
            long millis = OffsetDateTime.parse(iso8601).toInstant().toEpochMilli();
            return DateUtils.getRelativeTimeSpanString(
                    millis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE).toString();
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    /**
     * Formatea el actor para mostrar:
     *  - null o "system"   -> null (omitido)
     *  - email del user logueado -> "por Vos"
     *  - otro email -> "por nombre" (la parte antes del @)
     */
    private String formatActor(String actor) {
        if (actor == null || actor.isEmpty() || "system".equals(actor)) return null;
        String myEmail = PrefsHelper.getUserEmail(this);
        if (actor.equalsIgnoreCase(myEmail)) {
            return getString(R.string.notif_actor_by, getString(R.string.notif_actor_you));
        }
        String shortName = actor.contains("@") ? actor.substring(0, actor.indexOf('@')) : actor;
        return getString(R.string.notif_actor_by, shortName);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
