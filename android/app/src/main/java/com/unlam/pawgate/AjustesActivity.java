package com.unlam.pawgate;

import android.content.Intent;
import android.content.res.ColorStateList;
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

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * Ajustes - Configuracion del dispositivo, sensores, horarios y notificaciones.
 *
 * Las secciones se construyen programaticamente porque cada una tiene un
 * tipo de fila distinto (con chevron, badge, toggle). Refactorizar a XML
 * estatico requeriria 4 layouts hijos distintos.
 *
 * Usa PrefsHelper centralizado para:
 *   - leer el email del usuario logueado (lo muestra en la profile card)
 *   - persistir el toggle de Push (con onResume re-leemos por si cambio en otra Activity)
 *
 * La seccion HORARIOS tiene una fila clickable que abre HorariosActivity
 * para que el usuario pueda ver y configurar los horarios desde Ajustes.
 */
public class AjustesActivity extends AppCompatActivity {

    private LinearLayout container;
    private LayoutInflater inflater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ajustes);

        // Profile card: nombre + email leidos de PrefsHelper (vienen del Login).
        renderProfile();

        findViewById(R.id.ajustes_profile_card).setOnClickListener(
                v -> showToast(R.string.toast_coming_soon));

        container = findViewById(R.id.ajustes_sections);
        inflater = LayoutInflater.from(this);

        renderSeccionDispositivo();
        renderSeccionSensores();
        renderSeccionHorarios();
        renderSeccionNotificaciones();

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    // ============================================================
    // PROFILE
    // ============================================================

    private void renderProfile() {
        String email = PrefsHelper.getUserEmail(this);
        TextView nameView = findViewById(R.id.ajustes_user_name);
        TextView emailView = findViewById(R.id.ajustes_user_email);

        if (email != null && !email.isEmpty()) {
            // Si el email tiene formato "nombre@dominio", usamos el nombre como display name
            String displayName = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            nameView.setText(displayName);
            emailView.setText(email);
        } else {
            nameView.setText(R.string.ajustes_user_name_fallback);
            emailView.setText("");
        }
    }

    // ============================================================
    // SECCIONES
    // ============================================================

    private void renderSeccionDispositivo() {
        LinearLayout card = agregarSeccion(R.string.ajustes_section_device, null);
        agregarFila(card, withChevron(makeRow(R.drawable.ic_cpu, R.string.ajustes_device_esp32, "Conectado · v1.0.3")));
        agregarFila(card, withChevron(makeRow(R.drawable.ic_wifi, R.string.ajustes_device_wifi, "PawGate_5G · -42 dBm")));
        agregarFila(card, withChevron(makeRow(R.drawable.ic_globe, R.string.ajustes_device_ip, "192.168.1.42")));
        agregarFila(card, makeRow(R.drawable.ic_timer, R.string.ajustes_device_uptime, "3 d · 12 h · 24 min"));
        agregarFila(card, makeRow(R.drawable.ic_hash, R.string.ajustes_device_mac, "AC:67:B2:1F:88:42"));
    }

    private void renderSeccionSensores() {
        LinearLayout card = agregarSeccion(R.string.ajustes_section_sensors, getString(R.string.ajustes_badge_all_ok));
        agregarFila(card, withBadge(makeRow(R.drawable.ic_radio, R.string.ajustes_sensor_ultrasound, "Distancia: 14 cm · 20 Hz"),
                R.string.ajustes_badge_active));
        agregarFila(card, withBadge(makeRow(R.drawable.ic_settings, R.string.ajustes_sensor_servo, "Posición: 0° · cerrado"),
                R.string.ajustes_badge_ok));
    }

    /** Fila clickable que abre HorariosActivity para configurar horarios. */
    private void renderSeccionHorarios() {
        LinearLayout card = agregarSeccion(R.string.ajustes_section_schedules, null);
        View row = withChevron(makeRow(R.drawable.ic_calendar, R.string.ajustes_schedules_row,
                getString(R.string.ajustes_schedules_subtitle)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> startActivity(new Intent(this, HorariosActivity.class)));
        agregarFila(card, row);
    }

    private void renderSeccionNotificaciones() {
        LinearLayout card = agregarSeccion(R.string.ajustes_section_notifications, null);
        agregarFila(card, withToggle(makeRow(R.drawable.ic_bell, R.string.ajustes_notif_push, null)));
    }

    // ============================================================
    // BUILDERS (construyen Views programaticamente)
    // ============================================================

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
            header.addView(makeBadge(badge));
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
            card.addView(makeDivider());
        }
        card.addView(row);
    }

    private View makeRow(int iconRes, int tituloRes, String subtitulo) {
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

    private View withChevron(View row) {
        ImageView chevron = new ImageView(this);
        chevron.setLayoutParams(new FrameLayout.LayoutParams(dp(18), dp(18)));
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_muted));
        accessory(row).addView(chevron);
        return row;
    }

    private View withBadge(View row, int textoRes) {
        accessory(row).addView(makeBadge(getString(textoRes)));
        return row;
    }

    private View withToggle(View row) {
        FrameLayout track = new FrameLayout(this);
        track.setLayoutParams(new FrameLayout.LayoutParams(dp(40), dp(22)));
        track.setClickable(true);
        track.setFocusable(true);

        View knob = new View(this);
        track.addView(knob);

        boolean[] encendido = {PrefsHelper.isPushEnabled(this)};
        applyToggleState(track, knob, encendido[0]);

        track.setOnClickListener(v -> {
            encendido[0] = !encendido[0];
            applyToggleState(track, knob, encendido[0]);
            PrefsHelper.setPushEnabled(this, encendido[0]);
        });

        accessory(row).addView(track);
        return row;
    }

    private void applyToggleState(FrameLayout track, View knob, boolean encendido) {
        track.setBackgroundResource(encendido ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);

        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(16), dp(16));
        knobParams.gravity = (encendido ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        if (encendido) {
            knobParams.setMarginEnd(dp(3));
        } else {
            knobParams.setMarginStart(dp(3));
        }
        knob.setLayoutParams(knobParams);
        knob.setBackgroundResource(R.drawable.bg_toggle_knob);
        knob.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, encendido ? R.color.bg_card : R.color.text_muted)));
    }

    private FrameLayout accessory(View row) {
        return row.findViewById(R.id.row_accessory);
    }

    private TextView makeBadge(String texto) {
        TextView badge = new TextView(this);
        badge.setText(texto);
        badge.setTextSize(10);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setTextColor(ContextCompat.getColor(this, R.color.accent_success));
        badge.setBackgroundResource(R.drawable.bg_badge_success);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        return badge;
    }

    private View makeDivider() {
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
