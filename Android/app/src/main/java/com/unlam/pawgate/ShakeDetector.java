package com.unlam.pawgate;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Detector de "sacudida" del telefono usando el acelerometro lineal.
 *
 * Funcionamiento:
 *  - Suscribe al Sensor.TYPE_ACCELEROMETER del SensorManager.
 *  - Calcula la magnitud del vector aceleracion (sqrt(x^2 + y^2 + z^2))
 *    y le resta la gravedad (~9.8 m/s^2) para quedarse con la aceleracion
 *    "neta" producida por el usuario.
 *  - Si esa magnitud supera SHAKE_THRESHOLD_G por al menos
 *    SHAKE_MIN_SAMPLES samples consecutivos, considera que hubo un shake y
 *    dispara el callback.
 *  - Despues de disparar aplica un debounce de SHAKE_COOLDOWN_MS para evitar
 *    triggers en rafagas (un shake real produce 10-20 samples sobre umbral).
 *
 * Uso desde una Activity:
 *  <pre>
 *  ShakeDetector shake;
 *  protected void onResume() {
 *      super.onResume();
 *      shake = new ShakeDetector(this, () -> { ... cmd/call ... });
 *      shake.start();
 *  }
 *  protected void onPause() {
 *      super.onPause();
 *      shake.stop();
 *  }
 *  </pre>
 *
 * Si el device no tiene acelerometro (raro pero posible), start() loguea y
 * el detector queda inactivo (callback nunca dispara).
 */
public class ShakeDetector implements SensorEventListener {

    /** Aceleracion neta minima (en m/s^2 sobre gravedad) para contar como shake.
     *  9.8 = movimiento de 1g. Un shake fuerte produce ~15-20 m/s^2 netos. */
    private static final float SHAKE_THRESHOLD = 12.0f;

    /** Cantidad de samples consecutivos sobre umbral antes de disparar.
     *  El acelerometro sample rate UI = ~16 Hz (~60ms/sample), asi que 3
     *  samples = ~180ms de movimiento fuerte. Filtra golpes accidentales. */
    private static final int SHAKE_MIN_SAMPLES = 3;

    /** Tiempo de debounce despues de un trigger valido (ms). */
    private static final long SHAKE_COOLDOWN_MS = 2_000L;

    public interface Listener {
        /** Se llama en el main thread cuando se detecta un shake. */
        void onShake();
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Listener listener;

    private int consecutiveOverThreshold = 0;
    private long lastTriggerAt = 0L;
    private boolean running = false;

    public ShakeDetector(Context context, Listener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager)
                context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;
    }

    public void start() {
        if (running) return;
        if (sensorManager == null || accelerometer == null) {
            android.util.Log.w("ShakeDetector", "no accelerometer available on device");
            return;
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        consecutiveOverThreshold = 0;
        running = true;
    }

    public void stop() {
        if (!running) return;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        consecutiveOverThreshold = 0;
        running = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        // Magnitud del vector menos gravedad. Si el telefono esta quieto
        // la magnitud es ~9.8 y el net es ~0. Si lo sacudimos, sube.
        double magnitude = Math.sqrt(x * x + y * y + z * z);
        double netAcceleration = Math.abs(magnitude - SensorManager.GRAVITY_EARTH);

        if (netAcceleration > SHAKE_THRESHOLD) {
            consecutiveOverThreshold++;
            if (consecutiveOverThreshold >= SHAKE_MIN_SAMPLES) {
                long now = System.currentTimeMillis();
                if (now - lastTriggerAt >= SHAKE_COOLDOWN_MS) {
                    lastTriggerAt = now;
                    consecutiveOverThreshold = 0;
                    if (listener != null) listener.onShake();
                }
            }
        } else {
            consecutiveOverThreshold = 0;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
