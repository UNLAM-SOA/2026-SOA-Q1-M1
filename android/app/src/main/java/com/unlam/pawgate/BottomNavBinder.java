package com.unlam.pawgate;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

public final class BottomNavBinder {

    private BottomNavBinder() {}

    public static void bind(Activity host, int activeTabId) {
        wireTab(host, R.id.nav_inicio, activeTabId, DashboardActivity.class);
        wireTab(host, R.id.nav_puerta, activeTabId, null);
        wireTab(host, R.id.nav_historial, activeTabId, HistorialActivity.class);
        wireTab(host, R.id.nav_ajustes, activeTabId, AjustesActivity.class);
    }

    private static void wireTab(Activity host, int tabId, int activeTabId, Class<?> target) {
        View tab = host.findViewById(tabId);
        if (tab == null) return;

        setTabSelected(host, tab, tabId == activeTabId);

        tab.setOnClickListener(v -> {
            if (tabId == activeTabId) return;
            if (target == null) {
                Toast.makeText(host, host.getString(R.string.toast_coming_soon), Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(host, target);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            host.startActivity(intent);
            if (!(host instanceof DashboardActivity)) {
                host.finish();
            }
        });
    }

    private static void setTabSelected(Activity host, View tab, boolean selected) {
        ViewGroup container = (ViewGroup) tab;
        ImageView icon = null;
        TextView label = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ImageView && icon == null) {
                icon = (ImageView) child;
            } else if (child instanceof TextView && label == null) {
                label = (TextView) child;
            }
        }
        int color = ContextCompat.getColor(host, selected ? R.color.accent_neon : R.color.text_muted);
        if (icon != null) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
        }
        if (label != null) {
            label.setTextColor(color);
            label.setTypeface(label.getTypeface(), selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }
}
