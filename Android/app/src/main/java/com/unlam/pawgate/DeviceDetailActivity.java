package com.unlam.pawgate;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.dto.DeviceDtos;

import java.util.Collections;
import java.util.Locale;

/**
 * W15 - Detalle ESP32 + sensores.
 *
 * Pantalla que muestra la telemetria del dispositivo. La data viene de
 * GET /devices/{id}/info, que devuelve el ultimo snapshot que publico el
 * device en el topic `pawgate/{id}/events/telemetry` (cada 30s).
 *
 * Actions:
 *  - Reiniciar: POST /devices/{id}/cmd/reboot. Confirma con dialog antes.
 *  - Buscar actualizacion OTA: por ahora solo muestra un toast 'estas en la
 *    ultima version'. En el futuro podria llamar a un endpoint /info/ota.
 *
 * Polling: cada {@link #POLL_INTERVAL_MS} mientras la pantalla esta visible.
 * No hay foreground service: el polling local con Handler cubre el caso
 * 'mientras el user mira la pantalla'. El telemetry interval del simulator
 * (30s) hace que pollear cada 5s no sea super util, pero asi vemos el
 * 'online/offline' transition al apagar el simulator.
 */
public class DeviceDetailActivity extends AppCompatActivity {

    private static final long POLL_INTERVAL_MS = 10_000L;
    private static final String BUILD_TAG = "v2026-06-08-r1-w15";

    private DeviceRepository deviceRepo;
    private String deviceId;

    // Views del header
    private TextView modelName;
    private TextView statusText;
    private View statusDot;

    // Rows. Cada row es un include con 2 TextView dentro: label + value.
    private TextView uptimeValue;
    private TextView cpuValue;
    private TextView ramValue;
    private TextView flashValue;
    private TextView ipValue;
    private TextView rssiValue;

