package com.unlam.pawgate.api;

/**
 * Callback simple para resultados de la API. Mas conciso que el Callback<T> de
 * Retrofit (que mezcla onResponse + onFailure + necesita .isSuccessful()).
 *
 * Convenciones:
 *   - onSuccess se llama con el body deserializado.
 *   - onError se llama con un mensaje human-readable (ya parseado del body de error
 *     si el server devolvio JSON, o el toString del Throwable si fue error de red).
 */
public interface ApiCallback<T> {
    void onSuccess(T result);
    void onError(String message);
}
