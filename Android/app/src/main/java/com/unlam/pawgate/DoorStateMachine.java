package com.unlam.pawgate;

import android.content.Context;
import android.os.SystemClock;

/**
 * State machine de la puerta basada en SharedPreferences + timestamps.
 *
 * Idea clave: el estado NO es una variable in-memory en la Activity. Esta
 * derivado de:
 *   - PrefsHelper.isDoorBlocked() -> si esta en true, estado = BLOCKED.
 *   - PrefsHelper.getCycleType() + getCycleStartMs() -> tiempo transcurrido
 *     desde que empezo el ciclo. Comparado con las duraciones de cada subestado,
 *     determina si estamos en OPENING, OPEN, CLOSING, CALLING, CALL_ENDING o IDLE.
 *
 * Beneficio: si el usuario navega Dashboard <-> Control, el estado se mantiene
 * consistente sin Handlers compartidos ni Services. Cada Activity lee el estado
 * cuando se renderiza.
 *
 * Tradeoff: el timer real corre en background siempre (no se pausa cuando la
 * Activity esta paused). Para una puerta de IoT eso es lo correcto: la puerta
 * fisica sigue abriendose aunque el user navegue lejos.
 *
 * Reloj: usamos SystemClock.elapsedRealtime() (monotonic, incluye deep sleep)
 * en vez de System.currentTimeMillis() (wall-clock, afectado por cambios de
 * hora del usuario o sincronizacion NTP). Para medir duraciones cortas la
 * diferencia rara vez importa; en background largo el monotonic es seguro.
 *
 * Diagrama temporal del ciclo OPEN_DOOR (0ms = startCycle):
 *
 *   0----OPENING(2s)----2000---OPEN(5s, countdown)---7000---CLOSING(2s)---9000---IDLE
 *
 * Diagrama temporal del ciclo CALL:
 *
 *   0----CALLING(3s, countdown)----3000---CALL_ENDING(1s)---4000---IDLE
 */
public final class DoorStateMachine {

    public enum DoorState {
        IDLE, OPENING, OPEN, CLOSING, BLOCKED, CALLING, CALL_ENDING
    }

    // Duraciones de cada subestado (ms). Si cambia algo aca, todos los Activities
    // que usen el helper ven el cambio (sin tocar codigo en cada uno).
    public static final long OPENING_MS = 2_000L;
    public static final long OPEN_MS = 5_000L;
    public static final long CLOSING_MS = 2_000L;
    public static final long CALLING_MS = 3_000L;
    public static final long CALL_ENDING_MS = 1_000L;

    private DoorStateMachine() { /* no instanciar */ }

    /** Computa el estado actual de la puerta a partir de PrefsHelper + clock. */
    public static DoorState currentState(Context ctx) {
        // BLOCKED siempre tiene prioridad (override total).
        if (PrefsHelper.isDoorBlocked(ctx)) return DoorState.BLOCKED;

        String cycle = PrefsHelper.getCycleType(ctx);
        long elapsed = SystemClock.elapsedRealtime() - PrefsHelper.getCycleStartMs(ctx);

        if (PrefsHelper.CYCLE_OPEN_DOOR.equals(cycle)) {
            if (elapsed < OPENING_MS) return DoorState.OPENING;
            if (elapsed < OPENING_MS + OPEN_MS) return DoorState.OPEN;
            if (elapsed < OPENING_MS + OPEN_MS + CLOSING_MS) return DoorState.CLOSING;
            return DoorState.IDLE;
        }

        if (PrefsHelper.CYCLE_CALL.equals(cycle)) {
            if (elapsed < CALLING_MS) return DoorState.CALLING;
            if (elapsed < CALLING_MS + CALL_ENDING_MS) return DoorState.CALL_ENDING;
            return DoorState.IDLE;
        }

        return DoorState.IDLE;
    }

    /**
     * Segundos restantes para mostrar en el countdown del estado actual.
     * Devuelve 0 si el estado no tiene countdown visible (transients sin numero).
     *
     * Solo OPEN (5,4,3,2,1) y CALLING (3,2,1) tienen countdown visible.
     */
    public static int secondsRemainingInCountdown(Context ctx) {
        if (PrefsHelper.isDoorBlocked(ctx)) return 0;

        String cycle = PrefsHelper.getCycleType(ctx);
        long elapsed = SystemClock.elapsedRealtime() - PrefsHelper.getCycleStartMs(ctx);

        if (PrefsHelper.CYCLE_OPEN_DOOR.equals(cycle)
                && elapsed >= OPENING_MS && elapsed < OPENING_MS + OPEN_MS) {
            long remainingMs = OPENING_MS + OPEN_MS - elapsed;
            return Math.max(1, (int) Math.ceil(remainingMs / 1000.0));
        }

        if (PrefsHelper.CYCLE_CALL.equals(cycle) && elapsed < CALLING_MS) {
            long remainingMs = CALLING_MS - elapsed;
            return Math.max(1, (int) Math.ceil(remainingMs / 1000.0));
        }

        return 0;
    }

