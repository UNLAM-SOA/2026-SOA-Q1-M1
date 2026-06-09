package com.unlam.pawgate.api;

import android.content.Context;

import com.unlam.pawgate.PrefsHelper;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp Interceptor que agrega Authorization: Bearer <idToken> a cada request
 * EXCEPT los de /auth/* (login/signup/confirm son publicos, no requieren JWT).
 *
 * Patron clasico de middleware HTTP. Se conecta al OkHttpClient en ApiClient
 * y corre antes de cada request, automaticamente.
 */
public class AuthInterceptor implements Interceptor {

    private final Context appContext;

    public AuthInterceptor(Context context) {
        // Guardamos siempre applicationContext para no leakear Activity
        this.appContext = context.getApplicationContext();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        String path = original.url().encodedPath();

        // Endpoints publicos - no agregar header
        if (path.contains("/auth/")) {
            return chain.proceed(original);
        }

        // Resto de endpoints - agregar Authorization si hay token
        String idToken = PrefsHelper.getIdToken(appContext);
        if (idToken == null || idToken.isEmpty()) {
            // Sin token, dejamos pasar y que API GW devuelva 401
            return chain.proceed(original);
        }

        Request authenticated = original.newBuilder()
                .header("Authorization", "Bearer " + idToken)
                .build();
        return chain.proceed(authenticated);
    }
}
