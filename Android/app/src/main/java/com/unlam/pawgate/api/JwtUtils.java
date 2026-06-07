package com.unlam.pawgate.api;

import android.util.Base64;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Utilidades para inspeccionar claims de un JWT (Cognito idToken).
 *
 * NO valida la firma — solo lee el payload. Eso esta OK aca porque el token
 * ya viene de un endpoint nuestro que lo trajo del Cognito; no estamos
 * confiando en el contenido para decisiones de seguridad sino solo para
 * mostrar el name/email del user en la UI.
 */
public final class JwtUtils {

    private JwtUtils() {}

    /**
     * Devuelve el valor del claim "name" del idToken, o null si no esta o
     * el token no parsea.
     */
    public static String extractName(String idToken) {
        return extractClaim(idToken, "name");
    }

    /** Lee un claim string del payload del JWT. */
    public static String extractClaim(String token, String claim) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return null;
        try {
            byte[] decoded = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String json = new String(decoded);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has(claim) || obj.get(claim).isJsonNull()) return null;
            return obj.get(claim).getAsString();
        } catch (Exception e) {
            return null;
        }
    }
}
