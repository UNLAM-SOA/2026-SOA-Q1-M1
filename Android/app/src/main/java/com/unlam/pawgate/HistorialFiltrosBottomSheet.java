package com.unlam.pawgate;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Filtros avanzados del Historial (W13).
 *
 * Permite al user:
 *   - elegir rango personalizado de fechas (MaterialDatePicker.Builder.dateRangePicker)
 *   - togglear "incluir telemetria de sensores"
 *
 * Devuelve los filtros al caller via OnFiltrosAppliedListener.
 *
 * El estado inicial se pasa por constructor (Filtros currentFilters) para que
 * el sheet abra reflejando lo que ya hay aplicado.
 */
public class HistorialFiltrosBottomSheet extends BottomSheetDialogFragment {

    /** Modelo de filtros. Inmutable a proposito. */
    public static final class Filtros {
        public final Long fromMs;
        public final Long toMs;
        public final boolean includeSensors;

        public Filtros(Long fromMs, Long toMs, boolean includeSensors) {
            this.fromMs = fromMs;
            this.toMs = toMs;
            this.includeSensors = includeSensors;
        }

        public static Filtros empty() {
            return new Filtros(null, null, false);
        }

        public boolean hasCustomRange() { return fromMs != null && toMs != null; }
    }

    public interface OnFiltrosAppliedListener {
        void onFiltrosApplied(Filtros filtros);
    }

    private OnFiltrosAppliedListener listener;
    private Filtros initial;

    // Estado de edicion (cambia con cada interaccion del user)
    private Long editingFromMs;
    private Long editingToMs;
    private boolean editingIncludeSensors;

    public static void show(@NonNull FragmentManager fm,
                            @NonNull Filtros currentFilters,
                            @NonNull OnFiltrosAppliedListener listener) {
        HistorialFiltrosBottomSheet sheet = new HistorialFiltrosBottomSheet();
        sheet.initial = currentFilters;
        sheet.listener = listener;
        sheet.show(fm, "HistorialFiltrosBottomSheet");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_filtros_historial, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (initial == null) initial = Filtros.empty();
        editingFromMs = initial.fromMs;
        editingToMs = initial.toMs;
        editingIncludeSensors = initial.includeSensors;

        TextView rangeValue = view.findViewById(R.id.filtros_range_value);
        renderRangeValue(rangeValue);

        view.findViewById(R.id.filtros_pick_range).setOnClickListener(v -> openDateRangePicker(rangeValue));

        MaterialSwitch sensorsSwitch = view.findViewById(R.id.filtros_include_sensors_switch);
        sensorsSwitch.setChecked(editingIncludeSensors);
        sensorsSwitch.setOnCheckedChangeListener((b, checked) -> editingIncludeSensors = checked);

        view.findViewById(R.id.filtros_apply).setOnClickListener(v -> {
            if (listener != null) {
                listener.onFiltrosApplied(new Filtros(editingFromMs, editingToMs, editingIncludeSensors));
            }
            dismiss();
        });

        view.findViewById(R.id.filtros_clear).setOnClickListener(v -> {
            if (listener != null) listener.onFiltrosApplied(Filtros.empty());
            dismiss();
        });
    }

    private void openDateRangePicker(TextView rangeValueView) {
        MaterialDatePicker.Builder<Pair<Long, Long>> builder = MaterialDatePicker.Builder.dateRangePicker()
                .setTitleText(R.string.filtros_section_range);
        if (editingFromMs != null && editingToMs != null) {
            builder.setSelection(new Pair<>(editingFromMs, editingToMs));
        }
        MaterialDatePicker<Pair<Long, Long>> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
            editingFromMs = selection.first;
            editingToMs = selection.second;
            renderRangeValue(rangeValueView);
        });
        picker.show(getParentFragmentManager(), "FiltrosDateRangePicker");
    }

    private void renderRangeValue(TextView view) {
        if (editingFromMs != null && editingToMs != null) {
            SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
            view.setText(getString(R.string.filtros_range_summary,
                    fmt.format(new Date(editingFromMs)),
                    fmt.format(new Date(editingToMs))));
        } else {
            view.setText(R.string.filtros_pick_range);
        }
    }
}