    // Polling
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            loadDeviceInfo();
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_detail);
        android.util.Log.d("DeviceDetailActivity", "onCreate build=" + BUILD_TAG);

        deviceRepo = new DeviceRepository(this);
        deviceId = getString(R.string.default_device_id);

        // Header
        modelName  = findViewById(R.id.device_model_name);
        statusText = findViewById(R.id.device_status_text);
        statusDot  = findViewById(R.id.device_status_dot);

        // Rows: cada include trae adentro row_kv_label / row_kv_value. Para
        // bindear, busco la View del include por su id y desde ahi findViewById
        // adentro hasta los TextView. Asi reuso row_device_info_kv.xml sin
        // tener que escribir 6 IDs distintos.
        bindRow(R.id.row_uptime,   R.string.device_detail_row_uptime);
        bindRow(R.id.row_cpu_temp, R.string.device_detail_row_cpu);
        bindRow(R.id.row_ram,      R.string.device_detail_row_ram);
        bindRow(R.id.row_flash,    R.string.device_detail_row_flash);
        bindRow(R.id.row_ip,       R.string.device_detail_row_ip);
        bindRow(R.id.row_rssi,     R.string.device_detail_row_rssi);

        uptimeValue = findValueOf(R.id.row_uptime);
        cpuValue    = findValueOf(R.id.row_cpu_temp);
        ramValue    = findValueOf(R.id.row_ram);
        flashValue  = findValueOf(R.id.row_flash);
        ipValue     = findValueOf(R.id.row_ip);
        rssiValue   = findValueOf(R.id.row_rssi);

        // Topbar
        findViewById(R.id.device_back).setOnClickListener(v -> finish());

        // Actions
        findViewById(R.id.device_btn_ota).setOnClickListener(v ->
                Toast.makeText(this, R.string.device_detail_ota_no_update,
                        Toast.LENGTH_SHORT).show());
        findViewById(R.id.device_btn_reboot).setOnClickListener(v -> confirmReboot());
    }

    private void bindRow(int rowId, int labelStringRes) {
        View row = findViewById(rowId);
        TextView label = row.findViewById(R.id.row_kv_label);
        TextView value = row.findViewById(R.id.row_kv_value);
        label.setText(labelStringRes);
        value.setText(R.string.device_detail_value_unavailable);
    }

    private TextView findValueOf(int rowId) {
        return findViewById(rowId).findViewById(R.id.row_kv_value);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Arrancar polling (carga inmediata + tick cada POLL_INTERVAL_MS)
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Frenar polling cuando salimos de la pantalla (consume bateria + api calls)
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void loadDeviceInfo() {
        deviceRepo.deviceInfo(deviceId, new ApiCallback<DeviceDtos.DeviceInfoResponse>() {
            @Override public void onSuccess(DeviceDtos.DeviceInfoResponse info) {
                renderInfo(info);
            }
            @Override public void onError(String message) {
                android.util.Log.w("DeviceDetailActivity", "deviceInfo error: " + message);
                // Marcar offline visualmente; no Toast para no spamear al pollear.
                renderOffline();
            }
        });
    }

    private void renderInfo(DeviceDtos.DeviceInfoResponse info) {
        // Header
        String model = info.hardware_model != null && !info.hardware_model.isEmpty()
                ? info.hardware_model : "ESP32";
        modelName.setText(model);

        if (info.online) {
            String fw = info.firmware_version != null && !info.firmware_version.isEmpty()
                    ? info.firmware_version : "—";
            statusText.setText(getString(R.string.device_detail_status_online, fw));
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_online);
        } else {
            statusText.setText(R.string.device_detail_status_offline);
            statusDot.setBackgroundResource(R.drawable.bg_status_dot_offline);
        }

        // Rows
        uptimeValue.setText(formatUptime(info.uptime_s));
        cpuValue.setText(info.cpu_temp_c != null && !info.cpu_temp_c.isEmpty()
                ? info.cpu_temp_c + " °C"
                : getString(R.string.device_detail_value_unavailable));
        ramValue.setText(String.format(Locale.US, "%d / %d KB",
                info.free_heap_kb, info.total_heap_kb));
        // Flash mostramos en MB para que sea legible (1024 KB = 1 MB)
        double usedMb  = info.flash_used_kb  / 1024.0;
        double totalMb = info.flash_total_kb / 1024.0;
        flashValue.setText(String.format(Locale.US, "%.1f / %.0f MB", usedMb, totalMb));
        ipValue.setText(info.local_ip != null && !info.local_ip.isEmpty()
                ? info.local_ip
                : getString(R.string.device_detail_value_unavailable));
        rssiValue.setText(info.rssi_dbm != 0
                ? info.rssi_dbm + " dBm"
                : getString(R.string.device_detail_value_unavailable));
    }

    /** Caso 'no se pudo cargar info': mostramos badge offline pero dejamos las rows en —. */
    private void renderOffline() {
        statusText.setText(R.string.device_detail_status_offline);
        statusDot.setBackgroundResource(R.drawable.bg_status_dot_offline);
    }

    /** Convierte segundos a 'Xd Yh Zmin'. Si <1h muestra solo min. Si <1min, '<1 min'. */
    private String formatUptime(long uptimeSeconds) {
        if (uptimeSeconds <= 0) return getString(R.string.device_detail_value_unavailable);
        long days = uptimeSeconds / 86400;
        long hours = (uptimeSeconds % 86400) / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        if (days > 0) return String.format(Locale.US, "%dd · %dh · %dmin", days, hours, minutes);
        if (hours > 0) return String.format(Locale.US, "%dh · %dmin", hours, minutes);
        if (minutes > 0) return String.format(Locale.US, "%d min", minutes);
        return "<1 min";
    }

    private void confirmReboot() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.device_detail_reboot_confirm_title)
                .setMessage(R.string.device_detail_reboot_confirm_msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.device_detail_reboot_confirm_ok, (d, w) -> doReboot())
                .show();
    }

    private void doReboot() {
        deviceRepo.sendCommand(deviceId, "reboot", Collections.emptyMap(),
                new ApiCallback<DeviceDtos.CommandResponse>() {
                    @Override public void onSuccess(DeviceDtos.CommandResponse result) {
                        Toast.makeText(DeviceDetailActivity.this,
                                R.string.device_detail_reboot_sent, Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(String message) {
                        Toast.makeText(DeviceDetailActivity.this,
                                message, Toast.LENGTH_LONG).show();
                    }
                });
    }
}
