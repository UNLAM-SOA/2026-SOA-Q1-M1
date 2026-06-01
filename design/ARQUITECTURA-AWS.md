# PawGate — Arquitectura Cloud (AWS IoT Core + Serverless)

## Diagrama general

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              ESP32 (FreeRTOS)                              │
│                                                                            │
│  Hardware:                                                                 │
│   - LDR (GPIO 35)              → sensor luz                                │
│   - HC-SR04 (GPIO 5, 34)       → distancia mascota interior                │
│   - MFRC522 (SPI 18/19/21/22)  → RFID                                      │
│   - Pulsador (GPIO 14)         → bloqueo manual local                      │
│   - LED (GPIO 25)              → luminaria                                 │
│   - Servomotor SG90 (GPIO 12)  → puerta                                    │
│   - Buzzer (GPIO 4)            → feedback sonoro                           │
│                                                                            │
│  Software actual:                                                          │
│   - Máquina de estados (5 estados, 9 eventos)                              │
│   - Patrón productor-consumidor con colas FreeRTOS                         │
│   - Sin delay(), todo con vTaskDelay / xTimerCreate                        │
│                                                                            │
│  Software a AGREGAR:                                                       │
│   - WiFi (esp_wifi.h)                                                      │
│   - MQTT cliente con TLS X.509 (esp-aws-iot SDK o PubSubClient)            │
└────────────────────────────────────────────────────────────────────────────┘
                                    │
                          MQTT-TLS (puerto 8883)
                          mTLS con cert X.509
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                            AWS IoT Core                                    │
│                                                                            │
│  - Thing: "pawgate-001"  (1 thing por dispositivo físico)                  │
│  - Certificate + Private Key (X.509, único por thing)                      │
│  - Policy: permite Publish/Subscribe a sus topics, nada más                │
│                                                                            │
│  Topics MQTT (jerárquicos):                                                │
│   Publica el ESP32:                                                        │
│     pawgate/devices/{id}/events    → entradas, salidas, intentos, etc.     │
│     pawgate/devices/{id}/state     → estado actual (door, light, locked)   │
│   Suscribe el ESP32:                                                       │
│     pawgate/devices/{id}/commands  → lock, unlock, open_remote, buzz       │
│                                                                            │
│  Rules (procesan mensajes en tiempo real):                                 │
│   ┌───────────────────────────────────────────────────────────────────┐    │
│   │ rule_events_to_lambda                                             │    │
│   │   SELECT *, topic(3) AS deviceId FROM 'pawgate/devices/+/events'  │    │
│   │   → Lambda: ProcessEvent                                          │    │
│   ├───────────────────────────────────────────────────────────────────┤    │
│   │ rule_state_to_ddb                                                 │    │
│   │   SELECT * FROM 'pawgate/devices/+/state'                         │    │
│   │   → DynamoDB: tabla "DeviceState" (UPSERT por deviceId)           │    │
│   ├───────────────────────────────────────────────────────────────────┤    │
│   │ rule_alerts_to_sns                                                │    │
│   │   SELECT * FROM 'pawgate/devices/+/events'                        │    │
│   │     WHERE type IN ('intruder', 'battery_low')                     │    │
│   │   → SNS topic: pawgate-alerts                                     │    │
│   └───────────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────────┘
        │                                  │                            │
        │                                  │                            │
        ▼                                  ▼                            ▼
