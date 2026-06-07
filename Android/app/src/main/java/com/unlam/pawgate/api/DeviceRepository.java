package com.unlam.pawgate.api;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.unlam.pawgate.api.dto.AuthDtos;
import com.unlam.pawgate.api.dto.DeviceDtos;
import com.unlam.pawgate.api.dto.ScheduleDtos;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Repository para los endpoints /devices/{id}/*.
 *
 * Mismo patron que AuthRepository: la UI llama metodos limpios, no toca Retrofit.
 *
 * Comandos validos por contrato del backend:
 *   "open" | "block" | "unblock" | "call"
 *
 * El body de POST /cmd va vacio por ahora (el backend solo necesita el path).
 * Si en el futuro queremos parametrizar (ej: open con "duration_s": 10),
 * se pasa por el Map.
 */
public class DeviceRepository {

    private static final String TAG = "DeviceRepository";
    private static final Gson gson = new Gson();

    /** Comandos validos. Constantes para no tipear el string suelto en cada Activity. */
    public static final String CMD_OPEN = "open";
    public static final String CMD_BLOCK = "block";
    public static final String CMD_UNBLOCK = "unblock";
    public static final String CMD_CALL = "call";
    public static final String CMD_CANCEL = "cancel";

    private final Context appContext;
    private final PawGateApi api;

