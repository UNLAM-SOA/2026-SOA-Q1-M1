package com.unlam.pawgate;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.textfield.TextInputEditText;

/**
 * BottomSheet del flow "Cambiar red WiFi" (W14).
 *
 * Form simple con SSID + password. Cuando el user toca "Conectar", invoca
 * el listener con las credenciales y cierra el sheet.
 *
 * MOCK por ahora: WifiDetailActivity recibe las credenciales y solo muestra
 * un Toast. No publica nada al device porque el provisioning real requiere
 * BLE o AP-mode del ESP32 (no es seguro mandar passwords WiFi sobre MQTT
 * por mas TLS que tenga). Cuando se cierre Fase 14, este flow va a llamar
 * al stack BLE / AP-mode.
 */
public class WifiProvisioningBottomSheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onSubmit(@NonNull String ssid, @NonNull String password);
    }

    private static Listener pendingListener;

    public static void show(FragmentManager fm, @NonNull Listener listener) {
        pendingListener = listener;
        new WifiProvisioningBottomSheet().show(fm, "wifi_provisioning");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        return new BottomSheetDialog(requireContext(), getTheme());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_wifi_provisioning, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final TextInputEditText ssidInput = view.findViewById(R.id.wifi_prov_ssid);
        final TextInputEditText pwdInput  = view.findViewById(R.id.wifi_prov_password);

        view.findViewById(R.id.wifi_prov_btn_connect).setOnClickListener(v -> {
            String ssid = ssidInput.getText() != null ? ssidInput.getText().toString().trim() : "";
            String pwd  = pwdInput.getText()  != null ? pwdInput.getText().toString()         : "";
            if (ssid.isEmpty()) {
                Toast.makeText(requireContext(), R.string.wifi_provisioning_ssid_hint,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (pendingListener != null) {
                pendingListener.onSubmit(ssid, pwd);
                pendingListener = null;
            }
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Por las dudas: no queremos mantener el listener (memory leak) si el
        // sheet se cierra sin haber disparado.
        pendingListener = null;
    }
}
