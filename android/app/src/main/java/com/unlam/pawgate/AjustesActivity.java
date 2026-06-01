package com.unlam.pawgate;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class AjustesActivity extends AppCompatActivity {

    private LinearLayout container;
    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_ajustes);

        findViewById(R.id.ajustes_profile_card).setOnClickListener(v -> showToast(R.string.toast_coming_soon));

        container = findViewById(R.id.ajustes_sections);
        inflater = LayoutInflater.from(this);

        LinearLayout dispositivo = agregarSeccion(R.string.ajustes_section_device, null);
        agregarFila(dispositivo, setChevron(crearFila(R.drawable.ic_cpu, R.string.ajustes_device_esp32, "Conectado · v1.0.3")));
        agregarFila(dispositivo, setChevron(crearFila(R.drawable.ic_wifi, R.string.ajustes_device_wifi, "PawGate_5G · -42 dBm")));
        agregarFila(dispositivo, setChevron(crearFila(R.drawable.ic_globe, R.string.ajustes_device_ip, "192.168.1.42")));
        agregarFila(dispositivo, crearFila(R.drawable.ic_timer, R.string.ajustes_device_uptime, "3 d · 12 h · 24 min"));
        agregarFila(dispositivo, crearFila(R.drawable.ic_hash, R.string.ajustes_device_mac, "AC:67:B2:1F:88:42"));

        LinearLayout sensores = agregarSeccion(R.string.ajustes_section_sensors, getString(R.string.ajustes_badge_all_ok));
        agregarFila(sensores, setBadge(crearFila(R.drawable.ic_radio, R.string.ajustes_sensor_ultrasound, "Distancia: 14 cm · 20 Hz"), R.string.ajustes_badge_active));
        agregarFila(sensores, setBadge(crearFila(R.drawable.ic_settings, R.string.ajustes_sensor_servo, "Posición: 0° · cerrado"), R.string.ajustes_badge_ok));

        LinearLayout notificaciones = agregarSeccion(R.string.ajustes_section_notifications, null);
        agregarFila(notificaciones, setToggle(crearFila(R.drawable.ic_bell, R.string.ajustes_notif_push, null)));

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    private LinearLayout agregarSeccion(int tituloRes, String badge) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(14);
        headerParams.bottomMargin = dp(6);
        header.setPadding(dp(4), 0, dp(4), 0);

        TextView title = new TextView(this);
        title.setText(tituloRes);
        title.setTextSize(10);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        title.setLetterSpacing(0.15f);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (badge != null) {
            header.addView(crearBadge(badge));
        }
        container.addView(header, headerParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        container.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void agregarFila(LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            card.addView(crearDivisor());
        }
        card.addView(row);
    }

    private View crearFila(int iconRes, int tituloRes, String subtitulo) {
        View row = inflater.inflate(R.layout.item_ajustes_row, container, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.row_title)).setText(tituloRes);
        TextView subtitle = row.findViewById(R.id.row_subtitle);
        if (subtitulo == null) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setText(subtitulo);
        }
        return row;
    }

    private View setChevron(View row) {
        ImageView chevron = new ImageView(this);
        chevron.setLayoutParams(new FrameLayout.LayoutParams(dp(18), dp(18)));
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_muted));
        accessory(row).addView(chevron);
        return row;
    }

    private View setBadge(View row, int textoRes) {
        accessory(row).addView(crearBadge(getString(textoRes)));
        return row;
    }

    private View setToggle(View row) {
        FrameLayout track = new FrameLayout(this);
        track.setLayoutParams(new FrameLayout.LayoutParams(dp(40), dp(22)));
        track.setBackgroundResource(R.drawable.bg_chip_active);

        View knob = new View(this);
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(16), dp(16));
        knobParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        knobParams.setMarginEnd(dp(3));
        knob.setLayoutParams(knobParams);
        knob.setBackgroundResource(R.drawable.bg_toggle_knob);
        track.addView(knob);

        accessory(row).addView(track);
        return row;
    }

    private FrameLayout accessory(View row) {
        return row.findViewById(R.id.row_accessory);
    }

    private TextView crearBadge(String texto) {
        TextView badge = new TextView(this);
        badge.setText(texto);
        badge.setTextSize(10);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setTextColor(ContextCompat.getColor(this, R.color.accent_success));
        badge.setBackgroundResource(R.drawable.bg_badge_success);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        return badge;
    }

    private View crearDivisor() {
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(ContextCompat.getColor(this, R.color.border_subtle));
        return divider;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }
}
