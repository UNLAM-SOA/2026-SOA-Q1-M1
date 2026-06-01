package com.unlam.pawgate;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class NotificacionesActivity extends AppCompatActivity {

    private static final class Notificacion {
        final int iconRes;
        final int tituloRes;
        final String subtitulo;
        final boolean noLeida;

        Notificacion(int iconRes, int tituloRes, String subtitulo, boolean noLeida) {
            this.iconRes = iconRes;
            this.tituloRes = tituloRes;
            this.subtitulo = subtitulo;
            this.noLeida = noLeida;
        }
    }

    private TextView chipAll;
    private TextView chipUnread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_notificaciones);

        findViewById(R.id.notificaciones_mark_all).setOnClickListener(v -> showToast(R.string.toast_coming_soon));

        chipAll = findViewById(R.id.chip_all);
        chipUnread = findViewById(R.id.chip_unread);
        for (TextView chip : new TextView[]{chipAll, chipUnread}) {
            chip.setOnClickListener(v -> seleccionarChip((TextView) v));
        }

        Notificacion[] notificaciones = {
                new Notificacion(R.drawable.ic_door_open, R.string.notif_puerta_abierta, "hace 2m", true),
                new Notificacion(R.drawable.ic_battery_low, R.string.notif_bateria_baja, "hace 18m", true),
                new Notificacion(R.drawable.ic_lock, R.string.notif_puerta_bloqueada, "hace 1h", false),
                new Notificacion(R.drawable.ic_phone_call, R.string.notif_llamada_perdida, "hace 3h", false),
                new Notificacion(R.drawable.ic_wifi, R.string.notif_wifi, "ayer · 21:14", false),
                new Notificacion(R.drawable.ic_moon, R.string.notif_nocturno, "ayer · 20:00", false),
                new Notificacion(R.drawable.ic_log_in, R.string.notif_entro, "ayer · 18:42", false),
        };

        LinearLayout list = findViewById(R.id.notif_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < notificaciones.length; i++) {
            if (i > 0) {
                list.addView(crearDivisor());
            }
            list.addView(renderizarNotificacion(inflater, list, notificaciones[i]));
        }

        BottomNavBinder.bind(this, R.id.nav_inicio);
    }

    private View renderizarNotificacion(LayoutInflater inflater, ViewGroup parent, Notificacion n) {
        View row = inflater.inflate(R.layout.item_notificacion, parent, false);
        row.findViewById(R.id.notif_dot).setVisibility(n.noLeida ? View.VISIBLE : View.INVISIBLE);
        ((ImageView) row.findViewById(R.id.notif_icon)).setImageResource(n.iconRes);
        ((TextView) row.findViewById(R.id.notif_title)).setText(n.tituloRes);
        ((TextView) row.findViewById(R.id.notif_subtitle)).setText(n.subtitulo);
        return row;
    }

    private View crearDivisor() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.border_subtle));
        return divider;
    }

    private void seleccionarChip(TextView active) {
        for (TextView chip : new TextView[]{chipAll, chipUnread}) {
            boolean isActive = chip == active;
            chip.setBackgroundResource(isActive ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chip.setTextColor(ContextCompat.getColor(this,
                    isActive ? R.color.bg_card : R.color.text_secondary));
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
