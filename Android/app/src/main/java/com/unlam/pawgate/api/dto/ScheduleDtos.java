package com.unlam.pawgate.api.dto;

import java.util.List;

/**
 * DTOs para los endpoints /devices/{id}/schedules y /devices/{id}/state.
 */
public final class ScheduleDtos {

    private ScheduleDtos() {}

    // ===== GET /devices/{id}/schedules =====

    public static final class ListResponse {
        public String device_id;
        public List<Schedule> schedules;
    }

    /** Representacion del schedule tal como esta en pawgate_schedules. */
    public static final class Schedule {
        public String device_id;
        public String schedule_id;
        public String nombre;
        public String hora_inicio;   // "HH:MM"
        public String hora_fin;
        public List<String> dias;    // chars en {L,M,X,J,V,S,D}
        public boolean activo;
        public String created_at;
        public String updated_at;
    }

    /** Body del POST /schedules y del PUT /schedules/{id}. */
    public static final class CreateRequest {
        public String nombre;
        public String hora_inicio;
        public String hora_fin;
        public List<String> dias;
        public boolean activo;

        public CreateRequest(String nombre, String hora_inicio, String hora_fin,
                             List<String> dias, boolean activo) {
            this.nombre = nombre;
            this.hora_inicio = hora_inicio;
            this.hora_fin = hora_fin;
            this.dias = dias;
            this.activo = activo;
        }
    }

    // ===== GET /devices/{id}/state =====

    public static final class DeviceStateResponse {
        public String device_id;
        public String lock_state;            // AUTO_BLOCKED | AUTO_UNBLOCKED | MANUAL_UNBLOCKED
        public String updated_at;
        public boolean currently_in_horario;
    }

    // ===== POST /devices/{id}/state/override-unblock =====

    public static final class OverrideUnblockResponse {
        public String lock_state;
        public String topic;
    }
}
