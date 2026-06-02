package com.unlam.pawgate.api;

import com.unlam.pawgate.api.dto.AuthDtos;
import com.unlam.pawgate.api.dto.DeviceDtos;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
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

    // ===== Device (requieren JWT) =====

    @GET("devices/{id}/history")
    Call<DeviceDtos.HistoryResponse> getHistory(
            @Path("id") String deviceId,
            @Query("from") Long fromMs,
            @Query("to") Long toMs);

    @POST("devices/{id}/cmd/{cmd}")
    Call<DeviceDtos.CommandResponse> sendCommand(
            @Path("id") String deviceId,
            @Path("cmd") String cmd,
            @Body Map<String, Object> body);
}
