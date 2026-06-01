package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class DashboardActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        BottomNavHelper.markActive(this, R.id.nav_inicio_icon, R.id.nav_inicio_label);

        String user = getIntent().getStringExtra(LoginActivity.EXTRA_USER);

        if (user != null) {
            TextView greeting = findViewById(R.id.dashboard_greeting);
            greeting.setText(getString(R.string.dashboard_greeting_template, user));
        }
        findViewById(R.id.dashboard_door_card).setOnClickListener(v -> openControl());
        findViewById(R.id.dashboard_notification).setOnClickListener(v -> startActivity(new Intent(this, NotificacionesActivity.class)));
        findViewById(R.id.action_open).setOnClickListener(v -> showToast(R.string.toast_action_open));
        findViewById(R.id.action_block).setOnClickListener(v -> showToast(R.string.toast_action_block));
        findViewById(R.id.action_call).setOnClickListener(v -> showToast(R.string.toast_action_call));
        findViewById(R.id.nav_puerta).setOnClickListener(v -> openControl());
        findViewById(R.id.nav_historial).setOnClickListener(v -> showToast(R.string.toast_coming_soon));
        findViewById(R.id.nav_ajustes).setOnClickListener(v -> showToast(R.string.toast_coming_soon));
        findViewById(R.id.action_schedules).setOnClickListener(v -> startActivity(new Intent(this, HorariosActivity.class)));

        BottomNavBinder.bind(this, R.id.nav_inicio);
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }

    private void openControl() {
        startActivity(new Intent(this, ControlActivity.class));
    }

}
