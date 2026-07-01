package com.unlam.pawgate.api;

import com.unlam.pawgate.api.dto.AuthDtos;
import com.unlam.pawgate.api.dto.DeviceDtos;
import com.unlam.pawgate.api.dto.NotificationDtos;
import com.unlam.pawgate.api.dto.ScheduleDtos;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Interface de Retrofit que describe la API REST de PawGate.
 *
 * Cada metodo retorna un Call<T>. Para ejecutar:
 *   - call.enqueue(callback)   -> asincrono, callback en main thread (Android)
 *   - call.execute()           -> sincrono, NO usar en main thread
 *
 * El AuthInterceptor agrega Authorization: Bearer <idToken> a todos los requests
 * EXCEPT los de /auth/* (login/signup/confirm son publicos).
 */
public interface PawGateApi {

    // ===== Auth (publicos) =====

    @POST("auth/signup")
    Call<AuthDtos.SignupResponse> signup(@Body AuthDtos.SignupRequest body);

    @POST("auth/confirm")
    Call<AuthDtos.ConfirmResponse> confirm(@Body AuthDtos.ConfirmRequest body);

    @POST("auth/login")
    Call<AuthDtos.LoginResponse> login(@Body AuthDtos.LoginRequest body);

    @POST("auth/refresh")
    Call<AuthDtos.RefreshTokenResponse> refresh(@Body AuthDtos.RefreshTokenRequest body);

    // ===== Device events + cmds (requieren JWT) =====

    @GET("devices/{id}/history")
    Call<DeviceDtos.HistoryResponse> getHistory(
            @Path("id") String deviceId,
            @Query("from") Long fromMs,
            @Query("to") Long toMs,
            @Query("include_sensors") Boolean includeSensors,
            @Query("cursor") String cursor);

    @POST("devices/{id}/cmd/{cmd}")
    Call<DeviceDtos.CommandResponse> sendCommand(
            @Path("id") String deviceId,
            @Path("cmd") String cmd,
            @Body Map<String, Object> body);

    @GET("devices/{id}/metrics/today")
    Call<DeviceDtos.MetricsTodayResponse> getMetricsToday(@Path("id") String deviceId);

    @GET("devices/{id}/info")
    Call<DeviceDtos.DeviceInfoResponse> getDeviceInfo(@Path("id") String deviceId);

    @GET("devices/{id}/firmware/latest")
    Call<DeviceDtos.FirmwareLatestResponse> getFirmwareLatest(@Path("id") String deviceId);

    // ===== FCM token (Fase 20) =====

    @POST("users/me/fcm-token")
    Call<DeviceDtos.RegisterFcmTokenResponse> registerFcmToken(
            @Body DeviceDtos.RegisterFcmTokenRequest body);

    @DELETE("users/me/fcm-token")
    Call<Void> unregisterFcmToken();

    // ===== Notifications (bandeja persistente) =====

    @GET("users/me/notifications")
    Call<NotificationDtos.ListResponse> getNotifications(
            @Query("limit") Integer limit,
            @Query("onlyUnread") Boolean onlyUnread);

    @GET("users/me/notifications/unread-count")
    Call<NotificationDtos.UnreadCountResponse> getUnreadCount();

    @POST("users/me/notifications/read")
    Call<NotificationDtos.MarkReadResponse> markAllNotificationsRead();

    @POST("users/me/notifications/{notif_id}/read")
    Call<NotificationDtos.MarkReadResponse> markNotificationRead(
            @Path("notif_id") String notifId);

    // ===== Schedules CRUD =====

    @GET("devices/{id}/schedules")
    Call<ScheduleDtos.ListResponse> getSchedules(@Path("id") String deviceId);

    @POST("devices/{id}/schedules")
    Call<ScheduleDtos.Schedule> createSchedule(
            @Path("id") String deviceId,
            @Body ScheduleDtos.CreateRequest body);

    @PUT("devices/{id}/schedules/{schedule_id}")
    Call<ScheduleDtos.Schedule> updateSchedule(
            @Path("id") String deviceId,
            @Path("schedule_id") String scheduleId,
            @Body ScheduleDtos.CreateRequest body);

    @DELETE("devices/{id}/schedules/{schedule_id}")
    Call<Void> deleteSchedule(
            @Path("id") String deviceId,
            @Path("schedule_id") String scheduleId);

    // ===== Device state + override =====

    @GET("devices/{id}/state")
    Call<ScheduleDtos.DeviceStateResponse> getDeviceState(@Path("id") String deviceId);

    @POST("devices/{id}/state/override-unblock")
    Call<ScheduleDtos.OverrideUnblockResponse> overrideUnblock(
            @Path("id") String deviceId,
            @Body Map<String, Object> body);

    @POST("devices/{id}/state/override-block")
    Call<ScheduleDtos.OverrideUnblockResponse> overrideBlock(
            @Path("id") String deviceId,
            @Body Map<String, Object> body);
}
