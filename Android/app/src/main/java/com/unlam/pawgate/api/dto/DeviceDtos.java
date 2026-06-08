package com.unlam.pawgate.api.dto;

import java.util.List;
import java.util.Map;

/**
 * DTOs para los endpoints /devices/*.
 */
public final class DeviceDtos {

    private DeviceDtos() {}

    // ===== GET /devices/{id}/history =====

    public static final class HistoryResponse {
        public String device_id;
        public long from;
        public long to;
        public int count;
        public List<Event> events;
        /** Si != null, hay mas paginas. Pasar este valor en ?cursor= para la siguiente. */
        public String next_cursor;
    }

    /** Un row de pawgate_events tal como viene de DDB serializado. */
    public static final class Event {
        public String device_id;
        public String ts_event;     // "1780347796470#door#opened"
        public String type;          // "door" | "sensor"
        public String event_type;    // "opened" | "closed" | "blocked" | ...
        /** Solo presente en eventos type=door (opened/closed):
         *  "in"  -> abrio hacia adentro (alguien entro a la casa, ej RFID)
         *  "out" -> abrio hacia afuera (alguien salio al patio, ej proximity) */
        public String direction;
        public Map<String, Object> payload;
        public long ttl_epoch;
        public String created_at;    // ISO 8601
    }

    // ===== POST /devices/{id}/cmd/{cmd} =====

    public static final class CommandResponse {
        public boolean queued;
        public String topic;
        public Map<String, Object> payload;
    }

    // ===== GET /devices/{id}/metrics/today =====

    public static final class MetricsTodayResponse {
        public String device_id;
        public long from_ms;
        public long to_ms;
        public int openings_today;
        public String last_door_event_at;        // ISO 8601 (nullable)
        public String last_door_event_type;      // opened/closed/blocked/...
        public String last_door_event_direction; // in/out (solo opened/closed)
    }

    // ===== GET /devices/{id}/info (telemetria del ESP32) =====

    public static final class DeviceInfoResponse {
        public String device_id;
        public boolean online;
        public String info_updated_at;     // ISO 8601 del ultimo telemetry recibido
        public long uptime_s;
        public int rssi_dbm;
        public int free_heap_kb;
        public int total_heap_kb;
        public int flash_used_kb;
        public int flash_total_kb;
        public String cpu_temp_c;          // string para no perder precision decimal
        public String local_ip;
        public String firmware_version;
        public String hardware_model;
        // W14 - WiFi info
        public String wifi_ssid;
        public String wifi_bssid;
        public String wifi_band;
        public String wifi_gateway;
        public String wifi_security;
    }
}
