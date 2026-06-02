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
    }

    /** Un row de pawgate_events tal como viene de DDB serializado. */
    public static final class Event {
        public String device_id;
        public String ts_event;     // "1780347796470#door#opened"
        public String type;          // "door" | "sensor"
        public String event_type;    // "opened" | "closed" | "blocked" | ...
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
}
