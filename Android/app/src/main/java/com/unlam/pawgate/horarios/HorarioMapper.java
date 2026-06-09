package com.unlam.pawgate.horarios;

import com.unlam.pawgate.api.dto.ScheduleDtos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Conversiones entre el DTO ScheduleDtos.Schedule (lo que sale del API) y el
 * modelo Horario (lo que usa la UI).
 *
 * El backend guarda los dias como lista de chars ["L","M","X","J","V","S","D"]
 * y la app usa un bitmask para facilitar toggle/check. Aca traducimos.
 */
public final class HorarioMapper {

    private HorarioMapper() {}

    private static final String[] DAY_CHARS = {"L", "M", "X", "J", "V", "S", "D"};
    private static final int[] DAY_BITS = {
            Horario.LUN, Horario.MAR, Horario.MIE, Horario.JUE,
            Horario.VIE, Horario.SAB, Horario.DOM
    };

    public static Horario fromDto(ScheduleDtos.Schedule dto) {
        Horario h = new Horario();
        h.id = dto.schedule_id;
        h.nombre = dto.nombre != null ? dto.nombre : "";
        int[] hi = parseHoraToInts(dto.hora_inicio);
        int[] hf = parseHoraToInts(dto.hora_fin);
        h.horaInicio = hi[0]; h.minutoInicio = hi[1];
        h.horaFin = hf[0];    h.minutoFin = hf[1];
        h.diasBitmask = listaToBitmask(dto.dias);
        h.activo = dto.activo;
        return h;
    }

    public static List<Horario> fromDtos(List<ScheduleDtos.Schedule> dtos) {
        List<Horario> out = new ArrayList<>();
        if (dtos == null) return out;
        for (ScheduleDtos.Schedule s : dtos) out.add(fromDto(s));
        return out;
    }

    public static ScheduleDtos.CreateRequest toCreateRequest(Horario h) {
        return new ScheduleDtos.CreateRequest(
                h.nombre,
                h.formatHoraInicio(),
                h.formatHoraFin(),
                bitmaskToLista(h.diasBitmask),
                h.activo);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static int[] parseHoraToInts(String hhmm) {
        if (hhmm == null) return new int[]{0, 0};
        try {
            String[] parts = hhmm.split(":");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private static int listaToBitmask(List<String> dias) {
        if (dias == null) return 0;
        int mask = 0;
        for (String d : dias) {
            for (int i = 0; i < DAY_CHARS.length; i++) {
                if (DAY_CHARS[i].equals(d)) {
                    mask |= DAY_BITS[i];
                    break;
                }
            }
        }
        return mask;
    }

    private static List<String> bitmaskToLista(int mask) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < DAY_BITS.length; i++) {
            if ((mask & DAY_BITS[i]) != 0) out.add(DAY_CHARS[i]);
        }
        return out;
    }

    /** Util para el seed inicial de UI / tests. */
    public static List<String> charsToList(String... chars) {
        return Arrays.asList(chars);
    }
}