┌─────────────────┐               ┌────────────────────┐      ┌────────────────┐
│  Lambda         │               │  DynamoDB          │      │  SNS topic     │
│  ProcessEvent   │               │                    │      │  pawgate-alert │
│                 │               │  Tablas:           │      │                │
│  - persiste     │ ─writes─►     │   DeviceState      │      │  Platform App  │
│  - filtra       │               │   Events (history) │      │  → FCM         │
│  - dispara push │               │   Schedules        │      │  → Android     │
│                 │               │   Users            │      │                │
└─────────────────┘               └────────────────────┘      └────────────────┘
        ▲                                  ▲
        │                                  │
        │                                  │
        │           ┌──────────────────────┴──────────────────────┐
        │           │                                              │
        │      ┌────▼─────────────────────────────────────────┐    │
        │      │              API Gateway (REST)              │    │
        │      │                                              │    │
        │      │   POST /auth/login        → Cognito          │    │
        │      │   GET  /devices/me/state  → Lambda           │    │
        │      │   POST /devices/me/commands → Lambda → IoT   │────┘
        │      │   GET  /events             → Lambda          │
        │      │   GET  /schedules          → Lambda          │
        │      │   PUT  /schedules          → Lambda          │
        │      └──────────────────────────────────────────────┘
        │                          ▲
        │                          │
        │                          │  HTTPS + JWT (Authorization header)
        │                          │
        └──────────────┐    ┌──────┴──────────────────────────┐
                       │    │                                  │
              ┌────────▼────▼──────────────┐         ┌─────────▼────────────┐
              │      App Android           │         │   Node-RED Dashboard │
              │      (Java + XML)          │         │   (Docker en tu Mac) │
              │                            │         │                      │
              │  - Retrofit (REST)         │         │  Cliente MQTT a      │
              │  - FCM (push)              │         │  IoT Core con cert.  │
              │  - Cognito SDK (auth)      │         │                      │
              │                            │         │  Muestra eventos,    │
              │                            │         │  estado, control     │
              └────────────────────────────┘         │  manual para demo    │
                                                     └──────────────────────┘
