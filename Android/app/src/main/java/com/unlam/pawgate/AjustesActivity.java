package com.unlam.pawgate;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.JwtUtils;
import com.unlam.pawgate.api.dto.DeviceDtos;

public class AjustesActivity extends AppCompatActivity {

    private FrameLayout pushToggle;
    private View pushKnob;
    private FrameLayout shakeToggle;
    private View shakeKnob;

    private DeviceRepository deviceRepo;
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ajustes);

        deviceRepo = new DeviceRepository(this);
        deviceId = getString(R.string.default_device_id);

        renderProfile();
        loadDeviceInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Volver de PerfilActivity tras editar el nombre debe refrescar
        // el avatar + email row de la profile card. Lo mismo si volvio
        // de DeviceDetail / WifiDetail (no afecta nada pero tampoco
        // hace harm).
        renderProfile();

        findViewById(R.id.ajustes_profile_card).setOnClickListener(
                v -> startActivity(new Intent(this, PerfilActivity.class)));

        findViewById(R.id.ajustes_row_schedules).setOnClickListener(
                v -> startActivity(new Intent(this, HorariosActivity.class)));

        findViewById(R.id.ajustes_row_device_esp32).setOnClickListener(
                v -> startActivity(new Intent(this, DeviceDetailActivity.class)));

        findViewById(R.id.ajustes_row_device_wifi).setOnClickListener(
                v -> startActivity(new Intent(this, WifiDetailActivity.class)));

        wirePushToggle();
        wireShakeToggle();

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    /** Setea nombre + email en la profile card.
     *  Prioridad de nombre: PrefsHelper.getUserName -> claim 'name' del JWT
     *  almacenado -> parte antes del @ del email -> fallback. */
    private void renderProfile() {
        TextView nameView = findViewById(R.id.ajustes_user_name);
        TextView emailView = findViewById(R.id.ajustes_user_email);

        String name = PrefsHelper.getUserName(this);
        if (name == null || name.isEmpty()) {
            String idToken = PrefsHelper.getIdToken(this);
            if (idToken != null) {
                name = com.unlam.pawgate.api.JwtUtils.extractName(idToken);
                if (name != null) PrefsHelper.setUserName(this, name);
            }
        }
        String email = PrefsHelper.getUserEmail(this);
        if ((name == null || name.isEmpty()) && email != null) {
            name = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        }
        if (name == null || name.isEmpty()) {
            nameView.setText(R.string.ajustes_user_name_fallback);
        } else {
            nameView.setText(name);
        }
        emailView.setText(email != null ? email : "");

        // Avatar: primera letra del nombre.
        String initial = name != null && !name.isEmpty()
                ? name.substring(0, 1).toUpperCase()
                : "?";
        ((TextView) findViewById(R.id.perfil_avatar_initial)).setText(initial);
    }

    // ============================================================
    // PUSH TOGGLE (XML define el contenedor, aca le aplicamos el estado visual)
    // ============================================================

    private void wirePushToggle() {
        pushToggle = findViewById(R.id.ajustes_push_toggle);
        pushKnob = findViewById(R.id.ajustes_push_knob);

        renderToggle(pushToggle, pushKnob, PrefsHelper.isPushEnabled(this));

        pushToggle.setOnClickListener(v -> {
            boolean newState = !PrefsHelper.isPushEnabled(this);
            PrefsHelper.setPushEnabled(this, newState);
            renderToggle(pushToggle, pushKnob, newState);
        });
    }

    private void wireShakeToggle() {
        shakeToggle = findViewById(R.id.ajustes_shake_toggle);
        shakeKnob = findViewById(R.id.ajustes_shake_knob);

        renderToggle(shakeToggle, shakeKnob, PrefsHelper.isShakeToCallEnabled(this));

        shakeToggle.setOnClickListener(v -> {
            boolean newState = !PrefsHelper.isShakeToCallEnabled(this);
            PrefsHelper.setShakeToCallEnabled(this, newState);
            renderToggle(shakeToggle, shakeKnob, newState);
        });
    }

    /** Render visual del toggle (track verde/gris + knob izquierda/derecha + tint). */
    private void renderToggle(FrameLayout track, View knob, boolean enabled) {
        track.setBackgroundResource(
                enabled ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(16), dp(16));
        knobParams.gravity = (enabled ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        if (enabled) knobParams.setMarginEnd(dp(3));
        else         knobParams.setMarginStart(dp(3));
        knob.setLayoutParams(knobParams);
        knob.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, enabled ? R.color.bg_card : R.color.text_muted)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ============================================================
    // DEVICE INFO REAL (IP, uptime, MAC) — viene de /info que pollea
    // el ESP32 telemetry cada 30s.
    // ============================================================

    private void loadDeviceInfo() {
        deviceRepo.deviceInfo(deviceId,
                new ApiCallback<DeviceDtos.DeviceInfoResponse>() {
            @Override public void onSuccess(DeviceDtos.DeviceInfoResponse r) {
                if (r == null) {
                    renderUnavailableDeviceInfo();
                    return;
                }
                setRow(R.id.ajustes_row_device_ip_subtitle,
                        nonEmpty(r.local_ip, getString(R.string.ajustes_value_unavailable)));
                setRow(R.id.ajustes_row_device_uptime_subtitle,
                        formatUptime(r.uptime_s));
                setRow(R.id.ajustes_row_device_mac_subtitle,
                        nonEmpty(r.device_mac, getString(R.string.ajustes_value_unavailable)));
            }
            @Override public void onError(String message) {
                renderUnavailableDeviceInfo();
            }
        });
    }

    private void renderUnavailableDeviceInfo() {
        String na = getString(R.string.ajustes_value_unavailable);
        setRow(R.id.ajustes_row_device_ip_subtitle, na);
        setRow(R.id.ajustes_row_device_uptime_subtitle, na);
        setRow(R.id.ajustes_row_device_mac_subtitle, na);
    }

    private void setRow(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private static String nonEmpty(String s, String fallback) {
        return (s == null || s.trim().isEmpty()) ? fallback : s;
    }

    /** Formatea uptime en segundos a "Xd · Yh · Zmin", "Yh · Zmin" o "Zmin". */
    private String formatUptime(long uptimeSec) {
        if (uptimeSec <= 0) return getString(R.string.ajustes_value_unavailable);
        long days  = uptimeSec / 86400;
        long hours = (uptimeSec % 86400) / 3600;
        long mins  = (uptimeSec % 3600) / 60;
        if (days > 0)  return getString(R.string.ajustes_uptime_dhm, (int) days, (int) hours, (int) mins);
        if (hours > 0) return getString(R.string.ajustes_uptime_hm, (int) hours, (int) mins);
        return getString(R.string.ajustes_uptime_m, (int) mins);
    }
}
