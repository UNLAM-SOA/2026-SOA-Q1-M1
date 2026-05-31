package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class LoginActivity extends AppCompatActivity {
    private EditText email;
    private EditText password;
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        this.email = findViewById(R.id.login_email_input);
        this.password = findViewById(R.id.login_password_input);
        Button login = findViewById(R.id.login_submit_button);
        TextView loginRegisterLink = findViewById(R.id.login_register_link);

        login.setOnClickListener(v -> {
            Intent i = new Intent(this, DashboardActivity.class);
            i.putExtra(EXTRA_USER, this.email.getText().toString());
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