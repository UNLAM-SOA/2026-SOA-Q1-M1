# Métricas de CPU y memoria en ESP32 con **VS Code + PlatformIO**

> La cátedra indicó que la biblioteca de métricas (`UsoCpuMemESP32`) **solo funciona en
> Wokwi y en el IDE de Arduino, no en VS Code**. Este documento explica **por qué** pasa eso
> y **cómo se logró que funcione igual en VS Code/PlatformIO**, con la verificación real.

---

## 1. Por qué "no funciona en VS Code" (causa real)

La biblioteca mide el uso de CPU con las **estadísticas de runtime de FreeRTOS**:

```cpp
uxTaskGetSystemState(taskArray, maxTasks, &totalRuntime); // tiempos por tarea
taskArray[i].ulRunTimeCounter;                            // contador de ejecución
taskArray[i].xCoreID;                                     // núcleo de cada tarea
```

Esas APIs/campos **solo existen si el FreeRTOS fue compilado con tres flags activados**:

| Flag de FreeRTOS | Para qué |
|---|---|
| `configGENERATE_RUN_TIME_STATS` | habilita `ulRunTimeCounter` y `totalRuntime` |
| `configUSE_TRACE_FACILITY` | habilita `uxTaskGetSystemState()` |
| `configVTASKLIST_INCLUDE_COREID` | habilita el campo `xCoreID` |

**El problema:** el platform oficial de PlatformIO (`platform = espressif32`) usa el core
**arduino-esp32 2.0.x**, que trae esos flags **APAGADOS**. Se puede comprobar en el SDK:

```
# CONFIG_FREERTOS_USE_TRACE_FACILITY is not set
# CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS is not set
```

Con los flags apagados, la biblioteca **ni siquiera compila** (las APIs no están declaradas) y,
de compilar, `initStats()` solo imprimiría *"Stats de CPU no disponibles"*.
El **IDE de Arduino** usa **arduino-esp32 3.x**, donde esos flags vienen **activados** por
defecto → por eso ahí sí funciona.

**Conclusión:** no es una limitación de "VS Code", es del **core 2.0.x** que usa el platform
oficial. Si en PlatformIO usamos el core **3.x**, funciona igual.

---

## 2. La solución (3 pasos)

### Paso 1 — Cambiar el core a arduino-esp32 3.x (plataforma *pioarduino*)

En `platformio.ini`, reemplazar `platform = espressif32` por la plataforma **pioarduino**
(que empaqueta arduino-esp32 3.x, con los flags activados):

```ini
[env:esp32dev]
; arduino-esp32 3.x: trae configGENERATE_RUN_TIME_STATS y configUSE_TRACE_FACILITY
; activados => la biblioteca de métricas de CPU/memoria funciona.
platform = https://github.com/pioarduino/platform-espressif32/releases/download/55.03.39/platform-espressif32.zip
board = esp32dev
framework = arduino
monitor_speed = 115200

lib_deps =
  madhephaestus/ESP32Servo
  miguelbalboa/MFRC522@^1.4.10
  knolleary/PubSubClient@2.8.0
```

> En el SDK del core 3.x, los flags ahora están en `1`:
> ```
> #define CONFIG_FREERTOS_GENERATE_RUN_TIME_STATS 1
> #define CONFIG_FREERTOS_USE_TRACE_FACILITY 1
> #define CONFIG_FREERTOS_VTASKLIST_INCLUDE_COREID 1
> ```
> El primer `pio run` descarga el toolchain de 3.x (tarda unos minutos, es normal).

### Paso 2 — Agregar la biblioteca de métricas

Copiar `Metrics.h` y `Metrics.cpp` (de `Material-SOA/Ejemplos SE/UsoCpuMemESP32`) dentro de
`src/`. PlatformIO compila automáticamente todo lo que esté en `src/`.

### Paso 3 — Integrar `initStats()` / `finishStats()` en el código

```cpp
#include "Metrics.h"

void setup() {
  // ... resto de la inicialización ...
  initStats();   // arranca el muestreo (al final del setup, con las tareas ya creadas)
}

// Disparador del fin de muestreo (caso "al recibir un mensaje MQTT"):
void mqttCallback(char *topico, byte *payload, unsigned int length) {
  // ...
  else if (payload[0] == 'M') finishStats(); // imprime los promedios y termina
}

// Caso "10 segundos sin interacción": termina solo a los 10 s
void loop() {
  static unsigned long t0 = millis();
  static bool fin = false;
  if (!fin && millis() - t0 >= 10000) { fin = true; finishStats(); }
  vTaskDelay(pdMS_TO_TICKS(10));
}
```

> En este proyecto, `finishStats()` también se dispara escribiendo `M` por el **monitor serial**
> (más cómodo para probar) además de por **MQTT** publicando en `pawgate/pawgate-001/cmd/metrics`.

---

## 3. Cómo medir

1. Compilar: **`pio run`**.
2. Correr en **Wokwi** (extensión de VS Code) — ahora usa el firmware 3.x con métricas.
3. Dos casos de prueba (los que pide la cátedra):
   - **10 s sin interacción**: arranca, esperás 10 s → imprime los promedios solo.
   - **Por evento MQTT**: hacés la interacción y publicás en `pawgate/pawgate-001/cmd/metrics`
     (o tipeás `M` en el monitor) → imprime los promedios.

---

## 4. Verificación (salida real en VS Code + Wokwi)

```
Muestreo de Metricas Iniciado...
=== Contribución Promedio al total del sistema ===
=== Tiempo de muestreado: 10 (segundos) ===
====== Estado Promedio del Uso de CPU en ESP32 ===
Core 0 -> Total:  96.30% | Ocupado: 26.15% | Libre (IDLE): 70.15%
Core 1 -> Total: 118.13% | Ocupado: 22.97% | Libre (IDLE): 95.16%
====== Estado Promedio de la memoria en ESP32 ====
Heap total : 330424 bytes
Heap libre : 188206 bytes
Heap usado : 142217 bytes
Uso        : 43.04 %
Muestreo de Metricas finalizado
```

✅ **Funciona en VS Code/PlatformIO**: imprime uso de CPU por núcleo y uso de heap, con datos reales.

---

## 5. Notas

- Para **comparar Manual vs Vibecoding**, usar los valores de **"Ocupado"** (CPU) y el **% de
  heap usado**, que son los consistentes. El "Total" puede superar 100% por el promediado simple
  de la biblioteca (artefacto conocido de la herramienta, no del código).
- Con el core 3.x, `ESP32Servo` puede loguear `Pin 12 is already attached to LEDC` — es un
  aviso cosmético del primer `attach` (el servo igual funciona); no afecta las métricas.
- Resumen de por qué la cátedra decía "no en VS Code": el platform **oficial** usa core 2.0.x
  (flags apagados). Cambiando al core **3.x** (pioarduino), VS Code queda equivalente al IDE
  de Arduino para este fin.
