# Diseño de arquitectura (versión con IA)

Misma funcionalidad que la versión hecha a mano, pero con una implementación
propia. Diferencias de diseño principales:

- **UNA SOLA máquina de estados: la de la puerta.** La luz NO es una segunda FSM:
  día/noche son **eventos** (`Evento::DIA`, `Evento::NOCHE`) y encender/apagar el
  LED son **acciones** (`Accion::ENCENDER_LUZ`, `Accion::APAGAR_LUZ`).
- **FSM dirigida por DATOS** (no por punteros a función). Cada celda de la tabla
  `[estado][evento]` es un `struct Transicion { Estado siguiente; Accion accion; }`.
  El controlador lee la celda, cambia de estado y encola la acción. No hay
  funciones de transición sueltas.
- C++ moderno: `enum class`, `namespace Pin`, `constexpr`, includes explícitos.
- Un único flag `deteccionHabilitada` en vez de un `estado` por sensor
  (equivalente, más simple: ambos sensores siempre se habilitaban/deshabilitaban juntos).
- **MQTT** (WiFi + PubSubClient) para recibir bloqueo/desbloqueo y publicar eventos.

```cpp
struct Transicion {
  Estado siguiente; // Estado::CANT (SIN_CAMBIO) => permanece en el estado actual
  Accion accion;    // Accion::NINGUNA          => no encola ninguna acción
};
const Transicion TABLA[N_ESTADOS][N_EVENTOS] = { ... }; // 5 x 9
```

## Tareas (FreeRTOS, prioridad 1, while(1) + vTaskDelay no bloqueante)

- **`tareaDeteccion`**: lee serial (bloqueo/desbloqueo), proximidad (HC-SR04, animal
  adentro), RFID (MFRC522, animal afuera) y fotoresistor (día/noche). Encola **eventos**
  en `colaEventos`.
- **`tareaControlador`** (la FSM): saca un evento de `colaEventos`, busca
  `TABLA[estadoActual][evento]`, actualiza el estado y, si hay acción, la encola en
  `colaAcciones`.
- **`tareaActuadores`**: saca una acción de `colaAcciones` y mueve el servo
  (abrir afuera/adentro, cerrar), suena el buzzer (bloqueo/desbloqueo), prende/apaga el
  LED y publica eventos por MQTT.
- **`tareaMqtt`**: mantiene la conexión (reconexión no bloqueante), `mqtt.loop()` y
  vacía `colaMqttSalida` publicando cada mensaje. El resto del código publica con
  `publicarEvento(...)`, que solo encola (desacopla el publish de las tareas).

Colas: `colaEventos` (Evento), `colaAcciones` (Accion), `colaMqttSalida` (MensajeMqtt).
Timer one-shot `timerCierre` (4500 ms) que encola `Evento::TIMEOUT`.

> El arranque se hace **por la propia FSM**: desde `Estado::ARRANQUE` se encola
> `Evento::INIT_LIBRE` (o `INIT_BLOQUEADA` si se define `INICIO_BLOQUEADO`), que lleva
> al estado inicial. No se fija el estado a mano.

## Estados (5) y eventos (9)
- Estados: `ARRANQUE`, `CERRADA_LIBRE`, `CERRADA_BLOQUEADA`, `ABIERTA_AFUERA`, `ABIERTA_ADENTRO`.
- Eventos: `INIT_LIBRE`, `INIT_BLOQUEADA`, `DESBLOQUEAR`, `BLOQUEAR`, `ANIMAL_ADENTRO`,
  `ANIMAL_AFUERA`, `TIMEOUT`, `DIA`, `NOCHE`.
- Acciones: `ABRIR_AFUERA`, `ABRIR_ADENTRO`, `CERRAR`, `BLOQUEAR`, `DESBLOQUEAR`,
  `ENCENDER_LUZ`, `APAGAR_LUZ`.

## Correcciones de bugs respecto de la versión a mano
1. **Luz por flanco**: antes se emitía `DIA`/`NOCHE` en *cada* ciclo (cada 200 ms),
   inundando la cola y arriesgando descartar eventos reales. Ahora solo se emite cuando
   cambia día↔noche.
2. **Desbloqueo por serial estando bloqueada**: antes el serial solo se leía en
   `CERRADA_LIBRE`, así que nunca se podía mandar `D` estando bloqueada. Ahora se lee en
   cualquier estado y la tabla decide la validez.
3. **Suscripción MQTT redundante** al propio tópico de eventos: eliminada (solo se
   suscribe a `soa/puerta/cmd`).
4. **Flag RFID "fantasma"**: se elimina leyendo y actuando en el momento, sin flag
   persistente entre ciclos.

## MQTT
- Broker: HiveMQ público (`broker.hivemq.com:1883`, sin auth). Client ID `esp32-puerta-soa`.
- `soa/puerta/cmd` (suscripción): payload que empieza con `B` = bloquear, `D` = desbloquear.
  El callback traduce a `Evento::BLOQUEAR`/`Evento::DESBLOQUEAR`.
- `soa/puerta/evento` (publicación): "PUERTA ABIERTA AFUERA/ADENTRO", "PUERTA CERRADA".

> WiFi: en simulación Wokwi se usa `Wokwi-GUEST` (sin clave). Para hardware real,
> reemplazar `WIFI_SSID`/`WIFI_PASS` en `src/main.cpp`.

## Notas
- El **pulsador físico** fue eliminado (código y `diagram.json`): el bloqueo/desbloqueo
  va por serial y por MQTT, que lo vuelven redundante.
- La tabla de transiciones completa (5×9) está en `máquina_de_estados.excalidraw.md`.
- Verificación de comportamiento ejecutable en `verificacion_fsm.py` (31 asserts).
