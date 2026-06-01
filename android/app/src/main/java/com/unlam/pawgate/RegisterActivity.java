package com.unlam.pawgate;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class RegisterActivity extends AppCompatActivity {
    private EditText name;
    private EditText email;
    private EditText password;
    private EditText confirmPassword;
    private FrameLayout registerBack;
    private Button registerSubmit;
    private TextView registerLoginLink;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        this.name = findViewById(R.id.register_name_input);
        this.email = findViewById(R.id.register_email_input);
        this.password = findViewById(R.id.register_password_input);
        this.confirmPassword = findViewById(R.id.register_confirm_password_input);
        this.registerBack = findViewById(R.id.register_back_button);
        this.registerSubmit = findViewById(R.id.register_submit_button);
        this.registerLoginLink = findViewById(R.id.register_login_link);

        this.registerBack.setOnClickListener(v -> finish());
        this.registerSubmit.setOnClickListener(v -> {
            if (!validateForm()) {
                return;
            }

            Intent intent = new Intent(this, DashboardActivity.class);
            intent.putExtra("name", getNameValue());
            intent.putExtra("email", getEmailValue());

            startActivity(intent);
            finish();
        });
        this.registerLoginLink.setOnClickListener(v -> finish());
    }

    private boolean validateForm() {
        clearErrors();

        boolean isValid = true;
        EditText firstInvalidField = null;

        String nameValue = getNameValue();
        String emailValue = getEmailValue();
        String passwordValue = getPasswordValue();
        String confirmPasswordValue = getConfirmPasswordValue();

        if (nameValue.isEmpty()) {
            name.setError("El nombre es obligatorio");
            firstInvalidField = name;
            isValid = false;
        } else if (nameValue.length() < 2) {
            name.setError("El nombre debe tener al menos 2 caracteres");
            firstInvalidField = name;
            isValid = false;
        }

        if (emailValue.isEmpty()) {
            email.setError("El email es obligatorio");
            if (firstInvalidField == null) firstInvalidField = email;
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            email.setError("Ingresá un email válido");
            if (firstInvalidField == null) firstInvalidField = email;
            isValid = false;
        }

        if (passwordValue.isEmpty()) {
            password.setError("La contraseña es obligatoria");
            if (firstInvalidField == null) firstInvalidField = password;
            isValid = false;
        } else if (!isValidPassword(passwordValue)) {
            password.setError("Debe tener mínimo 8 caracteres, una mayúscula, una minúscula y un número");
            if (firstInvalidField == null) firstInvalidField = password;
            isValid = false;
        }

        if (confirmPasswordValue.isEmpty()) {
            confirmPassword.setError("Confirmá la contraseña");
            if (firstInvalidField == null) firstInvalidField = confirmPassword;
            isValid = false;
        } else if (!passwordValue.equals(confirmPasswordValue)) {
            confirmPassword.setError("Las contraseñas no coinciden");
            if (firstInvalidField == null) firstInvalidField = confirmPassword;
            isValid = false;
        }

        if (firstInvalidField != null) {
            firstInvalidField.requestFocus();
        }

        return isValid;
    }

    private void clearErrors() {
        name.setError(null);
        email.setError(null);
        password.setError(null);
        confirmPassword.setError(null);
    }

    private boolean isValidPassword(String value) {
        boolean hasMinimumLength = value.length() >= 8;
        boolean hasUppercase = value.matches(".*[A-Z].*");
        boolean hasLowercase = value.matches(".*[a-z].*");
        boolean hasNumber = value.matches(".*\\d.*");

        return hasMinimumLength && hasUppercase && hasLowercase && hasNumber;
    }

    private String getNameValue() {
        return name.getText().toString().trim();
    }

    private String getEmailValue() {
        return email.getText().toString().trim();
    }

    private String getPasswordValue() {
        return password.getText().toString();
    }

    private String getConfirmPasswordValue() {
        return confirmPassword.getText().toString();
    }
}