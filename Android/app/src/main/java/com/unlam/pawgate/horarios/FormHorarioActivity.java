package com.unlam.pawgate.horarios;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.unlam.pawgate.R;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.ScheduleDtos;

/**
 * Alta / edicion de un horario contra el backend.
 *
 * Componentes Material:
 *   TextInputLayout outlined (nombre con error inline)
 *   MaterialSwitch (activo)
 *   MaterialTimePicker CLOCK_24H (hora inicio / fin)
 *   DeleteHorarioBottomSheet (confirmacion modal personalizada)
 *
 * Validacion (3):
 *   1) nombre min 3 caracteres
 *   2) al menos 1 dia seleccionado
 *   3) hora_inicio != hora_fin (cruce de medianoche permitido)
 *
 * Restriccion 30min: el TimePicker permite cualquier minuto, pero al confirmar
 * snapeamos al multiplo de 30 mas cercano. Mostramos un toast para que el user
 * sepa que su seleccion fue ajustada.
 *
 * Persistencia: DeviceRepository (POST /schedules, PUT /schedules/{id}, DELETE).
 *
 * savedInstanceState: serializamos el Horario actual a JSON para sobrevivir
 * rotacion mid-form sin perder los cambios sin guardar.
 */
public class FormHorarioActivity extends AppCompatActivity {

    public static final String EXTRA_HORARIO_ID = "horario_id";
    public static final String EXTRA_HORARIO_JSON = "horario_json";

    private static final String STATE_HORARIO_JSON = "horario_json";

    private static final int[] DIAS_BITS = {
            Horario.LUN, Horario.MAR, Horario.MIE, Horario.JUE,
            Horario.VIE, Horario.SAB, Horario.DOM
    };
    private static final int[] DIAS_LABELS = {
            R.string.horarios_day_l, R.string.horarios_day_m1, R.string.horarios_day_x,
            R.string.horarios_day_j, R.string.horarios_day_v,
            R.string.horarios_day_s, R.string.horarios_day_d
    };

    private DeviceRepository deviceRepo;
    private String deviceId;
    private Horario horario;
    private boolean isEditMode;
    private boolean inFlight;

