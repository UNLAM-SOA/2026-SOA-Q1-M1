package com.unlam.pawgate;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public final class BottomNavHelper {

    private BottomNavHelper() {}

    public static void bind(Activity host, int activeTabId) {
        wireTab(host, R.id.nav_inicio, R.id.nav_inicio_icon, R.id.nav_inicio_label, activeTabId, DashboardActivity.class);
        wireTab(host, R.id.nav_puerta, R.id.nav_puerta_icon, R.id.nav_puerta_label, activeTabId, ControlActivity.class);
        wireTab(host, R.id.nav_historial, R.id.nav_historial_icon, R.id.nav_historial_label, activeTabId, HistorialActivity.class);
        wireTab(host, R.id.nav_ajustes, R.id.nav_ajustes_icon, R.id.nav_ajustes_label, activeTabId, AjustesActivity.class);
    }

    private static void wireTab(Activity host, int tabId, int iconId, int labelId, int activeTabId, Class<?> target) {
        View tab = host.findViewById(tabId);
        if (tab == null) return;

        boolean active = tabId == activeTabId;
        markActive(host, iconId, labelId, active);

        tab.setOnClickListener(v -> {
            if (active) return;
            Intent intent = new Intent(host, target);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            host.startActivity(intent);
            if (!(host instanceof DashboardActivity)) {
                host.finish();
            }
        });
    }

    private static void markActive(Activity host, int iconId, int labelId, boolean active) {
        int color = ContextCompat.getColor(host, active ? R.color.accent_neon : R.color.text_muted);
        ImageView icon = host.findViewById(iconId);
        TextView label = host.findViewById(labelId);
        if (icon != null) {
            icon.setColorFilter(color);
        }
        if (label != null) {
            label.setTextColor(color);
            label.setTypeface(label.getTypeface(), active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }
}
