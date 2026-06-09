package com.unlam.pawgate.api;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.unlam.pawgate.api.dto.AuthDtos;
import com.unlam.pawgate.api.dto.NotificationDtos;

import java.io.IOException;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Repository para los endpoints /users/me/notifications/*.
 *
 * <p>Mismo patron que DeviceRepository y AuthRepository: la UI llama metodos
 * limpios, no toca Retrofit.
 *
 * <p>Operaciones:
 * <ul>
 *     <li>{@link #list(Integer, Boolean, ApiCallback)} — bandeja paginada</li>
 *     <li>{@link #unreadCount(ApiCallback)} — conteo para el badge del bell</li>
 *     <li>{@link #markAllRead(ApiCallback)} — boton "Leer todo"</li>
 *     <li>{@link #markRead(String, ApiCallback)} — tap individual sobre item</li>
 * </ul>
 */
public class NotificationRepository {

    private static final String TAG = "NotificationRepo";
    private static final Gson gson = new Gson();

    private final PawGateApi api;

    public NotificationRepository(Context context) {
        this.api = ApiClient.get(context.getApplicationContext());
    }

    /** GET /users/me/notifications?limit=&onlyUnread= */
    public void list(Integer limit, Boolean onlyUnread,
                     ApiCallback<NotificationDtos.ListResponse> cb) {
        api.getNotifications(limit, onlyUnread).enqueue(new Callback<NotificationDtos.ListResponse>() {
            @Override public void onResponse(Call<NotificationDtos.ListResponse> call,
                                             Response<NotificationDtos.ListResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "No se pudieron cargar las notificaciones"));
                }
            }
            @Override public void onFailure(Call<NotificationDtos.ListResponse> call, Throwable t) {
                Log.e(TAG, "list network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    /** GET /users/me/notifications/unread-count */
    public void unreadCount(ApiCallback<NotificationDtos.UnreadCountResponse> cb) {
        api.getUnreadCount().enqueue(new Callback<NotificationDtos.UnreadCountResponse>() {
            @Override public void onResponse(Call<NotificationDtos.UnreadCountResponse> call,
                                             Response<NotificationDtos.UnreadCountResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "No se pudo obtener el conteo"));
                }
            }
            @Override public void onFailure(Call<NotificationDtos.UnreadCountResponse> call, Throwable t) {
                Log.e(TAG, "unreadCount network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    /** POST /users/me/notifications/read */
    public void markAllRead(ApiCallback<NotificationDtos.MarkReadResponse> cb) {
        api.markAllNotificationsRead().enqueue(new Callback<NotificationDtos.MarkReadResponse>() {
            @Override public void onResponse(Call<NotificationDtos.MarkReadResponse> call,
                                             Response<NotificationDtos.MarkReadResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "No se pudo marcar como leído"));
                }
            }
            @Override public void onFailure(Call<NotificationDtos.MarkReadResponse> call, Throwable t) {
                Log.e(TAG, "markAllRead network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    /** POST /users/me/notifications/{id}/read */
    public void markRead(String notifId,
                          ApiCallback<NotificationDtos.MarkReadResponse> cb) {
        api.markNotificationRead(notifId).enqueue(new Callback<NotificationDtos.MarkReadResponse>() {
            @Override public void onResponse(Call<NotificationDtos.MarkReadResponse> call,
                                             Response<NotificationDtos.MarkReadResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "No se pudo marcar como leído"));
                }
            }
            @Override public void onFailure(Call<NotificationDtos.MarkReadResponse> call, Throwable t) {
                Log.e(TAG, "markRead network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    private String parseError(ResponseBody errorBody, String fallback) {
        if (errorBody == null) return fallback;
        try {
            String raw = errorBody.string();
            AuthDtos.ApiError err = gson.fromJson(raw, AuthDtos.ApiError.class);
            if (err != null && err.error != null) return err.error;
            return fallback;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    private String networkErrorMessage(Throwable t) {
        if (t == null) return "Error de conexión";
        String msg = t.getMessage();
        if (msg == null) return "Error de conexión";
        if (msg.contains("Unable to resolve host")) return "Sin conexión a internet";
        if (msg.contains("timeout")) return "El servidor no responde, reintentá en unos segundos";
        return "Error de red: " + msg;
    }
}