    private TextView titleView;
    private TextInputLayout nombreLayout;
    private TextInputEditText nombreInput;
    private TextView horaInicioValue;
    private TextView horaFinValue;
    private TextView horasError;
    private LinearLayout diasContainer;
    private TextView diasError;
    private MaterialSwitch activoSwitch;
    private View deleteButton;
    private com.google.android.material.button.MaterialButton saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_form_horario);

        this.deviceRepo = new DeviceRepository(this);
        this.deviceId = getString(R.string.default_device_id);
        bindViews();

        // Modo: edit si llega un id (y si trae el json del horario, lo usamos
        // directo sin tener que volver a pedir al backend).
        String editId = getIntent().getStringExtra(EXTRA_HORARIO_ID);
        String preloadedJson = getIntent().getStringExtra(EXTRA_HORARIO_JSON);

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_HORARIO_JSON)) {
            this.horario = new com.google.gson.Gson().fromJson(
                    savedInstanceState.getString(STATE_HORARIO_JSON), Horario.class);
            this.isEditMode = editId != null;
        } else if (editId != null && preloadedJson != null) {
            this.horario = new com.google.gson.Gson().fromJson(preloadedJson, Horario.class);
            this.isEditMode = true;
        } else if (editId != null) {
            this.horario = Horario.nuevo();
            this.horario.id = editId;
            this.isEditMode = true;
        } else {
            this.horario = Horario.nuevo();
            this.isEditMode = false;
        }

        renderInitialState();
        wireListeners();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (nombreInput != null && nombreInput.getText() != null) {
            horario.nombre = nombreInput.getText().toString().trim();
        }
        outState.putString(STATE_HORARIO_JSON, new com.google.gson.Gson().toJson(horario));
    }

    // ============================================================
    // BINDING + RENDER
    // ============================================================

    private void bindViews() {
        titleView = findViewById(R.id.form_title);
        nombreLayout = findViewById(R.id.form_nombre_layout);
        nombreInput = findViewById(R.id.form_nombre);
        horaInicioValue = findViewById(R.id.form_hora_inicio_value);
        horaFinValue = findViewById(R.id.form_hora_fin_value);
        horasError = findViewById(R.id.form_horas_error);
        diasContainer = findViewById(R.id.form_dias_container);
        diasError = findViewById(R.id.form_dias_error);
        activoSwitch = findViewById(R.id.form_activo_switch);
        deleteButton = findViewById(R.id.form_delete_button);
        saveButton = findViewById(R.id.form_save_button);
    }

    private void renderInitialState() {
        titleView.setText(isEditMode
                ? R.string.form_horario_title_editar
                : R.string.form_horario_title_nuevo);

        nombreInput.setText(horario.nombre);
        horaInicioValue.setText(horario.formatHoraInicio());
        horaFinValue.setText(horario.formatHoraFin());
        activoSwitch.setChecked(horario.activo);
        deleteButton.setVisibility(isEditMode ? View.VISIBLE : View.GONE);

        renderDiasChips();
    }

    private void renderDiasChips() {
        diasContainer.removeAllViews();
        int chipSize = dp(40);
        int gap = dp(6);
        for (int i = 0; i < DIAS_BITS.length; i++) {
            final int bit = DIAS_BITS[i];
            TextView chip = new TextView(this);
            chip.setText(DIAS_LABELS[i]);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTextSize(13);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(chipSize, chipSize);
            if (i > 0) lp.setMarginStart(gap);
            chip.setLayoutParams(lp);
            applyDayChipStyle(chip, horario.tieneDia(bit));
            chip.setOnClickListener(v -> {
                horario.toggleDia(bit);
                applyDayChipStyle((TextView) v, horario.tieneDia(bit));
                if (horario.diasBitmask != 0) diasError.setVisibility(View.GONE);
            });
            diasContainer.addView(chip);
        }
    }

    private void applyDayChipStyle(TextView chip, boolean active) {
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_day_active);
            chip.setTextColor(ContextCompat.getColor(this, R.color.bg_card));
        } else {
            chip.setBackgroundResource(R.drawable.bg_day_inactive);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    private void wireListeners() {
        findViewById(R.id.form_back).setOnClickListener(v -> finish());
        findViewById(R.id.form_cancel_button).setOnClickListener(v -> finish());
        findViewById(R.id.form_hora_inicio_picker).setOnClickListener(v -> openTimePicker(true));
        findViewById(R.id.form_hora_fin_picker).setOnClickListener(v -> openTimePicker(false));

        activoSwitch.setOnCheckedChangeListener((b, checked) -> horario.activo = checked);

        nombreInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                horario.nombre = s.toString();
                if (s.length() > 0) nombreLayout.setError(null);
            }
        });

        saveButton.setOnClickListener(v -> onSaveClick());
        deleteButton.setOnClickListener(v -> openDeleteSheet());
    }

    // ============================================================
    // TIME PICKER + SNAP a 30min
    // ============================================================

    private void openTimePicker(boolean isInicio) {
        int initialHour = isInicio ? horario.horaInicio : horario.horaFin;
        int initialMin  = isInicio ? horario.minutoInicio : horario.minutoFin;

        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(initialHour)
                .setMinute(initialMin)
                .setTitleText(isInicio
                        ? R.string.form_horario_hora_inicio
                        : R.string.form_horario_hora_fin)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            int h = picker.getHour();
            int m = picker.getMinute();
            int snapped = Horario.snapMinuto30(m);
            boolean rolled = Horario.snapRollsOver(m);
            if (rolled) h = (h + 1) % 24;

            if (snapped != m) {
                Toast.makeText(FormHorarioActivity.this,
                        R.string.form_horario_toast_snap_30min,
                        Toast.LENGTH_SHORT).show();
            }

            if (isInicio) {
                horario.horaInicio = h;
                horario.minutoInicio = snapped;
                horaInicioValue.setText(horario.formatHoraInicio());
            } else {
                horario.horaFin = h;
                horario.minutoFin = snapped;
                horaFinValue.setText(horario.formatHoraFin());
            }
            horasError.setVisibility(View.GONE);
        });
        picker.show(getSupportFragmentManager(), "MaterialTimePicker");
    }

    // ============================================================
    // GUARDAR / VALIDAR
    // ============================================================

    private void onSaveClick() {
        if (inFlight) return;
        if (!validate()) return;

        horario.nombre = nombreInput.getText() != null
                ? nombreInput.getText().toString().trim() : "";
        ScheduleDtos.CreateRequest body = HorarioMapper.toCreateRequest(horario);

        setInFlight(true);

        ApiCallback<ScheduleDtos.Schedule> cb = new ApiCallback<ScheduleDtos.Schedule>() {
            @Override
            public void onSuccess(ScheduleDtos.Schedule result) {
                setInFlight(false);
                Toast.makeText(FormHorarioActivity.this,
                        isEditMode ? R.string.form_horario_toast_actualizado
                                   : R.string.form_horario_toast_creado,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override
            public void onError(String message) {
                setInFlight(false);
                Toast.makeText(FormHorarioActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };

        if (isEditMode) {
            deviceRepo.updateSchedule(deviceId, horario.id, body, cb);
        } else {
            deviceRepo.createSchedule(deviceId, body, cb);
        }
    }

    private boolean validate() {
        boolean ok = true;
        String nombre = nombreInput.getText() != null
                ? nombreInput.getText().toString().trim() : "";

        if (nombre.isEmpty()) {
            nombreLayout.setError(getString(R.string.form_horario_err_nombre));
            ok = false;
        } else if (nombre.length() < 3) {
            nombreLayout.setError(getString(R.string.form_horario_err_nombre_corto));
            ok = false;
        } else {
            nombreLayout.setError(null);
        }

        if (horario.diasBitmask == 0) {
            diasError.setVisibility(View.VISIBLE);
            ok = false;
        } else {
            diasError.setVisibility(View.GONE);
        }

        int inicioMin = horario.horaInicio * 60 + horario.minutoInicio;
        int finMin = horario.horaFin * 60 + horario.minutoFin;
        if (inicioMin == finMin) {
            horasError.setVisibility(View.VISIBLE);
            ok = false;
        } else {
            horasError.setVisibility(View.GONE);
        }

        return ok;
    }

    // ============================================================
    // ELIMINAR (BottomSheet modal custom)
    // ============================================================

    private void openDeleteSheet() {
        if (inFlight) return;
        DeleteHorarioBottomSheet.show(getSupportFragmentManager(), this::doDelete);
    }

    private void doDelete() {
        if (horario.id == null) return;
        setInFlight(true);
        deviceRepo.deleteSchedule(deviceId, horario.id, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                setInFlight(false);
                Toast.makeText(FormHorarioActivity.this,
                        R.string.form_horario_toast_eliminado, Toast.LENGTH_SHORT).show();
                finish();
            }
            @Override
            public void onError(String message) {
                setInFlight(false);
                Toast.makeText(FormHorarioActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setInFlight(boolean inFlight) {
        this.inFlight = inFlight;
        saveButton.setEnabled(!inFlight);
        saveButton.setText(inFlight ? R.string.form_horario_saving : R.string.form_horario_guardar);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
