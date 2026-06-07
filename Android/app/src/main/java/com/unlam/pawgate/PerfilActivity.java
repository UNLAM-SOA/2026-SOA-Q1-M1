package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.unlam.pawgate.api.JwtUtils;

/**
 * Pantalla "Mi perfil" (W16).
 *
 * Muestra info del user logueado (nombre del claim 'name' del idToken + email)
 * y un boton "Cerrar sesion" que:
 *   1) Para el PawGatePollingService (no tiene sentido seguir pollando sin token).
 *   2) Limpia auth + nombre del PrefsHelper.
 *   3) Navega a LoginActivity limpiando el back stack.
 */
public class PerfilActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_perfil);

        findViewById(R.id.perfil_back).setOnClickListener(v -> finish());

        renderUser();

        MaterialButton logoutButton = findViewById(R.id.perfil_logout_button);
        logoutButton.setOnClickListener(v -> showLogoutDialog());
    }

    private void renderUser() {
        // Prioridad: user_name persistido -> claim 'name' del idToken -> email parsed.
        String name = PrefsHelper.getUserName(this);
        if (name == null || name.isEmpty()) {
            String idToken = PrefsHelper.getIdToken(this);
            if (idToken != null) {
                name = JwtUtils.extractName(idToken);
                if (name != null) PrefsHelper.setUserName(this, name);
            }
        }
        String email = PrefsHelper.getUserEmail(this);
        if ((name == null || name.isEmpty()) && email != null) {
            name = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        }
        if (name == null) name = getString(R.string.ajustes_user_name_fallback);
        if (email == null) email = "";

        ((TextView) findViewById(R.id.perfil_name_big)).setText(name);
        ((TextView) findViewById(R.id.perfil_name_row)).setText(name);
        ((TextView) findViewById(R.id.perfil_email_small)).setText(email);
        ((TextView) findViewById(R.id.perfil_email_row)).setText(email);

        // Avatar: primera letra del nombre.
        String initial = !name.isEmpty()
                ? name.substring(0, 1).toUpperCase()
                : "?";
        ((TextView) findViewById(R.id.perfil_avatar_initial)).setText(initial);
    }

    private void showLogoutDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perfil_logout_dialog_title)
                .setMessage(R.string.perfil_logout_dialog_message)
                .setPositiveButton(R.string.perfil_logout_dialog_confirm, (d, w) -> doLogout())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doLogout() {
        // 1) Parar el Service de polling.
        PawGatePollingService.stop(this);
        // 2) Limpiar tokens + user info de SharedPreferences.
        PrefsHelper.clearAuth(this);
        // 3) Volver al Login limpiando todo el back stack para que Back salga
        //    de la app en vez de volver al Dashboard sin sesion.
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
