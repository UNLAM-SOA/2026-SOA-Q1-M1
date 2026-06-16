package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.AuthRepository;
import com.unlam.pawgate.api.dto.AuthDtos;

public class LoginActivity extends AppCompatActivity {

    public static final String EXTRA_USER = "user";

    private EditText email;
    private EditText password;
    private Button loginButton;
    private AuthRepository authRepo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Auto-login: si hay un idToken valido en SharedPreferences, saltamos al Dashboard
        if (PrefsHelper.isLoggedIn(this)) {
            goToDashboard(PrefsHelper.getUserEmail(this));
            return;
        }

        setContentView(R.layout.activity_login);

        this.email = findViewById(R.id.login_email_input);
        this.password = findViewById(R.id.login_password_input);
        this.loginButton = findViewById(R.id.login_submit_button);
        TextView loginRegisterLink = findViewById(R.id.login_register_link);

        this.authRepo = new AuthRepository(this);

        loginButton.setOnClickListener(v -> onLoginClick());

        loginRegisterLink.setOnClickListener(v -> {
            Intent i = new Intent(this, RegisterActivity.class);
            startActivity(i);
        });
    }

    private void onLoginClick() {
        String emailValue = email.getText().toString().trim();
        String passwordValue = password.getText().toString();

        if (emailValue.isEmpty() || passwordValue.isEmpty()) {
            Toast.makeText(this, "Email y contraseña son requeridos", Toast.LENGTH_SHORT).show();
            return;
        }

        // Pre-check offline: si no hay red, evitamos el call y damos feedback
        // inmediato. Igual Retrofit lo manejaria, pero el toast es mas claro
        // que un "Failed to connect to host..."
        if (!NetworkUtils.isOnline(this)) {
            Toast.makeText(this, R.string.generic_network_error, Toast.LENGTH_LONG).show();
            return;
        }

        // Bloquear el boton mientras hay request en curso para evitar doble click
        loginButton.setEnabled(false);
        loginButton.setText(R.string.loading);

        authRepo.login(emailValue, passwordValue, new ApiCallback<AuthDtos.LoginResponse>() {
            @Override
            public void onSuccess(AuthDtos.LoginResponse result) {
                // Tokens ya quedaron persistidos por AuthRepository.
                // Si Firebase ya nos habia dado un FCM token antes del login
                // (caso boot inicial), lo registramos ahora que tenemos sesion.
                registerPendingFcmTokenIfAny();
                // Tambien pedimos a Firebase el token actual (puede ser distinto
                // al pending, y cubre el caso donde onNewToken nunca llego pero
                // Firebase ya tiene un token asignado).
                FcmTokenSync.syncIfLoggedIn(LoginActivity.this);
                goToDashboard(emailValue);
            }

            @Override
            public void onError(String message) {
                loginButton.setEnabled(true);
                loginButton.setText(R.string.login_button);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Si Firebase nos dio un token antes del login, lo enviamos al backend. */
    private void registerPendingFcmTokenIfAny() {
        String pending = PrefsHelper.getPendingFcmToken(this);
        if (pending == null || pending.isEmpty()) return;
        new com.unlam.pawgate.api.DeviceRepository(this).registerFcmToken(pending,
                new ApiCallback<com.unlam.pawgate.api.dto.DeviceDtos.RegisterFcmTokenResponse>() {
                    @Override
                    public void onSuccess(com.unlam.pawgate.api.dto.DeviceDtos.RegisterFcmTokenResponse r) {
                        PrefsHelper.clearPendingFcmToken(LoginActivity.this);
                        android.util.Log.i("LoginActivity",
                                "Pending FCM token registered post-login: " + r.endpoint_arn);
                    }
                    @Override public void onError(String message) {
                        android.util.Log.w("LoginActivity",
                                "Pending FCM token registration failed: " + message);
                    }
                });
    }

    private void goToDashboard(String emailValue) {
        // Arrancamos el Service de polling para toda la sesion. Se detiene en logout.
        PawGatePollingService.start(this);

        Intent i = new Intent(this, DashboardActivity.class);
        if (emailValue != null) {
            i.putExtra(EXTRA_USER, emailValue);
        }
        startActivity(i);
        finish();
    }
}
