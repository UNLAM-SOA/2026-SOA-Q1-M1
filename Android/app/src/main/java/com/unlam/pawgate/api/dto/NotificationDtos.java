package com.unlam.pawgate.api.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * DTOs para los endpoints de notificaciones persistentes en
 * pawgate_notifications.
 *
 * <p>Endpoints involucrados:
 * <ul>
 *     <li>GET  /users/me/notifications?limit=&onlyUnread=</li>
 *     <li>GET  /users/me/notifications/unread-count</li>
 *     <li>POST /users/me/notifications/read</li>
 *     <li>POST /users/me/notifications/{notif_id}/read</li>
 * </ul>
 *
 * El item de DDB tiene:
 *  - notif_id    sort key (timestamp invertido para orden DESC natural)
 *  - type        opened / closed / blocked / door_opened_manual / ...
 *  - direction   in / out (opcional)
 *  - title, body texto a mostrar
 *  - device_id   "pawgate-001"
 *  - actor       quien lo hizo (email del user, o "system" para events del ESP32)
 *  - read        bool
 *  - created_at  ISO 8601
 */
public final class NotificationDtos {

    private NotificationDtos() {}

    public static class NotificationItem {
        @SerializedName("notif_id")   public String notifId;
        @SerializedName("type")       public String type;
        @SerializedName("title")      public String title;
        @SerializedName("body")       public String body;
        @SerializedName("device_id")  public String deviceId;
        @SerializedName("direction")  public String direction;
        @SerializedName("actor")      public String actor;
        @SerializedName("read")       public boolean read;
        @SerializedName("created_at") public String createdAt;
    }

    public static class ListResponse {
        @SerializedName("items") public List<NotificationItem> items;
    }

    public static class UnreadCountResponse {
        @SerializedName("unread") public int unread;
    }

    public static class MarkReadResponse {
        @SerializedName("ok")      public Boolean ok;
        @SerializedName("updated") public Integer updated; // cuando es mark-all
    }
}
