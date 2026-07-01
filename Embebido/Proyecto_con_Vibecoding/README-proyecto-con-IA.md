# Proyecto con IA (Vibecoding Total) — Puerta inteligente ESP32

Informe del proyecto generado con **Vibecoding Total**: la versión con IA del sistema embebido
ESP32, su construcción y las métricas, siguiendo lo pedido por la cátedra (comparación
*Manual* vs *Vibecoding*).

> **Vibecoding Total** (def. de la cátedra): la IA genera **todo** el código; el humano **no
> escribe ni corrige código a mano**, solo le indica a la IA en lenguaje natural qué hacer y
> qué corregir. Este proyecto se construyó así de punta a punta.

---

## 1. Checklist del Informe sobre IA

| Punto | Detalle |
|---|---|
| **Herramienta de IA** | Claude Code (CLI de Anthropic, integrado en VS Code) |
| **Modelo de IA** | Claude Opus 4.8 (contexto 1M) |
| **Prompt técnico** | Ver sección 2 (prompt inicial + refinamientos por lenguaje natural) |
| **Cantidad de tokens** | _A completar_ — en Claude Code: comando `/cost` / panel de uso de la sesión |
| **Tiempo a versión funcional** | Vibecoding: _A completar_ · Manual: _A completar_ |
| **Métricas CPU/memoria** | Vibecoding: ver sección 5 · Manual: _pendiente (mismo test)_ |
| **Código en Git** | Rama `Develop-AB`; estructura `Manual/` + `Vibecoding/` (ver sección 6) |
| **Rúbrica de funcionalidad** | Ver sección 7 |

---

## 2. Prompt técnico

El **prompt inicial** pedía reescribir por completo `src/main.cpp` para que fuera
**funcionalmente equivalente** al código manual de referencia (`codigo_sin_ia.cpp`), pero:

- Unificando todo en **una sola máquina de estados** (la de la puerta), modelando día/noche
  como **eventos** y la luz como **acciones** (no una segunda FSM).
- Incorporando **MQTT** (WiFi + PubSubClient: broker, tópicos, callback y cola de salida).
- Respetando pines (`diagram.json`), librerías (`platformio.ini`) y FreeRTOS (tareas, colas,
  timer, esperas no bloqueantes).

A partir de ahí, el desarrollo siguió el ciclo Vibecoding: **indicación en lenguaje natural →
la IA implementa/corrige → se prueba → se refina**, sin edición manual de código. Ejemplos de
indicaciones dadas:
- "el pulsador ya no funciona / quitá el botón junto con lo que no se use"
- "que sea diferente al código sin IA pero que funcione igual; podés meter corrección de bugs"
- "probemos las métricas con la biblioteca de la cátedra, teniendo en cuenta el warning"

---

## 3. Cómo lo fuimos construyendo (cronología)

1. **FSM única + MQTT.** Se reescribió `main.cpp` desde el código manual: una sola tabla de
   transiciones (5 estados × 9 eventos) con la luz como eventos/acciones, y se incorporó MQTT
   completo (WiFi, reconexión, suscripción a `pawgate/pawgate-001/cmd/+`, publicación de
   eventos JSON en `pawgate/pawgate-001/events/door`, telemetría en
   `pawgate/pawgate-001/events/telemetry`, cola de salida y callback). Para Wokwi se usó el
   AP `Wokwi-GUEST`.
2. **Verificación temprana.** `pio run` OK; un test de la FSM (modelo fiel en Python, 31/31) y
   una demo de MQTT end-to-end contra el broker real HiveMQ (6/6).
3. **Ajustes funcionales pedidos.** Baud a 115200; se reactivó y luego se **eliminó el pulsador**
   (código + `diagram.json`) por redundante (bloqueo/desbloqueo va por serial y MQTT); volumen
   del buzzer.
4. **Reescritura "propia" (no copia).** Para que la versión con IA **no fuera un calco** del
   código manual: se pasó a una **FSM dirigida por datos** (tabla de `{estado_siguiente, acción}`
   en vez de punteros a función), `enum class`, `namespace`, `constexpr`, y se aprovechó para
   **corregir bugs** del original:
   - Luz por **flanco** (antes inundaba la cola cada 200 ms).
   - **Desbloqueo por serial** estando bloqueada (antes era imposible).
   - Se quitó la **suscripción MQTT redundante** al propio tópico de eventos.
   - Se eliminó el **flag RFID "fantasma"**.
5. **Métricas de CPU/memoria.** Se integró la biblioteca de la cátedra (`UsoCpuMemESP32`).
   Se diagnosticó por qué "no funciona en VS Code" (flags de FreeRTOS apagados en arduino-esp32
   2.0.x) y se resolvió **migrando al core 3.x (pioarduino)**, donde funciona. Ver
   `README-metricas-vscode.md`.
