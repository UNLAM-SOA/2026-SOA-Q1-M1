package com.unlam.pawgate;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Modal con 2 opciones para elegir hacia donde se abre la puerta:
 *   - "in"  -> hacia adentro (mascota entra a casa)
 *   - "out" -> hacia afuera (mascota sale al patio)
 *
 * Uso:
 *   OpenDirectionBottomSheet.show(getSupportFragmentManager(), dir -> {...});
 */
public class OpenDirectionBottomSheet extends BottomSheetDialogFragment {

    public interface OnDirectionSelectedListener {
        void onDirectionSelected(String direction);
    }

    private OnDirectionSelectedListener listener;

    public static void show(@NonNull FragmentManager fm, @NonNull OnDirectionSelectedListener listener) {
        OpenDirectionBottomSheet sheet = new OpenDirectionBottomSheet();
        sheet.listener = listener;
        sheet.show(fm, "OpenDirectionBottomSheet");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_open_direction, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.bs_open_dir_in).setOnClickListener(v -> select("in"));
        view.findViewById(R.id.bs_open_dir_out).setOnClickListener(v -> select("out"));
    }

    private void select(String direction) {
        if (listener != null) listener.onDirectionSelected(direction);
        dismiss();
    }
}
