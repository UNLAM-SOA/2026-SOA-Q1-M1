package com.unlam.pawgate;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Banner de "Sin conexion a internet" que aparece arriba de la pantalla
 * cuando el device pierde red. Se monta sobre el rootView de cualquier
 * Activity sin necesidad de modificar su XML.
 *
 * Uso:
 *   <pre>
 *   private OfflineBanner offlineBanner;
 *
 *   protected void onCreate(...) {
 *       super.onCreate(...);
 *       setContentView(...);
 *       offlineBanner = OfflineBanner.attach(this);
 *   }
 *
 *   protected void onResume() {
 *       super.onResume();
 *       offlineBanner.start();
 *   }
 *
 *   protected void onPause() {
 *       super.onPause();
 *       offlineBanner.stop();
 *   }
 *   </pre>
 *
 * Internamente registra un BroadcastReceiver para CONNECTIVITY_ACTION (legacy
 * pero funciona en >= API 24) y actualiza el banner inmediatamente al cambio.
 */
public class OfflineBanner {

    private final Activity activity;
    private final TextView bannerView;
    private boolean running = false;

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            update();
        }
    };

    private OfflineBanner(Activity activity, TextView bannerView) {
        this.activity = activity;
        this.bannerView = bannerView;
    }

    /** Crea e inserta el banner en el root view (visible solo cuando offline). */
    public static OfflineBanner attach(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        // El primer (y unico) child del android:id/content es el layout que
        // setContentView() inflo. Ese tiene match_parent, asi que ponemos
        // un FrameLayout overlay arriba para no romper layouts existentes.
        TextView banner = new TextView(activity);
        banner.setText(R.string.offline_banner_text);
        banner.setTextColor(Color.WHITE);
        banner.setBackgroundColor(0xFFB07700);  // amber, advertencia
        banner.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        banner.setGravity(Gravity.CENTER);
        banner.setTextSize(12f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        banner.setLayoutParams(lp);
        banner.setVisibility(View.GONE);
        root.addView(banner);
        return new OfflineBanner(activity, banner);
    }

    public void start() {
        if (running) return;
        update();
        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        activity.registerReceiver(connectivityReceiver, filter);
        running = true;
    }

    public void stop() {
        if (!running) return;
        try {
            activity.unregisterReceiver(connectivityReceiver);
        } catch (IllegalArgumentException ignored) {
            // defensivo
        }
        running = false;
    }

    private void update() {
        boolean online = NetworkUtils.isOnline(activity);
        bannerView.setVisibility(online ? View.GONE : View.VISIBLE);
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
