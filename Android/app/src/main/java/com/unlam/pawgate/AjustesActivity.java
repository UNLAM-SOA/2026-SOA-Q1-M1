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

public class AjustesActivity extends AppCompatActivity {

    private FrameLayout pushToggle;
    private View pushKnob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ajustes);

        renderProfile();

        findViewById(R.id.ajustes_profile_card).setOnClickListener(
                v -> startActivity(new Intent(this, PerfilActivity.class)));

        findViewById(R.id.ajustes_row_schedules).setOnClickListener(
                v -> startActivity(new Intent(this, HorariosActivity.class)));

        wirePushToggle();

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
    }

    // ============================================================
    // PUSH TOGGLE (XML define el contenedor, aca le aplicamos el estado visual)
    // ============================================================

    private void wirePushToggle() {
        pushToggle = findViewById(R.id.ajustes_push_toggle);
        pushKnob = findViewById(R.id.ajustes_push_knob);

        applyToggleState(PrefsHelper.isPushEnabled(this));

        pushToggle.setOnClickListener(v -> {
            boolean newState = !PrefsHelper.isPushEnabled(this);
            PrefsHelper.setPushEnabled(this, newState);
            applyToggleState(newState);
        });
    }

    private void applyToggleState(boolean enabled) {
        // Track: verde (chip_active) si enabled, gris (chip_inactive) si no.
        pushToggle.setBackgroundResource(
                enabled ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);

        // Knob: a la derecha si enabled, a la izquierda si no.
        // Color: blanco si enabled (sobre verde), gris claro si no (sobre gris).
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(16), dp(16));
        knobParams.gravity = (enabled ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        if (enabled) {
            knobParams.setMarginEnd(dp(3));
        } else {
            knobParams.setMarginStart(dp(3));
        }
        pushKnob.setLayoutParams(knobParams);
        pushKnob.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, enabled ? R.color.bg_card : R.color.text_muted)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
