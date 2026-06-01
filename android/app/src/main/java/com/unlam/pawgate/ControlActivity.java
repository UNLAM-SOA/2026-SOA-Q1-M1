package com.unlam.pawgate;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class ControlActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);
        BottomNavHelper.markActive(this, R.id.nav_puerta_icon, R.id.nav_puerta_label);
        findViewById(R.id.control_btn_open).setOnClickListener(v -> showToast(R.string.toast_action_open));
        findViewById(R.id.control_card_block).setOnClickListener(v -> showBlockConfirmDialog());
        findViewById(R.id.control_card_call).setOnClickListener(v -> showToast(R.string.toast_action_call));
        findViewById(R.id.nav_inicio).setOnClickListener(v -> finish());
        findViewById(R.id.nav_historial).setOnClickListener(v -> showToast(R.string.toast_coming_soon));
        findViewById(R.id.nav_ajustes).setOnClickListener(v -> showToast(R.string.toast_coming_soon));
    }

    private void showToast(int messageRes) {
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show();
    }

    private void showBlockConfirmDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.control_block_dialog_title)
                .setMessage(R.string.control_block_dialog_message)
                .setPositiveButton(R.string.control_block_dialog_confirm,
                        (dialog, which) -> showToast(R.string.toast_action_block))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}