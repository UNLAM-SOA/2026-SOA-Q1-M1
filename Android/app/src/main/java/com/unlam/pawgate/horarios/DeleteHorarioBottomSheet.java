package com.unlam.pawgate.horarios;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.unlam.pawgate.R;

/**
 * Modal de confirmacion para eliminar un horario.
 *
 * BottomSheet con icono trash + titulo + mensaje + 2 botones (Eliminar / Cancelar).
 *
 * Uso:
 *   DeleteHorarioBottomSheet.show(getSupportFragmentManager(), () -> { ... });
 */
public class DeleteHorarioBottomSheet extends BottomSheetDialogFragment {

    public interface OnConfirmListener {
        void onConfirmDelete();
    }

    private OnConfirmListener listener;

    public static void show(@NonNull FragmentManager fm, @NonNull OnConfirmListener listener) {
        DeleteHorarioBottomSheet sheet = new DeleteHorarioBottomSheet();
        sheet.listener = listener;
        sheet.show(fm, "DeleteHorarioBottomSheet");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_delete_horario, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.modal_delete_confirm).setOnClickListener(v -> {
            if (listener != null) listener.onConfirmDelete();
            dismiss();
        });
        view.findViewById(R.id.modal_delete_cancel).setOnClickListener(v -> dismiss());
    }
}