```

---

## Mapeo: eventos FreeRTOS actuales → mensajes MQTT

Tu firmware ya emite estos eventos a la cola. Solo hay que publicarlos como JSON a MQTT:

| Evento FreeRTOS | Topic MQTT | Payload sugerido |
|---|---|---|
| `animal_detectado_afuera` | `.../events` | `{"type":"door_open","direction":"in","trigger":"rfid","rfidId":"A14"}` |
| `animal_detectado_adentro` | `.../events` | `{"type":"door_open","direction":"out","trigger":"ultrasonic","distanceCm":12}` |
| `timeout` (cierre auto) | `.../events` | `{"type":"door_close","reason":"timeout"}` |
| `bloqueo_por_app` (local) | `.../events` | `{"type":"locked","source":"button"}` |
| `desbloqueo_por_app` (local) | `.../events` | `{"type":"unlocked","source":"button"}` |
| `noche_detectada` | `.../events` | `{"type":"light_on","reason":"darkness"}` |
| `dia_detectado` | `.../events` | `{"type":"light_off","reason":"daylight"}` |

Comandos remotos que recibe el ESP32 vía `.../commands`:

| Payload entrante | Acción en firmware |
|---|---|
| `{"cmd":"lock"}` | Emite evento `bloqueo_por_app` a la cola |
| `{"cmd":"unlock"}` | Emite evento `desbloqueo_por_app` |
| `{"cmd":"open","direction":"in"}` | Emite `animal_detectado_afuera` (apertura manual) |
| `{"cmd":"buzz","durationMs":3000}` | Llama a Toby — acción directa al buzzer |

**Estado periódico** (cada 30s o cuando cambia algo):

Topic: `.../state`
```json
{
  "door": "closed",      // closed | open_in | open_out
  "light": "off",        // on | off
  "locked": false,
  "lastEvent": "door_close",
  "lastEventAt": "2026-05-26T18:42:13Z",
  "uptimeSec": 3812
}
```

---

## Servicios AWS — lista mínima

| Servicio | Para qué | Free Tier (12 meses) |
|---|---|---|
| **IoT Core** | Broker MQTT, Things, Rules | 500k mensajes/mes |
| **Lambda** | Funciones (ProcessEvent, SendCommand, GetState, etc.) | 1M requests/mes |
| **DynamoDB** | Tablas (DeviceState, Events, Schedules, Users) | 25 GB + 25 RCU/WCU (siempre) |
| **API Gateway** | REST hacia la app Android | 1M requests/mes |
| **Cognito** | Auth (login email/password, tokens JWT) | 50k MAUs |
| **SNS** | Push notifications (puente a FCM) | 1M push/mes |
| **CloudWatch Logs** | Logs de Lambda (para debug) | 5 GB |

Costo esperado para un TP universitario: **0 USD/mes** si te mantenés dentro del Free Tier. Solo cobran si te excedés.

---

## Plan de ataque (orden recomendado)

### Fase A — Cuenta AWS + seguridad (30 min)
1. Crear cuenta en aws.amazon.com (necesita tarjeta).
2. **Configurar Billing Alerts** (avísame y te enseño): alerta por mail si el costo del mes supera 1 USD. Esto te protege de errores.
3. Crear un usuario IAM (no usar el root).
4. Instalar AWS CLI y configurarlo con `aws configure`.

### Fase B — IoT Core: thing + certs (1h)
1. Crear Thing `pawgate-001` desde la consola.
2. Generar Certificate + Private Key, descargarlos.
3. Crear Policy `pawgate-device-policy` que permita publish/subscribe a `pawgate/devices/${iot:Connection.Thing.ThingName}/*`.
4. Adjuntar cert al thing + policy al cert.
5. Anotar el endpoint MQTT (algo tipo `xxxxxxxxx-ats.iot.us-east-1.amazonaws.com`).

### Fase C — Conectar ESP32 (3-4h, lo más técnico)
1. Agregar al firmware: WiFi + biblioteca PubSubClient (o esp-aws-iot SDK).
2. Cargar los certificados X.509 al ESP32 (PROGMEM).
3. Tarea FreeRTOS nueva `tarea_mqtt` que se conecta al broker, suscribe a `commands`, y consume eventos de la cola para publicar.
4. Probar con MQTT Explorer o `mosquitto_sub` apuntando a AWS IoT.

### Fase D — DynamoDB + Lambda (2h)
1. Crear tablas: `DeviceState`, `Events`, `Schedules`.
2. Lambda `ProcessEvent`: lee mensajes desde la IoT Rule, persiste en `Events`.
3. Lambda `SendCommand`: recibe HTTP, valida, publica a `pawgate/devices/{id}/commands`.

### Fase E — API Gateway + Cognito (2h)
1. Cognito User Pool para auth.
2. API Gateway con rutas REST, autorizador Cognito.
3. Conectar rutas a Lambdas.

### Fase F — App Android (Retrofit) (1-2h)
1. Agregar Retrofit + Cognito SDK al `build.gradle`.
2. Definir interface `PawGateApi`.
3. Conectar Login → Cognito → JWT → almacenar.
4. Dashboard → `GET /devices/me/state`.
5. Control → `POST /devices/me/commands`.

### Fase G — Push notifications (1h)
1. Proyecto Firebase + `google-services.json`.
2. SNS Platform App apuntando a Firebase.
3. Cuando llega un evento crítico, Lambda registra el endpoint y SNS publica.
4. App recibe push.

### Fase H — Node-RED Dashboard (1h)
1. Docker en tu Mac: `docker run -d -p 1880:1880 nodered/node-red`.
2. Importar nodos MQTT con TLS, configurar cliente con cert del thing virtual `node-red-dashboard`.
3. Flow visual: lee eventos, muestra estado puerta/luz, tiene botón "abrir manual" que publica a commands.

---

## Donde Node-RED brilla en tu defensa

Cuando el profe te pida la demo:

1. **Mostrá la app Android primero** controlando la puerta.
2. **Después abrí el Node-RED dashboard** en el navegador: ves los eventos llegar en vivo, tenés métricas y un botón de control alternativo.
3. **Argumento**: "Node-RED corre como segundo cliente MQTT del mismo broker (IoT Core), entonces TODO observador es independiente. Si la app Android se cae, el dashboard sigue funcionando. Demuestra que la arquitectura está bien desacoplada."

Esto le va a gustar.

---

## Próximo paso concreto

Crear cuenta AWS. Cuando la tengas, te guío paso por paso para:
- Setear billing alerts (lo primero).
- Crear el usuario IAM y configurar AWS CLI.
- Crear el Thing y certs.

¿Empezamos por ahí?
