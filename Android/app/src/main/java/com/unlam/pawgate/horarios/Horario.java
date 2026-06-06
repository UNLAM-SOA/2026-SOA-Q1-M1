package com.unlam.pawgate.horarios;

/**
 * Modelo de un horario programado.
 *
 * Un horario representa una VENTANA en que la puerta queda desbloqueada.
 * Fuera de cualquier horario activo, el backend (scheduleExecutor) la
 * bloquea automaticamente.
 *
 * Restriccion: minutos de inicio y fin SIEMPRE en {0, 30}. El cron del
 * scheduleExecutor evalua en esos slots, asi que cualquier otro valor
 * se ignoraria.
 */
public class Horario {

    /** Bits de dias de la semana (combinables con OR). */
    public static final int LUN = 1;
    public static final int MAR = 1 << 1;
    public static final int MIE = 1 << 2;
    public static final int JUE = 1 << 3;
    public static final int VIE = 1 << 4;
    public static final int SAB = 1 << 5;
    public static final int DOM = 1 << 6;
    public static final int SEMANA = LUN | MAR | MIE | JUE | VIE;
    public static final int FIN_SEMANA = SAB | DOM;

    /** id del schedule en backend (null si todavia no se guardo). */
    public String id;
    public String nombre;
    public int horaInicio;       // 0..23
    public int minutoInicio;     // 0 o 30
    public int horaFin;
    public int minutoFin;
    public int diasBitmask;
    public boolean activo;

    public Horario() {}

    public static Horario nuevo() {
        Horario h = new Horario();
        h.id = null;
        h.nombre = "";
        h.horaInicio = 7;
        h.minutoInicio = 30;
        h.horaFin = 9;
        h.minutoFin = 0;
        h.diasBitmask = SEMANA;
        h.activo = true;
        return h;
    }

    public boolean tieneDia(int diaBit) {
        return (diasBitmask & diaBit) != 0;
    }

    public void toggleDia(int diaBit) {
        diasBitmask ^= diaBit;
    }

    public String formatHoraInicio() {
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", horaInicio, minutoInicio);
    }

    public String formatHoraFin() {
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", horaFin, minutoFin);
    }

    /** Snapea un minuto cualquiera al slot mas cercano (0 o 30).
     *  0-14 -> 0 ; 15-44 -> 30 ; 45-59 -> 0 (suma 1 a la hora, mod 24). */
    public static int snapMinuto30(int minuto) {
        if (minuto < 15) return 0;
        if (minuto < 45) return 30;
        return 0; // y el caller incrementa la hora
    }

    /** True si al snapear, el minuto >= 45 (caso de rollover a la hora siguiente). */
    public static boolean snapRollsOver(int minuto) {
        return minuto >= 45;
    }
}
