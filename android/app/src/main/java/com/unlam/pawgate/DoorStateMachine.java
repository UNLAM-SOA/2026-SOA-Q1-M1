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
}
