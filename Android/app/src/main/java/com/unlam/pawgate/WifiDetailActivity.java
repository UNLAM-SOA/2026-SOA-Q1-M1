package com.unlam.pawgate;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

/**
 * W14 - Detalle red WiFi + provisioning mock.
 *
 * Muestra la red a la que esta conectado el ESP32 (datos del mismo
 * endpoint /info que W15, que viene de la telemetria del device).
 *
 * Acciones:
 *  - "Cambiar red WiFi": abre WifiProvisioningBottomSheet con form mock
 *    SSID+password. NO envia nada al device por ahora porque el
 *    provisioning real requiere BLE o AP-mode (Fase 14).
 *  - "Olvidar red": confirm dialog. Mock por las mismas razones.
 */
public class WifiDetailActivity extends AppCompatActivity {

    private static final long POLL_INTERVAL_MS = 10_000L;
    private static final String BUILD_TAG = "v2026-06-08-r1-w14";

    private DeviceRepository deviceRepo;
    private String deviceId;

    private TextView ssidName;
    private TextView statusText;
    private View statusDot;

    private TextView vSsid, vBand, vBssid, vIp, vGateway, vSecurity, vRssi;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            loadInfo();
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_detail);
        android.util.Log.d("WifiDetailActivity", "onCreate build=" + BUILD_TAG);

        deviceRepo = new DeviceRepository(this);
        deviceId = getString(R.string.default_device_id);

        ssidName   = findViewById(R.id.wifi_ssid_name);
        statusText = findViewById(R.id.wifi_status_text);
        statusDot  = findViewById(R.id.wifi_status_dot);

        bindRow(R.id.wifi_row_ssid,     R.string.wifi_detail_row_ssid);
        bindRow(R.id.wifi_row_band,     R.string.wifi_detail_row_band);
        bindRow(R.id.wifi_row_bssid,    R.string.wifi_detail_row_bssid);
        bindRow(R.id.wifi_row_ip,       R.string.wifi_detail_row_ip);
        bindRow(R.id.wifi_row_gateway,  R.string.wifi_detail_row_gateway);
        bindRow(R.id.wifi_row_security, R.string.wifi_detail_row_security);
        bindRow(R.id.wifi_row_rssi,     R.string.wifi_detail_row_rssi);

        vSsid     = valueOf(R.id.wifi_row_ssid);
        vBand     = valueOf(R.id.wifi_row_band);
        vBssid    = valueOf(R.id.wifi_row_bssid);
        vIp       = valueOf(R.id.wifi_row_ip);
        vGateway  = valueOf(R.id.wifi_row_gateway);
        vSecurity = valueOf(R.id.wifi_row_security);
        vRssi     = valueOf(R.id.wifi_row_rssi);

        findViewById(R.id.wifi_back).setOnClickListener(v -> finish());
        findViewById(R.id.wifi_btn_change).setOnClickListener(v -> openProvisioning());
        findViewById(R.id.wifi_btn_forget).setOnClickListener(v -> confirmForget());
    }

    private void bindRow(int rowId, int labelRes) {
        View row = findViewById(rowId);
        TextView label = row.findViewById(R.id.row_kv_label);
        TextView value = row.findViewById(R.id.row_kv_value);
        label.setText(labelRes);
        value.setText(R.string.device_detail_value_unavailable);
    }

    private TextView valueOf(int rowId) {
        return findViewById(rowId).findViewById(R.id.row_kv_value);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void loadInfo() {
        deviceRepo.deviceInfo(deviceId, new ApiCallback<DeviceDtos.DeviceInfoResponse>() {
            @Override public void onSuccess(DeviceDtos.DeviceInfoResponse info) {
                render(info);
            }
            @Override public void onError(String message) {
                android.util.Log.w("WifiDetailActivity", "deviceInfo error: " + message);
                statusText.setText(R.string.wifi_detail_status_offline);
                statusDot.setBackgroundResource(R.drawable.bg_status_dot_offline);
            }
        });
    }

    private void render(DeviceDtos.DeviceInfoResponse info) {
        String ssid = info.wifi_ssid != null && !info.wifi_ssid.isEmpty()
                ? info.wifi_ssid : "—";
        ssidName.setText(ssid);

        if (info.online && !ssid.equals("—")) {
            statusText.setText(R.string.wifi_detail_status_online);
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_online);
        } else {
            statusText.setText(R.string.wifi_detail_status_offline);
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_offline);
        }

        vSsid.setText(ssid);
        vBand.setText(notEmpty(info.wifi_band));
        vBssid.setText(notEmpty(info.wifi_bssid));
        vIp.setText(notEmpty(info.local_ip));
        vGateway.setText(notEmpty(info.wifi_gateway));
        vSecurity.setText(notEmpty(info.wifi_security));
        // RSSI: mostrar dBm + descripcion humana
        if (info.rssi_dbm != 0) {
            vRssi.setText(info.rssi_dbm + " dBm · " + describeRssi(info.rssi_dbm));
        } else {
            vRssi.setText(R.string.device_detail_value_unavailable);
        }
    }

    private String notEmpty(String s) {
        return (s == null || s.isEmpty()) ? getString(R.string.device_detail_value_unavailable) : s;
    }

    /** Categoria humana del RSSI segun rangos tipicos para WiFi en 2.4/5 GHz. */
    private String describeRssi(int dbm) {
        if (dbm >= -50) return getString(R.string.wifi_detail_signal_excellent);
        if (dbm >= -60) return getString(R.string.wifi_detail_signal_good);
        if (dbm >= -70) return getString(R.string.wifi_detail_signal_fair);
        return getString(R.string.wifi_detail_signal_poor);
    }

    private void openProvisioning() {
        WifiProvisioningBottomSheet.show(getSupportFragmentManager(), (ssid, password) -> {
            // Mock: solo notificamos. No publicamos a MQTT porque el firmware
            // todavia no tiene un topic seguro para provisioning.
            Toast.makeText(this, R.string.wifi_provisioning_sent, Toast.LENGTH_LONG).show();
            android.util.Log.i("WifiDetailActivity",
                    "Mock provisioning: ssid=" + ssid + " pwd_len=" + password.length());
        });
    }

    private void confirmForget() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wifi_forget_confirm_title)
                .setMessage(R.string.wifi_forget_confirm_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        Toast.makeText(this, R.string.wifi_provisioning_mock_notice,
                                Toast.LENGTH_LONG).show())
                .show();
    }
}
