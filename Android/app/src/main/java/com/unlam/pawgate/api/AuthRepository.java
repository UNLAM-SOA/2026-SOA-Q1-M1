package com.unlam.pawgate.api;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.unlam.pawgate.PrefsHelper;
import com.unlam.pawgate.api.dto.AuthDtos;

import java.io.IOException;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Repository que encapsula las llamadas de auth (signup / confirm / login)
 * y la persistencia de tokens en PrefsHelper.
 *
 * Patron Repository: la UI (Activities) no conoce Retrofit, solo conoce este
 * objeto. Si en el futuro cambiamos Retrofit por Ktor o por gRPC, solo
 * cambia esta clase.
 *
 * Threading:
 *   - Las llamadas a .enqueue de Retrofit corren en un thread interno de OkHttp.
 *   - Los callbacks (onResponse / onFailure) corren en el MAIN thread de Android
 *     automaticamente (lo maneja el Executor por defecto del Retrofit en Android).
 *   - Por eso podes llamar PrefsHelper.setTokens() en onSuccess sin problemas.
 */
public class AuthRepository {

    private static final String TAG = "AuthRepository";
    private static final Gson gson = new Gson();

    private final Context appContext;
    private final PawGateApi api;

    public AuthRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.api = ApiClient.get(this.appContext);
    }

    // ============================================================
    // SIGNUP
    // ============================================================

    public void signup(String email, String password, String name, ApiCallback<AuthDtos.SignupResponse> cb) {
        AuthDtos.SignupRequest req = new AuthDtos.SignupRequest(email, password, name);
        api.signup(req).enqueue(new Callback<AuthDtos.SignupResponse>() {
            @Override
            public void onResponse(Call<AuthDtos.SignupResponse> call, Response<AuthDtos.SignupResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "Error al registrarte"));
                }
            }
            @Override
            public void onFailure(Call<AuthDtos.SignupResponse> call, Throwable t) {
                Log.e(TAG, "signup network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // CONFIRM (codigo del email)
    // ============================================================

    public void confirm(String email, String code, ApiCallback<AuthDtos.ConfirmResponse> cb) {
        AuthDtos.ConfirmRequest req = new AuthDtos.ConfirmRequest(email, code);
        api.confirm(req).enqueue(new Callback<AuthDtos.ConfirmResponse>() {
            @Override
            public void onResponse(Call<AuthDtos.ConfirmResponse> call, Response<AuthDtos.ConfirmResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    cb.onSuccess(response.body());
                } else {
                    cb.onError(parseError(response.errorBody(), "Error al confirmar"));
                }
            }
            @Override
            public void onFailure(Call<AuthDtos.ConfirmResponse> call, Throwable t) {
                Log.e(TAG, "confirm network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // LOGIN
    // ============================================================

    public void login(String email, String password, ApiCallback<AuthDtos.LoginResponse> cb) {
        AuthDtos.LoginRequest req = new AuthDtos.LoginRequest(email, password);
        api.login(req).enqueue(new Callback<AuthDtos.LoginResponse>() {
            @Override
            public void onResponse(Call<AuthDtos.LoginResponse> call, Response<AuthDtos.LoginResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    AuthDtos.LoginResponse body = response.body();
                    // Persistir tokens + email para auto-login en siguientes sessions
                    PrefsHelper.setTokens(appContext,
                            body.idToken,
                            body.accessToken,
                            body.refreshToken,
                            body.expiresIn);
                    PrefsHelper.setUserEmail(appContext, email);
                    cb.onSuccess(body);
                } else {
                    cb.onError(parseError(response.errorBody(), "Email o contraseña incorrectos"));
                }
            }
            @Override
            public void onFailure(Call<AuthDtos.LoginResponse> call, Throwable t) {
                Log.e(TAG, "login network error", t);
                cb.onError(networkErrorMessage(t));
            }
        });
    }

    // ============================================================
    // LOGOUT
    // ============================================================

    public void logout() {
        PrefsHelper.clearAuth(appContext);
    }

    // ============================================================
    // HELPERS DE ERROR
    // ============================================================

    /** Parsea el body de error si llego en formato JSON {"error": "..."}. */
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

    /** Mensaje de error de red, traduciendo cosas comunes a algo human-friendly. */
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
