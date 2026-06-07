package com.unlam.pawgate;

import com.unlam.pawgate.api.dto.DeviceDtos;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapper: DeviceDtos.Event (DTO del backend) -> HistorialAdapter.Evento (modelo del adapter).
 *
 * Responsabilidades:
 *   1) Elegir el icono segun event_type ("opened" -> ic_door_open, etc).
 *   2) Elegir el titulo human-readable.
 *   3) Formatear el subtitulo como "hace Xm" / "ayer · HH:mm" usando DateUtils.
 *
 * El backend devuelve created_at como ISO 8601 UTC (ej: "2026-06-01T22:14:00.000Z").
 * Parseamos a epoch ms y se lo pasamos a DateUtils.getRelativeTimeSpanString,
 * que ya hace toda la logica i18n de "hace 2 minutos" / "ayer a las..." correctamente.
 */
public final class HistorialMapper {

    private HistorialMapper() {}

    // Parser ISO 8601 robusto. El backend Python emite con microsegundos
    // (.123456) y offset numerico (+00:00), formato que SimpleDateFormat no
    // matchea bien. OffsetDateTime de java.time si lo parsea sin problemas
    // (cualquier precision de fraccion segundos + offset).
    // Requiere API 26+ (minSdk de la app).

    public static List<HistorialAdapter.Evento> mapAll(List<DeviceDtos.Event> events) {
        List<HistorialAdapter.Evento> out = new ArrayList<>(events.size());
        long nowMs = System.currentTimeMillis();
        for (DeviceDtos.Event e : events) {
            out.add(map(e, nowMs));
        }
        return out;
    }

    private static HistorialAdapter.Evento map(DeviceDtos.Event e, long nowMs) {
        int icon = iconFor(e.event_type);
        String titulo = titleFor(e.event_type, e.direction);
        String subtitulo = formatRelativeTime(e.created_at, nowMs);
        return new HistorialAdapter.Evento(icon, titulo, subtitulo);
    }

    // ============================================================
    // Iconos por tipo de evento
    // ============================================================
    private static int iconFor(String eventType) {
        if (eventType == null) return R.drawable.ic_door_open;
        switch (eventType) {
            case "opened":            return R.drawable.ic_door_open;
            case "closed":            return R.drawable.ic_door_open;
            case "blocked":           return R.drawable.ic_lock;
            case "unblocked":         return R.drawable.ic_lock_open;
            case "calling":           return R.drawable.ic_megaphone;
            case "call_ended":        return R.drawable.ic_bell;
            case "light_on":          return R.drawable.ic_lightbulb;
            case "light_off":         return R.drawable.ic_lightbulb_off;
            case "wifi_reconnected":  return R.drawable.ic_wifi;
            case "schedule_fired":    return R.drawable.ic_calendar;
            default:                  return R.drawable.ic_door_open;
        }
    }

    // ============================================================
    // Titulo human-readable por tipo de evento (y direccion si aplica)
    // ============================================================
    private static String titleFor(String eventType, String direction) {
        if (eventType == null) return "Evento";
        switch (eventType) {
            case "opened":
                if ("in".equals(direction))  return "Puerta abierta hacia adentro";
                if ("out".equals(direction)) return "Puerta abierta hacia afuera";
                return "Puerta abierta";
            case "closed":
                if ("in".equals(direction))  return "Puerta cerrada hacia adentro";
                if ("out".equals(direction)) return "Puerta cerrada hacia afuera";
                return "Puerta cerrada";
            case "blocked":          return "Puerta bloqueada";
            case "unblocked":        return "Puerta desbloqueada";
            case "calling":          return "Llamando a la mascota";
            case "call_ended":       return "Llamada finalizada";
            case "light_on":         return "Luz encendida";
            case "light_off":        return "Luz apagada";
            case "wifi_reconnected": return "WiFi reconectado";
            case "schedule_fired":   return "Horario ejecutado";
            default:                 return eventType;
        }
    }

    /**
     * Parsea una fecha ISO 8601 del backend a epoch ms. Devuelve -1 si no parsea.
     */
    public static long parseIsoToMs(String createdAtIso) {
        if (createdAtIso == null || createdAtIso.isEmpty()) return -1L;
        try {
            return OffsetDateTime.parse(createdAtIso).toInstant().toEpochMilli();
        } catch (DateTimeParseException ex) {
            return -1L;
        }
    }

    /** Helper publico para que call-sites externos formateen un ISO como "hace 2m". */
    public static String relativeTimeFor(String createdAtIso, long nowMs) {
        return formatRelativeTime(createdAtIso, nowMs);
    }

    // ============================================================
    // Subtitulo: "hace 2 min" / "hace 1 hora" / etc.
    // ============================================================
    /**
     * Custom formatter en castellano. No usamos DateUtils.getRelativeTimeSpanString
     * porque adapta los strings al locale del device, y si el device esta en
     * ingles muestra '1 min. ago' aunque la UI este en castellano.
     */
    private static String formatRelativeTime(String createdAtIso, long nowMs) {
        if (createdAtIso == null || createdAtIso.isEmpty()) return "";
        long eventMs = parseIsoToMs(createdAtIso);
        if (eventMs < 0) return createdAtIso;
        long diffMs = nowMs - eventMs;
        if (diffMs < 0) return "ahora";
        long secs = diffMs / 1000;
        if (secs < 60) return "hace unos segundos";
        long mins = secs / 60;
        if (mins == 1) return "hace 1 min";
        if (mins < 60) return "hace " + mins + " min";
        long hours = mins / 60;
        if (hours == 1) return "hace 1 hora";
        if (hours < 24) return "hace " + hours + " horas";
        long days = hours / 24;
        if (days == 1) return "ayer";
        if (days < 7) return "hace " + days + " días";
        long weeks = days / 7;
        if (weeks == 1) return "hace 1 semana";
        return "hace " + weeks + " semanas";
    }
}
