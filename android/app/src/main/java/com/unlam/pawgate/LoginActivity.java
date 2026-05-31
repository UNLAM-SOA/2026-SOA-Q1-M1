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
    private EditText user;
    private EditText password;
    private Button login;
    private TextView loginRegisterLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        this.user = findViewById(R.id.login_email_input);
        this.password = findViewById(R.id.login_password_input);
        this.login = findViewById(R.id.login_submit_button);
        this.loginRegisterLink = findViewById(R.id.login_register_link);

        this.login.setOnClickListener(v -> {
            Intent i = new Intent(this, DashboardActivity.class);
            i.putExtra("user", this.user.getText().toString());
            i.putExtra("password", this.password.getText().toString());
            startActivity(i);
            finish();
        });

        this.loginRegisterLink.setOnClickListener(v -> {
            Intent i = new Intent(this, RegisterActivity.class);
            startActivity(i);
        });
    }


}