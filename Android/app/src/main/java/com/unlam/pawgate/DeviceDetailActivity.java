package com.unlam.pawgate;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.unlam.pawgate.api.ApiCallback;
import com.unlam.pawgate.api.ApiClient;
import com.unlam.pawgate.api.DeviceRepository;
import com.unlam.pawgate.api.PawGateApi;
import com.unlam.pawgate.api.dto.DeviceDtos;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Response;

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
    private OfflineBanner offlineBanner;

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

    // ===== OTA state =====
    // Lo que dice GET /info (telemetria del device).
    private String currentFirmwareVersion = null;
    // Lo que dice GET /firmware/latest (manifest en S3).
    private DeviceDtos.FirmwareLatestResponse latestFirmware = null;
    // Boton OTA del header (se enable solo cuando hay info loaded).
    private TextView btnOta;
    // Task corriendo (para cancelarla si el user sale de la activity).
    private OtaUpdateTask otaTask = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_detail);
        android.util.Log.d("DeviceDetailActivity", "onCreate build=" + BUILD_TAG);
        offlineBanner = OfflineBanner.attach(this);

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
        btnOta = findViewById(R.id.device_btn_ota);
        btnOta.setOnClickListener(v -> onOtaButtonClick());
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
        if (offlineBanner != null) offlineBanner.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Frenar polling cuando salimos de la pantalla (consume bateria + api calls)
        pollHandler.removeCallbacks(pollRunnable);
        if (offlineBanner != null) offlineBanner.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Si la activity muere mid-OTA, cancelamos la task. El ESP32 va a seguir
        // descargando/aplicando porque ya recibio el cmd MQTT; el lado del user
        // simplemente pierde el trackeo de progreso. Al volver a abrir la pantalla
        // /info va a reportar la version nueva si el OTA termino bien.
        if (otaTask != null) {
            otaTask.cancel(true);
            otaTask = null;
        }
    }

    private void loadDeviceInfo() {
        deviceRepo.deviceInfo(deviceId, new ApiCallback<DeviceDtos.DeviceInfoResponse>() {
            @Override public void onSuccess(DeviceDtos.DeviceInfoResponse info) {
                currentFirmwareVersion = info.firmware_version;
                renderInfo(info);
                refreshOtaButton();
            }
            @Override public void onError(String message) {
                android.util.Log.w("DeviceDetailActivity", "deviceInfo error: " + message);
                // Marcar offline visualmente; no Toast para no spamear al pollear.
                renderOffline();
            }
        });
        // En paralelo, consultar el manifest de S3 para saber si hay update.
        // Lo hacemos en cada poll para que la disponibilidad de un release
        // nuevo se vea reflejada en vivo sin necesidad de salir y volver.
        deviceRepo.firmwareLatest(deviceId,
                new ApiCallback<DeviceDtos.FirmwareLatestResponse>() {
            @Override public void onSuccess(DeviceDtos.FirmwareLatestResponse r) {
                latestFirmware = r;
                refreshOtaButton();
            }
            @Override public void onError(String message) {
                android.util.Log.w("DeviceDetailActivity", "firmwareLatest error: " + message);
                // No es critico, el boton va a quedar en estado "Buscar..."
            }
        });
    }

    /**
     * Decide el texto y el enable del boton OTA segun current vs latest.
     * Lo llamamos cada vez que llega un fetch de /info o /firmware/latest.
     */
    private void refreshOtaButton() {
        if (btnOta == null) return;
        if (otaTask != null) {
            // OTA en curso — el dialog ya muestra el progreso, el boton queda
            // disabled para evitar disparar uno paralelo.
            btnOta.setEnabled(false);
            return;
        }
        if (latestFirmware == null || latestFirmware.latest_version == null
                || latestFirmware.latest_version.isEmpty()) {
            btnOta.setText(R.string.device_detail_btn_ota);
            btnOta.setEnabled(false);
            return;
        }
        // Si todavia no sabemos la version actual, dejamos el boton disabled.
        if (currentFirmwareVersion == null || currentFirmwareVersion.isEmpty()) {
            btnOta.setText(R.string.device_detail_btn_ota);
            btnOta.setEnabled(false);
            return;
        }
        if (currentFirmwareVersion.equals(latestFirmware.latest_version)) {
            btnOta.setText(R.string.device_detail_ota_no_update);
            btnOta.setEnabled(false);
        } else {
            btnOta.setText(getString(R.string.ota_btn_update,
                    latestFirmware.latest_version));
            btnOta.setEnabled(true);
        }
    }

    private void onOtaButtonClick() {
        if (latestFirmware == null) {
            Toast.makeText(this, R.string.device_detail_ota_no_update,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        long sizeKb = Math.max(1L, latestFirmware.size / 1024L);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ota_confirm_title)
                .setMessage(getString(R.string.ota_confirm_msg,
                        latestFirmware.latest_version, String.valueOf(sizeKb)))
                .setPositiveButton(R.string.ota_confirm_ok, (d, w) -> launchOtaTask())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Arranca la tarea OTA + muestra el dialog de progreso. */
    private void launchOtaTask() {
        if (otaTask != null) return; // ya hay una corriendo
        AlertDialog progressDialog = buildOtaProgressDialog();
        progressDialog.show();
        otaTask = new OtaUpdateTask(progressDialog);
        // Importante: AsyncTask por default usa el SERIAL_EXECUTOR, que serializa
        // todas las tasks de la app. Para que esta corra YA sin esperar a que
        // termine cualquier otra, usamos THREAD_POOL_EXECUTOR.
        otaTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        refreshOtaButton();
    }

    private AlertDialog buildOtaProgressDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_ota_progress, null);
        TextView target = view.findViewById(R.id.ota_target_version);
        if (latestFirmware != null) {
            target.setText(getString(R.string.ota_btn_update, latestFirmware.latest_version));
        }
        return new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ota_progress_title)
                .setView(view)
                .setCancelable(false)  // no se puede cancelar mid-OTA
                .create();
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

    // ===========================================================================
    // OTA: AsyncTask
    //
    // Demuestra los tres callbacks clasicos del ciclo de vida de AsyncTask:
    //   - onPreExecute()       (UI thread, antes del background)
    //   - doInBackground()     (worker thread, donde corre la logica de red)
    //   - onProgressUpdate(p)  (UI thread, llamado desde doInBackground
    //                           via publishProgress(...))
    //   - onPostExecute(r)     (UI thread, despues del background)
    //
    // Aclaracion didactica para el parcial: AsyncTask esta DEPRECADO desde
    // Android 11 (API 30). En produccion se reemplazaria por un
    // ExecutorService + Handler para el thread de UI, o por coroutines en
    // Kotlin. Lo usamos porque es lo que se ve en la materia y el patron
    // onPreExecute / doInBackground / onProgressUpdate / onPostExecute es el
    // ejemplo canonico de threading en Android.
    //
    // Flujo del OTA:
    //   1. POST /cmd/ota                          (5%)
    //   2. Poll GET /history filtrando ota_*      (15..85%)
    //      - ota_started   -> 15%
    //      - ota_progress  -> 15 + 0.70 * percent
    //      - ota_success   -> 90%
    //      - ota_failed    -> exit con error
    //   3. Poll GET /info hasta que cambie firmware_version  (95..100%)
    // ===========================================================================

    /** Snapshot del progreso, pasado a onProgressUpdate desde doInBackground. */
    private static final class OtaProgress {
        final int percent;
        final String statusText;
        OtaProgress(int percent, String statusText) {
            this.percent = percent;
            this.statusText = statusText;
        }
    }

    /**
     * Resultado final del AsyncTask. errorMessage == null implica exito.
     */
    private static final class OtaResult {
        final String errorMessage;   // null si OK
        final String newVersion;     // version a la que actualizamos
        OtaResult(String error, String newVersion) {
            this.errorMessage = error;
            this.newVersion = newVersion;
        }
    }

    @SuppressWarnings("deprecation")  // AsyncTask deprecated, lo usamos a proposito
    private class OtaUpdateTask extends AsyncTask<Void, OtaProgress, OtaResult> {

        // Constantes de timing
        private static final long POLL_EVENTS_INTERVAL_MS = 2_000L;
        private static final long EVENTS_TIMEOUT_MS = 120_000L;  // 120s para descarga + flash
        private static final long REBOOT_TIMEOUT_MS = 90_000L;   // 90s para reboot
        private static final long POLL_INFO_INTERVAL_MS = 3_000L;
        // Si pasaron tantos ms sin ver NINGUN ota_started en el history,
        // asumimos que el flujo de eventos esta roto pero el firmware quizas
        // sigue procesando el OTA. Saltamos al step 3 (polleo /info esperando
        // cambio de version) en vez de timeoutear. Asi la UX no depende de que
        // los events ota_* lleguen via /history.
        private static final long EVENTS_GRACE_MS = 15_000L;
        // Margen para tolerar clock skew entre el celu y AWS al filtrar /history.
        // El celu puede ir adelantado/atrasado por NTP/usuario; los timestamps
        // de DDB son server-side. 5 minutos de tolerancia hacia atras y hacia
        // adelante asegura que NO se nos escape un evento por diferencias de reloj.
        private static final long CLOCK_SKEW_MARGIN_MS = 5L * 60L * 1000L;

        private final AlertDialog progressDialog;
        private ProgressBar progressBar;
        private TextView statusText;

        OtaUpdateTask(AlertDialog progressDialog) {
            this.progressDialog = progressDialog;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // Cacheamos las refs a las vistas del dialog. El dialog ya esta
            // showed cuando arrancamos el task.
            progressBar = progressDialog.findViewById(R.id.ota_progress_bar);
            statusText  = progressDialog.findViewById(R.id.ota_status_text);
            if (progressBar != null) progressBar.setProgress(0);
            if (statusText != null) statusText.setText(R.string.ota_status_checking);
        }

        @Override
        protected OtaResult doInBackground(Void... voids) {
            try {
                // Las llamadas Retrofit sync (call.execute()) son seguras aca:
                // doInBackground YA esta en un worker thread.
                PawGateApi apiSync = ApiClient.get(getApplicationContext());

                String targetVersion = latestFirmware != null ? latestFirmware.latest_version : "";

                // ===== Paso 1: enviar cmd ota al backend =====
                android.util.Log.d("OtaUpdateTask", "[step1] targetVersion=" + targetVersion
                        + " deviceId=" + deviceId);
                publishProgress(new OtaProgress(5,
                        getString(R.string.ota_status_sending_cmd)));

                // Damos margen hacia atras al rango de busqueda para tolerar
                // clock skew entre el celu y el server AWS. Sin esto, si el
                // reloj del celu va 30s adelantado, los eventos ota_started
                // que el server timestampea con su clock quedan FUERA del
                // rango [from_ms, to_ms] y la app nunca los ve.
                long startMs = System.currentTimeMillis() - CLOCK_SKEW_MARGIN_MS;
                Map<String, Object> body = new HashMap<>();
                if (targetVersion != null && !targetVersion.isEmpty()) {
                    body.put("version", targetVersion);
                }
                android.util.Log.d("OtaUpdateTask", "[step1] POST /cmd/ota body=" + body);
                Response<DeviceDtos.CommandResponse> cmdResp =
                        apiSync.sendCommand(deviceId, DeviceRepository.CMD_OTA, body).execute();
                android.util.Log.d("OtaUpdateTask", "[step1] cmd HTTP " + cmdResp.code()
                        + " successful=" + cmdResp.isSuccessful());
                if (!cmdResp.isSuccessful()) {
                    String errBody = "";
                    try { if (cmdResp.errorBody() != null) errBody = cmdResp.errorBody().string(); }
                    catch (Exception ignore) {}
                    android.util.Log.w("OtaUpdateTask", "[step1] cmd error body=" + errBody);
                    return new OtaResult("cmd HTTP " + cmdResp.code() + " " + errBody, targetVersion);
                }

                publishProgress(new OtaProgress(10,
                        getString(R.string.ota_status_waiting_start)));
                android.util.Log.d("OtaUpdateTask",
                        "[step2] entering poll loop, deadline in " + EVENTS_TIMEOUT_MS + "ms");

                // ===== Paso 2: pollear /history hasta ver ota_success / ota_failed =====
                long pollStartedAt = System.currentTimeMillis();
                long deadline = pollStartedAt + EVENTS_TIMEOUT_MS;
                int lastDownloadPct = 0;
                boolean sawStarted = false, sawSuccess = false, sawFailed = false;
                String failReason = null;
                int pollCount = 0;

                while (!isCancelled() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_EVENTS_INTERVAL_MS);

                    // Fallback de robustez: si pasaron EVENTS_GRACE_MS sin ver
                    // ota_started, asumimos que el firmware esta procesando
                    // el OTA pero la pipeline de eventos esta rota (ej. la IoT
                    // Rule no captura events/ota o eventIngest los descarta).
                    // Saltamos directo al step 3 que pollea /info esperando que
                    // firmware_version cambie. Asi la UX completa al 100% solo
                    // con el reboot detection, sin depender de events ota_*.
                    if (!sawStarted &&
                            System.currentTimeMillis() - pollStartedAt > EVENTS_GRACE_MS) {
                        android.util.Log.w("OtaUpdateTask",
                                "[step2] no ota_started despues de " + EVENTS_GRACE_MS
                                + "ms — saltando al step 3 (poll /info)");
                        publishProgress(new OtaProgress(50,
                                getString(R.string.ota_status_flashing)));
                        break;
                    }

                    long now = System.currentTimeMillis() + CLOCK_SKEW_MARGIN_MS;
                    Response<DeviceDtos.HistoryResponse> hr = apiSync.getHistory(
                            deviceId, startMs, now, false, null).execute();
                    pollCount++;
                    if (!hr.isSuccessful() || hr.body() == null) {
                        android.util.Log.w("OtaUpdateTask",
                                "poll #" + pollCount + " HTTP "
                                + (hr.body() != null ? hr.code() : "null"));
                        continue;
                    }

                    List<DeviceDtos.Event> events = hr.body().events;
                    if (events == null) continue;

                    // Log de TODOS los event_type que llegan en este poll, para debug.
                    // Si vemos opened/closed/light_on pero no ota_*, la query
                    // funciona pero los OTA events no estan en DDB todavia.
                    StringBuilder typesSeen = new StringBuilder();
                    for (DeviceDtos.Event ev : events) {
                        if (ev.event_type != null) {
                            if (typesSeen.length() > 0) typesSeen.append(",");
                            typesSeen.append(ev.event_type);
                        }
                    }
                    android.util.Log.d("OtaUpdateTask",
                            "poll #" + pollCount + " events=" + events.size()
                            + " types=[" + typesSeen + "]");

                    // PROCESAMIENTO IDEMPOTENTE: en cada poll calculamos cual es
                    // el ESTADO MAS AVANZADO visto y publishProgress SOLO UNA VEZ
                    // con ese estado. Asi, aunque el mismo poll traiga eventos
                    // viejos (ota_started + 5 ota_progress), no hay flicker
                    // visual hacia atras: la barra solo avanza, nunca retrocede.
                    boolean foundStarted = false;
                    boolean foundSuccess = false;
                    boolean foundFailed = false;
                    int    maxPercent  = -1;
                    String localFailReason = null;
                    for (DeviceDtos.Event ev : events) {
                        if (ev.event_type == null) continue;
                        switch (ev.event_type) {
                            case "ota_started":
                                foundStarted = true;
                                break;
                            case "ota_progress": {
                                int pct = 0;
                                if (ev.payload != null) {
                                    Object p = ev.payload.get("percent");
                                    if (p instanceof Number) pct = ((Number) p).intValue();
                                }
                                if (pct > maxPercent) maxPercent = pct;
                                break;
                            }
                            case "ota_success":
                                foundSuccess = true;
                                break;
                            case "ota_failed":
                                foundFailed = true;
                                if (ev.payload != null) {
                                    Object e = ev.payload.get("error");
                                    if (e != null) localFailReason = String.valueOf(e);
                                }
                                break;
                            default:
                                // ignorar otros eventos (door, sensor, etc.)
                        }
                    }

                    // Priorizamos: success > failed > progress > started.
                    // Solo publishProgress si supera el estado anterior.
                    if (foundSuccess && !sawSuccess) {
                        sawSuccess = true;
                        publishProgress(new OtaProgress(90,
                                getString(R.string.ota_status_rebooting)));
                    } else if (foundFailed && !sawFailed) {
                        sawFailed = true;
                        failReason = localFailReason != null ? localFailReason : "unknown";
                    } else if (maxPercent > lastDownloadPct) {
                        lastDownloadPct = maxPercent;
                        int mapped = 15 + (int) (maxPercent * 0.70);
                        publishProgress(new OtaProgress(mapped,
                                getString(R.string.ota_status_downloading, maxPercent)));
                    } else if (foundStarted && !sawStarted) {
                        sawStarted = true;
                        publishProgress(new OtaProgress(15,
                                getString(R.string.ota_status_downloading, 0)));
                    }
                    if (sawSuccess || sawFailed) break;
                }

                if (sawFailed) {
                    return new OtaResult(failReason != null ? failReason : "ota_failed",
                            targetVersion);
                }
                if (!sawSuccess) {
                    return new OtaResult(getString(R.string.ota_error_timeout),
                            targetVersion);
                }

                // ===== Paso 3: esperar reboot — pollear /info hasta que firmware_version cambie =====
                long rebootDeadline = System.currentTimeMillis() + REBOOT_TIMEOUT_MS;
                while (!isCancelled() && System.currentTimeMillis() < rebootDeadline) {
                    Thread.sleep(POLL_INFO_INTERVAL_MS);
                    Response<DeviceDtos.DeviceInfoResponse> ir =
                            apiSync.getDeviceInfo(deviceId).execute();
                    if (ir.isSuccessful() && ir.body() != null
                            && ir.body().firmware_version != null
                            && ir.body().firmware_version.equals(targetVersion)) {
                        publishProgress(new OtaProgress(100,
                                getString(R.string.ota_status_done, targetVersion)));
                        return new OtaResult(null, targetVersion);
                    }
                }
                return new OtaResult(getString(R.string.ota_error_timeout), targetVersion);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new OtaResult("cancelado", null);
            } catch (Exception e) {
                android.util.Log.e("OtaUpdateTask", "doInBackground exception", e);
                return new OtaResult(e.getMessage() != null ? e.getMessage() : "error", null);
            }
        }

        @Override
        protected void onProgressUpdate(OtaProgress... values) {
            super.onProgressUpdate(values);
            if (values == null || values.length == 0) return;
            OtaProgress p = values[values.length - 1];
            if (progressBar != null) progressBar.setProgress(p.percent);
            if (statusText != null) statusText.setText(p.statusText);
            android.util.Log.d("OtaUpdateTask",
                    "progress " + p.percent + "% — " + p.statusText);
        }

        @Override
        protected void onPostExecute(OtaResult result) {
            super.onPostExecute(result);
            otaTask = null;
            if (progressDialog.isShowing()) progressDialog.dismiss();

            if (result == null || result.errorMessage == null) {
                String version = result != null && result.newVersion != null
                        ? result.newVersion : "";
                new MaterialAlertDialogBuilder(DeviceDetailActivity.this)
                        .setTitle(R.string.ota_progress_title)
                        .setMessage(getString(R.string.ota_status_done, version))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                // Disparar un poll inmediato para que la UI refleje la nueva version
                pollHandler.post(pollRunnable);
            } else {
                new MaterialAlertDialogBuilder(DeviceDetailActivity.this)
                        .setTitle(R.string.ota_error_title)
                        .setMessage(getString(R.string.ota_error_generic, result.errorMessage))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
            refreshOtaButton();
        }

        @Override
        protected void onCancelled(OtaResult result) {
            super.onCancelled(result);
            otaTask = null;
            if (progressDialog.isShowing()) progressDialog.dismiss();
            refreshOtaButton();
        }
    }
}