    public DeviceRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.api = ApiClient.get(this.appContext);
    }

    // ============================================================
    // GET /devices/{id}/history
    // ============================================================

    /**
     * Trae el historial completo (sin rango). Para filtros temporales,
     * usar el overload con fromMs/toMs.
     */
    public void history(String deviceId, ApiCallback<DeviceDtos.HistoryResponse> cb) {
        history(deviceId, null, null, null, cb);
    }

    /** Overload sin includeSensors (default backend: false). */
    public void history(String deviceId, Long fromMs, Long toMs,
                        ApiCallback<DeviceDtos.HistoryResponse> cb) {
        history(deviceId, fromMs, toMs, null, cb);
    }

    /**
     * Trae el historial con rango y filtro de sensores opcionales.
     * Si includeSensors=null o false, el backend oculta los sensors (default).
     */
    public void history(String deviceId, Long fromMs, Long toMs, Boolean includeSensors,
                        ApiCallback<DeviceDtos.HistoryResponse> cb) {
        api.getHistory(deviceId, fromMs, toMs, includeSensors).enqueue(new Callback<DeviceDtos.HistoryResponse>() {
            @Override
            public void onResponse(Call<DeviceDtos.HistoryResponse> call,
                                   Response<DeviceDtos.HistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "No se pudo cargar el historial"));
                }
            }
            @Override
            public void onFailure(Call<DeviceDtos.HistoryResponse> call, Throwable t) {
                Log.e(TAG, "history network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // POST /devices/{id}/cmd/{cmd}
    // ============================================================

    /**
     * Envia un comando al device. El backend valida que el cmd sea uno de los
     * conocidos y publica al topic MQTT pawgate/{id}/cmd/{cmd}.
     *
     * UX optimista: la Activity ya cambio el estado local antes de llamar a esto.
     * Si onError, mostramos toast pero NO revertimos el estado local (el polling
     * de /history terminara conciliando si el simulador realmente no recibio el cmd).
     */
    public void sendCommand(String deviceId, String cmd,
                            ApiCallback<DeviceDtos.CommandResponse> cb) {
        sendCommand(deviceId, cmd, Collections.emptyMap(), cb);
    }

    /** Variante con body parametrizado. Usado por ej. para cmd=open con
     *  {"direction": "in"|"out"}. */
    public void sendCommand(String deviceId, String cmd, Map<String, Object> body,
                            ApiCallback<DeviceDtos.CommandResponse> cb) {
        api.sendCommand(deviceId, cmd, body).enqueue(new Callback<DeviceDtos.CommandResponse>() {
            @Override
            public void onResponse(Call<DeviceDtos.CommandResponse> call,
                                   Response<DeviceDtos.CommandResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(),
                            "No se pudo enviar el comando \"" + cmd + "\""));
                }
            }
            @Override
            public void onFailure(Call<DeviceDtos.CommandResponse> call, Throwable t) {
                Log.e(TAG, "sendCommand network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // SCHEDULES CRUD
    // ============================================================

    public void getSchedules(String deviceId, ApiCallback<ScheduleDtos.ListResponse> cb) {
        api.getSchedules(deviceId).enqueue(new Callback<ScheduleDtos.ListResponse>() {
            @Override public void onResponse(Call<ScheduleDtos.ListResponse> call,
                                             Response<ScheduleDtos.ListResponse> response) {
                if (response.isSuccessful() && response.body() != null) cb.onSuccess(response.body());
                else cb.onError(parseError(response.errorBody(), "No se pudieron cargar los horarios"));
            }
            @Override public void onFailure(Call<ScheduleDtos.ListResponse> call, Throwable t) {
                Log.e(TAG, "getSchedules network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    public void createSchedule(String deviceId, ScheduleDtos.CreateRequest body,
                                ApiCallback<ScheduleDtos.Schedule> cb) {
        api.createSchedule(deviceId, body).enqueue(new Callback<ScheduleDtos.Schedule>() {
            @Override public void onResponse(Call<ScheduleDtos.Schedule> call,
                                             Response<ScheduleDtos.Schedule> response) {
                if (response.isSuccessful() && response.body() != null) cb.onSuccess(response.body());
                else cb.onError(parseError(response.errorBody(), "No se pudo crear el horario"));
            }
            @Override public void onFailure(Call<ScheduleDtos.Schedule> call, Throwable t) {
                Log.e(TAG, "createSchedule network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    public void updateSchedule(String deviceId, String scheduleId, ScheduleDtos.CreateRequest body,
                                ApiCallback<ScheduleDtos.Schedule> cb) {
        api.updateSchedule(deviceId, scheduleId, body).enqueue(new Callback<ScheduleDtos.Schedule>() {
            @Override public void onResponse(Call<ScheduleDtos.Schedule> call,
                                             Response<ScheduleDtos.Schedule> response) {
                if (response.isSuccessful() && response.body() != null) cb.onSuccess(response.body());
                else cb.onError(parseError(response.errorBody(), "No se pudo actualizar el horario"));
            }
            @Override public void onFailure(Call<ScheduleDtos.Schedule> call, Throwable t) {
                Log.e(TAG, "updateSchedule network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    public void deleteSchedule(String deviceId, String scheduleId, ApiCallback<Void> cb) {
        api.deleteSchedule(deviceId, scheduleId).enqueue(new Callback<Void>() {
            @Override public void onResponse(Call<Void> call, Response<Void> response) {
                if (response.isSuccessful()) cb.onSuccess(null);
                else cb.onError(parseError(response.errorBody(), "No se pudo eliminar el horario"));
            }
            @Override public void onFailure(Call<Void> call, Throwable t) {
                Log.e(TAG, "deleteSchedule network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // DEVICE STATE + OVERRIDE
    // ============================================================

    public void getDeviceState(String deviceId,
                               ApiCallback<ScheduleDtos.DeviceStateResponse> cb) {
        api.getDeviceState(deviceId).enqueue(new Callback<ScheduleDtos.DeviceStateResponse>() {
            @Override public void onResponse(Call<ScheduleDtos.DeviceStateResponse> call,
                                             Response<ScheduleDtos.DeviceStateResponse> response) {
                if (response.isSuccessful() && response.body() != null) cb.onSuccess(response.body());
                else cb.onError(parseError(response.errorBody(), "No se pudo obtener el estado"));
            }
            @Override public void onFailure(Call<ScheduleDtos.DeviceStateResponse> call, Throwable t) {
                Log.e(TAG, "getDeviceState network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    public void overrideUnblock(String deviceId,
                                 ApiCallback<ScheduleDtos.OverrideUnblockResponse> cb) {
        api.overrideUnblock(deviceId, Collections.emptyMap())
                .enqueue(new Callback<ScheduleDtos.OverrideUnblockResponse>() {
            @Override public void onResponse(Call<ScheduleDtos.OverrideUnblockResponse> call,
                                             Response<ScheduleDtos.OverrideUnblockResponse> response) {
                if (response.isSuccessful() && response.body() != null) cb.onSuccess(response.body());
                else cb.onError(parseError(response.errorBody(), "No se pudo desbloquear"));
            }
            @Override public void onFailure(Call<ScheduleDtos.OverrideUnblockResponse> call, Throwable t) {
                Log.e(TAG, "overrideUnblock network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    public void overrideBlock(String deviceId,
                               ApiCallback<ScheduleDtos.OverrideUnblockResponse> cb) {
        api.overrideBlock(deviceId, Collections.emptyMap())
                .enqueue(new Callback<ScheduleDtos.OverrideUnblockResponse>() {
            @Override public void onResponse(Call<ScheduleDtos.OverrideUnblockResponse> call,
                                             Response<ScheduleDtos.OverrideUnblockResponse> response) {
                if (response.isSuccessful() && response.body() != null) cb.onSuccess(response.body());
                else cb.onError(parseError(response.errorBody(), "No se pudo bloquear"));
            }
            @Override public void onFailure(Call<ScheduleDtos.OverrideUnblockResponse> call, Throwable t) {
                Log.e(TAG, "overrideBlock network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // HELPERS DE ERROR (mismos que AuthRepository - en una proxima refactor
    // movemos esto a ApiErrors o a una clase base)
    // ============================================================

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
        if (msg.contains("Unable to resolve host")) {
            return "Sin conexión a internet";
        }
        if (msg.contains("timeout")) {
            return "El servidor no responde, reintentá en unos segundos";
        }
        return "Error de red: " + msg;
    }
}
