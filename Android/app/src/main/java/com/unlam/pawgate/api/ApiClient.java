package com.unlam.pawgate.api;

import android.content.Context;

import com.unlam.pawgate.R;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton de Retrofit. Construye el cliente HTTP UNA sola vez y lo reusa.
 *
 * Por que singleton:
 *   - OkHttp tiene un connection pool interno (keep-alive HTTP). Crear N clientes
 *     desperdicia conexiones TCP y handshakes TLS.
 *   - Gson y Retrofit son thread-safe y stateless, no hay razon para tener varios.
 *
 * Como usarlo:
 *     PawGateApi api = ApiClient.get(context);
 *     api.login(new LoginRequest("...", "...")).enqueue(callback);
 */
public final class ApiClient {

    // Volatile + double-checked locking para singleton thread-safe en Java
    private static volatile PawGateApi instance;

    private ApiClient() {}

    public static PawGateApi get(Context context) {
        if (instance == null) {
            synchronized (ApiClient.class) {
                if (instance == null) {
                    instance = build(context);
                }
            }
        }
        return instance;
    }

    private static PawGateApi build(Context context) {
        // Logger para ver request/response en Logcat (filtrar por "OkHttp")
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(context))   // primero auth
                .addInterceptor(logging)                         // despues log
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        String baseUrl = context.getApplicationContext()
                .getString(R.string.api_base_url);

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(httpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        return retrofit.create(PawGateApi.class);
    }
}
