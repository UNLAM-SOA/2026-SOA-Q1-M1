package com.unlam.pawgate;

import android.app.Activity;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

public class BottomNavHelper {
    /** Marca un tab del BottomNav como activo (tint accent_neon + texto bold).
     *  @param activity la Activity que contiene el include del bottom_nav
     *  @param iconId   id del ImageView del tab activo (ej: R.id.nav_puerta_icon)
     *  @param labelId  id del TextView del tab activo (ej: R.id.nav_puerta_label)
     */
    public static void markActive(Activity activity, int iconId, int labelId) {
        int activeColor = ContextCompat.getColor(activity, R.color.accent_neon);
        ImageView icon = activity.findViewById(iconId);
        TextView label = activity.findViewById(labelId);
        icon.setColorFilter(activeColor);
        label.setTextColor(activeColor);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
    }
}