6. **Fix del servo** en core 3.x (aviso cosmético de LEDC).

> En todo el proceso, **ningún cambio de código fue hecho a mano por el humano** — todo lo
> implementó y corrigió la IA a partir de las indicaciones. Eso es Vibecoding Total.

---

## 4. Arquitectura de la versión con IA

- **5 tareas FreeRTOS** (prioridad 1, `while(1)` + `vTaskDelay` no bloqueante):
  `tareaDeteccion`, `tareaControlador` (la FSM), `tareaActuadores`, `tareaMqtt`, `tareaTelemetria`.
- **3 colas**: eventos, acciones y salida MQTT. **Timer** de FreeRTOS para el cierre automático.
- **Sensores**: proximidad (HC-SR04), RFID (MFRC522), luz (fotoresistor).
- **Actuadores**: servo, buzzer, LED.
- **Comunicación**: MQTT (broker público HiveMQ, simulable en Wokwi sin la nube). Misma
  interfaz que la versión sin IA: comandos en `pawgate/pawgate-001/cmd/<open|block|unblock|call|cancel|reboot>`
  y eventos/telemetría JSON en `pawgate/pawgate-001/events/door` y `.../events/telemetry`.

Detalle completo en `Arquitectura.md` y la tabla de estados en `máquina_de_estados.excalidraw.md`.

---

## 5. Métricas (versión con IA / Vibecoding)

Medido en Wokwi, caso "10 s sin interacción" (biblioteca de la cátedra):

| Métrica | Valor |
|---|---|
| Tiempo de muestreo | 10 s |
| CPU **Ocupado** Core 0 / Core 1 | 26.15% / 22.97% |
| CPU IDLE Core 0 / Core 1 | 70.15% / 95.16% |
| Heap total / libre / usado | 330424 B / 188206 B / 142217 B |
| **Heap usado** | **43.04 %** |

> Para comparar contra la versión **Manual**, correr el **mismo test de 10 s** con la misma
> biblioteca y completar una tabla análoga. Usar **"Ocupado"** (CPU) y **% de heap** como
> métricas de comparación (el "Total" puede pasar 100% por el promediado de la herramienta).

---

## 6. Estructura del repositorio (Git)

Pedida por la cátedra: un directorio por versión.

```
/Manual/        -> proyecto del código hecho a mano (codigo_sin_ia.cpp + diagram/platformio)
/Vibecoding/    -> este proyecto (versión con IA)
```

> _Pendiente de armado_: mover el proyecto actual a `Vibecoding/` y el código manual a `Manual/`.
> Para que el Manual también mida, necesita el platform 3.x + `Metrics.h/.cpp` + las llamadas
> `initStats()`/`finishStats()` (mismo procedimiento que la versión con IA).

---

## 7. Rúbrica de funcionalidad ESP32 (autoevaluación)

| Criterio | Estado | Evidencia |
|---|---|---|
| Funciona sin errores | A | Compila (`pio run` OK); FSM 31/31; MQTT 6/6. *(Único aviso: log cosmético de LEDC del servo.)* |
| Emplea tareas de FreeRTOS | A | 5 tareas (detección, controlador, actuadores, MQTT, telemetría) |
| Evita esperas bloqueantes (temporizadores) | A | `xTimerCreate` para el cierre automático; tareas con `vTaskDelay` |
| Usa los sensores y actuadores solicitados | A | 3 sensores (proximidad, RFID, luz) + 3 actuadores (servo, buzzer, LED) |
| Se comunica con Android | Parcial | MQTT bidireccional probado; falta la app Android sobre los mismos tópicos |

---

## 8. Cómo correr la versión con IA

```bash
pio run        # compila (primer build descarga el toolchain 3.x, tarda unos minutos)
```
Luego, simular en **Wokwi** (extensión de VS Code). En el monitor serial (115200) se ve la
conexión WiFi/MQTT, el funcionamiento de la FSM y, a los 10 s, las métricas de CPU/memoria.

- Comandos por MQTT (publicar en `pawgate/pawgate-001/cmd/<comando>`):
  - `open` con payload JSON `{"direction":"in"}` o `{"direction":"out"}` — abre la puerta.
  - `block` / `unblock` — bloquea / desbloquea (también `B`/`D` por serial).
  - `call` — llama al animal (beeps); `cancel` — sin efecto; `reboot` — reinicia el ESP32.
  - `metrics` — termina el muestreo de métricas (también `M` por serial, o auto-fin a los 10 s).
- Eventos y telemetría (JSON) salen por `pawgate/pawgate-001/events/door` y `.../events/telemetry`.
- Para probar el MQTT: `python control_mqtt.py` (control vivo contra el firmware en Wokwi) o
  `python demo_mqtt.py` (demo end-to-end offline con la puerta emulada).
