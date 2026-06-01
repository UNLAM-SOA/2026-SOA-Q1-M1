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

public class HistorialActivity extends AppCompatActivity {

    private static final class Evento {
        final int iconRes;
        final int tituloRes;
        final String subtitulo;

        Evento(int iconRes, int tituloRes, String subtitulo) {
            this.iconRes = iconRes;
            this.tituloRes = tituloRes;
            this.subtitulo = subtitulo;
        }
    }

    private TextView chipTodas;
    private TextView chipHoy;
    private TextView chipAyer;
    private TextView chip7d;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_historial);

        findViewById(R.id.historial_back).setOnClickListener(v -> finish());
        findViewById(R.id.historial_filter).setOnClickListener(v -> showToast(R.string.toast_coming_soon));

        chipTodas = findViewById(R.id.chip_todas);
        chipHoy = findViewById(R.id.chip_hoy);
        chipAyer = findViewById(R.id.chip_ayer);
        chip7d = findViewById(R.id.chip_7d);
        for (TextView chip : new TextView[]{chipTodas, chipHoy, chipAyer, chip7d}) {
            chip.setOnClickListener(v -> seleccionarChip((TextView) v));
        }

        Evento[] eventos = {
                new Evento(R.drawable.ic_door_open, R.string.historial_event_puerta_abierta, "hace 2m"),
                new Evento(R.drawable.ic_lightbulb_off, R.string.historial_event_luz_apagada, "hace 18m"),
                new Evento(R.drawable.ic_lock, R.string.historial_event_puerta_bloqueada, "hace 1h"),
                new Evento(R.drawable.ic_lightbulb, R.string.historial_event_luz_encendida, "ayer · 22:16"),
                new Evento(R.drawable.ic_wifi, R.string.historial_event_wifi, "ayer · 21:14"),
                new Evento(R.drawable.ic_calendar, R.string.historial_event_nocturno, "ayer · 20:00"),
                new Evento(R.drawable.ic_lock_open, R.string.historial_event_puerta_desbloqueada, "ayer · 18:42"),
        };

        LinearLayout list = findViewById(R.id.event_list);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < eventos.length; i++) {
            if (i > 0) {
                list.addView(crearDivisor());
            }
            list.addView(renderizarEvento(inflater, list, eventos[i]));
        }

        BottomNavBinder.bind(this, R.id.nav_historial);
    }

    private View renderizarEvento(LayoutInflater inflater, ViewGroup parent, Evento e) {
        View row = inflater.inflate(R.layout.item_historial_event, parent, false);
        ((ImageView) row.findViewById(R.id.event_icon)).setImageResource(e.iconRes);
        ((TextView) row.findViewById(R.id.event_title)).setText(e.tituloRes);
        ((TextView) row.findViewById(R.id.event_subtitle)).setText(e.subtitulo);
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
        for (TextView chip : new TextView[]{chipTodas, chipHoy, chipAyer, chip7d}) {
            boolean isActive = chip == active;
            chip.setBackgroundResource(isActive
                    ? R.drawable.bg_filter_chip_active : R.drawable.bg_button_secondary);
            chip.setTextColor(ContextCompat.getColor(this,
                    isActive ? R.color.text_primary : R.color.text_secondary));
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
