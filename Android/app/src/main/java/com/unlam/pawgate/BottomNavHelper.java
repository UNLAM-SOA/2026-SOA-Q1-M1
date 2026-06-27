package com.unlam.pawgate;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * Helper que wirea el bottom navigation de las 4 tabs del proyecto.
 *
 * Animacion direccional: cuando el user toca un tab a la DERECHA del
 * tab actual, la nueva activity entra desde la derecha (y la actual
 * sale a la izquierda). Cuando toca un tab a la IZQUIERDA, al reves.
 * Sin este detalle, todas las transiciones usaban la animacion default
 * de Android (slide derecha->izquierda) y se sentian inconsistentes.
 *
 * Para que el orden de los tabs determine la direccion, mantenemos una
 * lista interna ORDENADA segun aparecen en el layout del bottom nav.
 */
public final class BottomNavHelper {

    private BottomNavHelper() {}

    /** Orden de los tabs de izquierda a derecha en el bottom nav. */
    private static final int[] TAB_ORDER = {
            R.id.nav_inicio,
            R.id.nav_puerta,
            R.id.nav_historial,
            R.id.nav_ajustes,
    };

    public static void bind(Activity host, int activeTabId) {
        wireTab(host, R.id.nav_inicio, R.id.nav_inicio_icon, R.id.nav_inicio_label, activeTabId, DashboardActivity.class);
        wireTab(host, R.id.nav_puerta, R.id.nav_puerta_icon, R.id.nav_puerta_label, activeTabId, ControlActivity.class);
        wireTab(host, R.id.nav_historial, R.id.nav_historial_icon, R.id.nav_historial_label, activeTabId, HistorialActivity.class);
        wireTab(host, R.id.nav_ajustes, R.id.nav_ajustes_icon, R.id.nav_ajustes_label, activeTabId, AjustesActivity.class);
    }

    private static void wireTab(Activity host, int tabId, int iconId, int labelId, int activeTabId, Class<?> target) {
        View tab = host.findViewById(tabId);
        if (tab == null) return;

        markActive(host, iconId, labelId, tabId == activeTabId);

        tab.setOnClickListener(v -> {
            if (target.isInstance(host)) return;
            Intent intent = new Intent(host, target);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            host.startActivity(intent);

            // Animacion direccional segun la posicion del tab destino vs el
            // tab actual. Si voy a la derecha -> entra desde derecha; si a
            // la izquierda -> entra desde izquierda. Si no encontramos los
            // indices, usamos la animacion default.
            applyDirectionalAnim(host, activeTabId, tabId);

            if (!(host instanceof DashboardActivity)) {
                host.finish();
            }
        });
    }

    private static void applyDirectionalAnim(Activity host, int fromTabId, int toTabId) {
        int fromIdx = indexOf(fromTabId);
        int toIdx = indexOf(toTabId);
        if (fromIdx < 0 || toIdx < 0) return;
        if (toIdx > fromIdx) {
            // Hacia la derecha: nueva entra desde la derecha.
            host.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        } else if (toIdx < fromIdx) {
            // Hacia la izquierda: nueva entra desde la izquierda.
            host.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }

    private static int indexOf(int tabId) {
        for (int i = 0; i < TAB_ORDER.length; i++) {
            if (TAB_ORDER[i] == tabId) return i;
        }
        return -1;
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
