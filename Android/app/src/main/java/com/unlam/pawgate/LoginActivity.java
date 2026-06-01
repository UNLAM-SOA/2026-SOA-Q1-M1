package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {
    private EditText email;
    private EditText password;
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        this.email = findViewById(R.id.login_email_input);
        this.password = findViewById(R.id.login_password_input);
        Button login = findViewById(R.id.login_submit_button);
        TextView loginRegisterLink = findViewById(R.id.login_register_link);

        login.setOnClickListener(v -> {
            String emailValue = this.email.getText().toString();
            // Persistimos el email para que cualquier Activity (Ajustes, etc.) pueda
            // leerlo sin tener que pasarlo por Intent extras a traves de toda la app.
            PrefsHelper.setUserEmail(this, emailValue);

            Intent i = new Intent(this, DashboardActivity.class);
            i.putExtra(EXTRA_USER, emailValue);
            i.putExtra(EXTRA_PASSWORD, this.password.getText().toString());
            startActivity(i);
            finish();
        });

        loginRegisterLink.setOnClickListener(v -> {
            Intent i = new Intent(this, RegisterActivity.class);
            startActivity(i);
        });
    }
}
