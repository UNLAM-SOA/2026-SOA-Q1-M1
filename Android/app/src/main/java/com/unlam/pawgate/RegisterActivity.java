package com.unlam.pawgate;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.AuthRepository;
import com.unlam.pawgate.api.dto.AuthDtos;

public class RegisterActivity extends AppCompatActivity {

    private EditText name;
    private EditText email;
    private EditText password;
    private EditText confirmPassword;
    private FrameLayout registerBack;
    private Button registerSubmit;
    private TextView registerLoginLink;

    private AuthRepository authRepo;
    // Email usado para signup, lo necesitamos al confirmar el codigo
    private String pendingEmail;

    // Key del Bundle para sobrevivir a rotacion / process death.
    private static final String STATE_PENDING_EMAIL = "pending_email";

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

        this.authRepo = new AuthRepository(this);

        this.registerBack.setOnClickListener(v -> finish());
        this.registerLoginLink.setOnClickListener(v -> finish());
        this.registerSubmit.setOnClickListener(v -> onSubmit());

        // Restauracion post rotacion: si veniamos con un signup confirmado
        // pendiente, reabrimos el dialog automaticamente.
        if (savedInstanceState != null) {
            this.pendingEmail = savedInstanceState.getString(STATE_PENDING_EMAIL);
            if (this.pendingEmail != null) {
                showConfirmationDialog(this.pendingEmail);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingEmail != null) {
            outState.putString(STATE_PENDING_EMAIL, pendingEmail);
        }
    }

    // ============================================================
    // Submit: dispara signup contra Cognito
    // ============================================================
    private void onSubmit() {
        if (!validateForm()) return;

        final String emailValue = getEmailValue();
        final String passwordValue = getPasswordValue();
        final String nameValue = getNameValue();

        setLoading(true);

        authRepo.signup(emailValue, passwordValue, nameValue, new ApiCallback<AuthDtos.SignupResponse>() {
            @Override
            public void onSuccess(AuthDtos.SignupResponse result) {
                setLoading(false);
                Toast.makeText(RegisterActivity.this, R.string.register_signup_ok, Toast.LENGTH_SHORT).show();
                // Guardamos email para el confirm
                pendingEmail = emailValue;
                // Persistimos email "tentativo" para que la pantalla de Ajustes lo lea
                // si el user despues hace login sin re-tipear
                PrefsHelper.setUserEmail(RegisterActivity.this, emailValue);
                showConfirmationDialog(emailValue);
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(RegisterActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ============================================================
    // Dialog: el user ingresa el codigo que recibio por email
    // ============================================================
    private void showConfirmationDialog(String emailValue) {
        final EditText codeInput = new EditText(this);
        codeInput.setHint(R.string.register_confirm_dialog_hint);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        // Container con padding para que no quede el input pegado al borde del dialog
        int paddingPx = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(paddingPx, paddingPx / 2, paddingPx, 0);
        container.addView(codeInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.register_confirm_dialog_title)
                .setMessage(getString(R.string.register_confirm_dialog_message, emailValue))
                .setView(container)
                .setCancelable(false)
                .setPositiveButton(R.string.register_confirm_dialog_confirm, null) // override mas abajo
                .setNegativeButton(R.string.register_confirm_dialog_cancel, (d, w) -> {
                    // Limpiamos el pending asi al rotar no re-abrimos el dialog.
                    pendingEmail = null;
                })
                .create();

        dialog.show();

        // Override del positive button para NO cerrar el dialog si hay error de validacion
        // (el comportamiento default cierra siempre)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (code.length() < 6) {
                codeInput.setError(getString(R.string.register_confirm_code_required));
                return;
            }
            doConfirm(dialog, code);
        });
    }

    private void doConfirm(AlertDialog dialog, String code) {
        if (pendingEmail == null) return;

        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        positive.setEnabled(false);
        negative.setEnabled(false);

        authRepo.confirm(pendingEmail, code, new ApiCallback<AuthDtos.ConfirmResponse>() {
            @Override
            public void onSuccess(AuthDtos.ConfirmResponse result) {
                dialog.dismiss();
                Toast.makeText(RegisterActivity.this, R.string.register_confirm_ok, Toast.LENGTH_LONG).show();
                // Volvemos al Login. El user ingresa sus creds y a partir de ahi
                // empieza el flow normal con tokens.
                finish();
            }

            @Override
            public void onError(String message) {
                positive.setEnabled(true);
                negative.setEnabled(true);
                Toast.makeText(RegisterActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ============================================================
    // UI helpers
    // ============================================================
    private void setLoading(boolean loading) {
        registerSubmit.setEnabled(!loading);
        registerSubmit.setText(loading ? R.string.loading : R.string.register_button);
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
