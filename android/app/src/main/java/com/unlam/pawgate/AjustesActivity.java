package com.unlam.pawgate;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

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
                v -> Toast.makeText(this, getString(R.string.toast_coming_soon), Toast.LENGTH_SHORT).show());

        findViewById(R.id.ajustes_row_schedules).setOnClickListener(
                v -> startActivity(new Intent(this, HorariosActivity.class)));

        wirePushToggle();

        BottomNavHelper.bind(this, R.id.nav_ajustes);
    }

    /** Setea nombre + email en la profile card a partir del email del Login. */
    private void renderProfile() {
        TextView nameView = findViewById(R.id.ajustes_user_name);
        TextView emailView = findViewById(R.id.ajustes_user_email);

        String email = PrefsHelper.getUserEmail(this);
        if (email != null && !email.isEmpty()) {
            // Display name = parte antes del @ (ej: "fede" de "fede@pawgate.io")
            String displayName = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            nameView.setText(displayName);
            emailView.setText(email);
        } else {
            nameView.setText(R.string.ajustes_user_name_fallback);
            emailView.setText("");
        }
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