    // ============================================================
    // SINCRONIZACION CON EVENTOS DEL FIRMWARE
    // ============================================================

    /**
     * Sincroniza el ciclo local con un evento del backend/firmware.
     *
     * Llamado por PawGatePollingService cada vez que llega un evento nuevo
     * del topic events/door (opened, closed, blocked, unblocked).
     *
     * Casos:
     *
     *   "opened" — el firmware acaba de abrir la puerta. Puede ser por:
     *     a) la mascota (sensor RFID/proximidad) — no hubo cmd previo
     *     b) un cmd del user (cmd_open) — la app ya arranco un ciclo
     *   Si NO hay un ciclo OPEN_DOOR vivo, arrancamos uno con la direction
     *   del evento. Asi la UI muestra 'Abriendo hacia X'. Si YA hay uno
     *   vivo (caso b), no rearrancamos — el ciclo local ya esta sincronizado.
     *
     *   "closed" — el firmware acaba de cerrar la puerta. Si hay un ciclo
     *   OPEN_DOOR vivo y estamos en OPEN, saltamos a CLOSING ya (asi la UI
     *   se actualiza inmediatamente en vez de esperar al timer local).
     *   Si ya estamos en CLOSING o IDLE, no hacemos nada.
     *
     *   "blocked" — la puerta se bloqueo (por cmd o por horario). Marcamos
     *   isDoorBlocked=true. Esto pisa cualquier ciclo activo.
     *
     *   "unblocked" — la puerta se desbloqueo. Marcamos isDoorBlocked=false.
     *
     * Idempotente: si el evento ya esta reflejado, no hace nada.
     *
     * @return true si hubo cambio de estado, false si ya estaba sincronizado.
     *         El caller usa el retorno para emitir broadcast y refrescar UI.
     */
    public static boolean onExternalDoorEvent(Context ctx, String eventType,
                                               String direction) {
        if (eventType == null) return false;
        switch (eventType) {
            case "opened":
                return handleOpenedEvent(ctx, direction);
            case "closed":
                return handleClosedEvent(ctx);
            case "blocked":
                if (PrefsHelper.isDoorBlocked(ctx)) return false;
                PrefsHelper.setDoorBlocked(ctx, true);
                return true;
            case "unblocked":
                if (!PrefsHelper.isDoorBlocked(ctx)) return false;
                PrefsHelper.setDoorBlocked(ctx, false);
                return true;
            default:
                return false;
        }
    }

    private static boolean handleOpenedEvent(Context ctx, String direction) {
        // Si ya hay un ciclo OPEN_DOOR vivo (no expirado), no rearrancamos.
        // Solo actualizamos la direction si nos la pasaron y no la teniamos.
        String cycle = PrefsHelper.getCycleType(ctx);
        long elapsed = SystemClock.elapsedRealtime() - PrefsHelper.getCycleStartMs(ctx);
        boolean openDoorCycleAlive =
                PrefsHelper.CYCLE_OPEN_DOOR.equals(cycle)
                && elapsed < (OPENING_MS + OPEN_MS + CLOSING_MS);
        if (openDoorCycleAlive) {
            // El user ya disparo cmd_open desde la app. El evento del
            // firmware es la confirmacion. No tocamos el ciclo. Si veniamos
            // sin direction y ahora llego, la guardamos.
            if (direction != null && PrefsHelper.getCycleDirection(ctx) == null) {
                PrefsHelper.startCycle(ctx,
                        PrefsHelper.CYCLE_OPEN_DOOR,
                        direction);
                // Restablecemos el startMs original para no resetear el ciclo.
                long savedStart = SystemClock.elapsedRealtime() - elapsed;
                PrefsHelper.setCycleStartMs(ctx, savedStart);
                return true;
            }
            return false;
        }
        // Caso comun: la mascota abrio (sensor) -> arrancamos ciclo nuevo.
        PrefsHelper.startCycle(ctx, PrefsHelper.CYCLE_OPEN_DOOR, direction);
        return true;
    }

    private static boolean handleClosedEvent(Context ctx) {
        String cycle = PrefsHelper.getCycleType(ctx);
        if (!PrefsHelper.CYCLE_OPEN_DOOR.equals(cycle)) return false;
        long elapsed = SystemClock.elapsedRealtime() - PrefsHelper.getCycleStartMs(ctx);
        // Si ya estamos en CLOSING o IDLE, no tocar.
        if (elapsed >= OPENING_MS + OPEN_MS) return false;
        // "Saltar" a CLOSING: ajustar startMs para que elapsed quede justo
        // al borde OPEN -> CLOSING. Asi currentState() devuelve CLOSING ya.
        long now = SystemClock.elapsedRealtime();
        long newStart = now - (OPENING_MS + OPEN_MS);
        PrefsHelper.setCycleStartMs(ctx, newStart);
        return true;
    }
}
