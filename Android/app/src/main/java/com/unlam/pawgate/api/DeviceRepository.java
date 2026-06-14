package com.unlam.pawgate.api;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

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
    private static final String GET_SCHEDULE_ERROR_LOG = "getSchedules network error";
    private static final String GET_SCHEDULE_FALLBACK_MSG = "No se pudieron cargar los horarios";
    private static final String CREATE_SCHEDULE_ERROR_LOG = "createSchedule network error";
    private static final String CREATE_SCHEDULE_FALLBACK_MSG = "No se pudo crear el horario";
    private static final String UPDATE_SCHEDULE_ERROR_LOG = "updateSchedule network error";
    private static final String UPDATE_SCHEDULE_FALLBACK_MSG = "No se pudo actualizar el horario";
    private static final String GET_DEVICE_STATE_ERROR_LOG = "getDeviceState network error";
    private static final String GET_DEVICE_STATE_FALLBACK_MSG = "No se pudo obtener el estado";
    private static final String OVERRIDE_UNBLOCK_STATE_ERROR_LOG = "overrideUnblock network error";
    private static final String OVERRIDE_UNBLOCK_STATE_FALLBACK_MSG = "No se pudo desbloquear";
    private static final String OVERRIDE_BLOCK_STATE_ERROR_LOG = "overrideBlock network error";
    private static final String OVERRIDE_BLOCK_STATE_FALLBACK_MSG = "No se pudo bloquear";
    private static final String SEND_COMMAND_ERROR_LOG = "sendCommand network error";
    private static final String SEND_COMMAND_FALLBACK_MSG = "No se pudo enviar el comando \"%s\"";

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
        history(deviceId, null, null, cb);
    }

    /**
     * Trae el historial con rango opcional. Pasar null en from/to si no aplica.
     * El backend interpreta from/to como epoch ms.
     */
    public void history(String deviceId, Long fromMs, Long toMs,
                        ApiCallback<DeviceDtos.HistoryResponse> cb) {
        api.getHistory(deviceId, fromMs, toMs).enqueue(new Callback<DeviceDtos.HistoryResponse>() {
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
    public void sendCommand(String deviceId, String cmd, ApiCallback<DeviceDtos.CommandResponse> cb) {
        // Body vacio - el backend solo mira el path por ahora
        Map<String, Object> body = Collections.emptyMap();
        enqueue(
                api.sendCommand(deviceId, cmd, body),
                SEND_COMMAND_ERROR_LOG,
                String.format(SEND_COMMAND_FALLBACK_MSG, cmd),
                cb
        );
    }

    // ============================================================
    // SCHEDULES CRUD
    // ============================================================

    public void getSchedules(String deviceId, ApiCallback<ScheduleDtos.ListResponse> cb) {
        enqueue(api.getSchedules(deviceId), GET_SCHEDULE_ERROR_LOG, GET_SCHEDULE_FALLBACK_MSG, cb);
    }

    public void createSchedule(String deviceId, ScheduleDtos.CreateRequest body, ApiCallback<ScheduleDtos.Schedule> cb) {
        enqueue(api.createSchedule(deviceId, body), CREATE_SCHEDULE_ERROR_LOG, CREATE_SCHEDULE_FALLBACK_MSG, cb);
    }

    public void updateSchedule(String deviceId, String scheduleId, ScheduleDtos.CreateRequest body,
                                ApiCallback<ScheduleDtos.Schedule> cb) {
        enqueue(api.updateSchedule(deviceId, scheduleId, body), UPDATE_SCHEDULE_ERROR_LOG, UPDATE_SCHEDULE_FALLBACK_MSG, cb);
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

    public void getDeviceState(String deviceId, ApiCallback<ScheduleDtos.DeviceStateResponse> cb) {
        enqueue(api.getDeviceState(deviceId), GET_DEVICE_STATE_ERROR_LOG, GET_DEVICE_STATE_FALLBACK_MSG, cb);
    }

    public void overrideUnblock(String deviceId, ApiCallback<ScheduleDtos.OverrideUnblockResponse> cb) {
        enqueue(
                api.overrideUnblock(deviceId, Collections.emptyMap()),
                OVERRIDE_UNBLOCK_STATE_ERROR_LOG,
                OVERRIDE_UNBLOCK_STATE_FALLBACK_MSG,
                cb
        );
    }

    public void overrideBlock(String deviceId, ApiCallback<ScheduleDtos.OverrideUnblockResponse> cb) {
        enqueue(
                api.overrideBlock(deviceId, Collections.emptyMap()),
                OVERRIDE_BLOCK_STATE_ERROR_LOG,
                OVERRIDE_BLOCK_STATE_FALLBACK_MSG,
                cb
        );
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

    private <T> void enqueue(Call<T> call, String errorLog, String fallbackMessage, ApiCallback<T> cb) {
        call.enqueue(new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> call, @NonNull Response<T> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), fallbackMessage));
                }
            }

            @Override
            public void onFailure(@NonNull Call<T> call, @NonNull Throwable t) {
                Log.e(TAG, errorLog, t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }
}
