package com.unlam.pawgate;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Utility para chequear conectividad de red. Wrappea ConnectivityManager.
 *
 * Uso:
 *   if (!NetworkUtils.isOnline(ctx)) { show offline banner; return; }
 *
 * Limitacion conocida: "isOnline" devuelve true si el device esta CONECTADO
 * a una red con capacidades de internet, pero NO confirma que efectivamente
 * pueda alcanzar AWS. Para eso habria que hacer un ping al endpoint. En la
 * practica para esta app alcanza: si hay red, Retrofit hace el call y si
 * falla se maneja con el error handler normal.
 */
public final class NetworkUtils {

    private NetworkUtils() {}

    public static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) return false;
        // INTERNET: la red dice tener internet. VALIDATED: el OS confirmo
        // que efectivamente sale (passed captive portal check).
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
