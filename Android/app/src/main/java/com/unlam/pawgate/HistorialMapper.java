package com.unlam.pawgate;

import android.text.format.DateUtils;

import com.unlam.pawgate.api.dto.DeviceDtos;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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

    // Formato ISO 8601 con milisegundos y Z (UTC). El backend usa este formato
    // exacto en created_at. Si en el futuro cambia el formato, hay que ajustarlo aca.
    private static final SimpleDateFormat ISO_FORMAT;
    static {
        ISO_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        ISO_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

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
        String titulo = titleFor(e.event_type);
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
    // Titulo human-readable por tipo de evento
    // ============================================================
    private static String titleFor(String eventType) {
        if (eventType == null) return "Evento";
        switch (eventType) {
            case "opened":           return "Puerta abierta";
            case "closed":           return "Puerta cerrada";
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
     * Util para los call-sites que necesitan el timestamp crudo (ej: Dashboard
     * para decidir si el evento es "reciente" o no).
     */
    public static long parseIsoToMs(String createdAtIso) {
        if (createdAtIso == null || createdAtIso.isEmpty()) return -1L;
        try {
            Date d;
            synchronized (ISO_FORMAT) {
                d = ISO_FORMAT.parse(createdAtIso);
            }
            return d != null ? d.getTime() : -1L;
        } catch (ParseException ex) {
            return -1L;
        }
    }

    /** Helper publico para que call-sites externos formateen un ISO como "hace 2m". */
    public static String relativeTimeFor(String createdAtIso, long nowMs) {
        return formatRelativeTime(createdAtIso, nowMs);
    }

    // ============================================================
    // Subtitulo: "hace 2m" / "ayer 21:14" / etc.
    // ============================================================
    private static String formatRelativeTime(String createdAtIso, long nowMs) {
        if (createdAtIso == null || createdAtIso.isEmpty()) return "";
        try {
            Date eventDate;
            synchronized (ISO_FORMAT) { // SimpleDateFormat NO es thread-safe
                eventDate = ISO_FORMAT.parse(createdAtIso);
            }
            if (eventDate == null) return createdAtIso;

            long eventMs = eventDate.getTime();
            CharSequence rel = DateUtils.getRelativeTimeSpanString(
                    eventMs,
                    nowMs,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            return rel.toString();
        } catch (ParseException ex) {
            // Si el formato cambio, fallback al string raw
            return createdAtIso;
        }
    }
}
