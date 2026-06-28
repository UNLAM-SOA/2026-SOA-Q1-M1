// ============================================================================
//  Puerta inteligente para mascotas — ESP32 / FreeRTOS
//  Versión "con IA": misma funcionalidad que la versión a mano, pero con una
//  FSM ÚNICA dirigida por DATOS (tabla de {estado_siguiente, acción}) en lugar
//  de una tabla de punteros a función. Incluye control por MQTT.
//
//  Subsistemas modelados dentro de la única máquina de estados de la puerta:
//   - Proximidad (HC-SR04): animal adentro     -> abre hacia adentro
//   - RFID (MFRC522): animal afuera             -> abre hacia afuera
//   - Luz (fotoresistor): día/noche son EVENTOS -> LED es ACCIÓN
//   - Bloqueo/desbloqueo: por serial o por MQTT
//   - Timer de cierre automático por timeout
// ============================================================================

#include <Arduino.h>
#include <SPI.h>
#include <MFRC522.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h> // eventos y telemetría en JSON (mismo formato que la versión sin IA)
#include "Metrics.h" // métricas de CPU/memoria (biblioteca de la cátedra; requiere arduino-esp32 3.x)

// ----------------------------------------------------------------------------
//  Pines (deben coincidir con diagram.json)
// ----------------------------------------------------------------------------
namespace Pin
{
    constexpr uint8_t LED = 25;
    constexpr uint8_t LDR = 35; // fotoresistor
    constexpr uint8_t BUZZER = 4;
    constexpr uint8_t SERVO = 12;
    constexpr uint8_t TRIGGER = 5; // HC-SR04
    constexpr uint8_t ECHO = 34;   // HC-SR04 (vía divisor de tensión)
    constexpr uint8_t RFID_SS = 21;
    constexpr uint8_t RFID_SCK = 18;
    constexpr uint8_t RFID_MOSI = 23;
    constexpr uint8_t RFID_MISO = 19;
    constexpr uint8_t RFID_RST = 22;
}

// ----------------------------------------------------------------------------
//  Parámetros de dominio
// ----------------------------------------------------------------------------
constexpr long UMBRAL_LUZ = 2048;       // ADC: > umbral => día
constexpr float DIST_MIN_CM = 30.0f;    // tolerancia: animal "cerca" si distancia < esto
constexpr float VEL_SONIDO = 0.0343f;   // cm/us
constexpr uint32_t ECHO_TIMEOUT_US = 30000;
constexpr uint32_t TIMEOUT_CIERRE_MS = 4500;

constexpr int ANGULO_CERRADA = 90;
constexpr int ANGULO_ABIERTA_AFUERA = 0;
constexpr int ANGULO_ABIERTA_ADENTRO = 180;

constexpr uint32_t PERIODO_TAREA_MS = 200;
constexpr uint32_t STACK_TAREAS = 8192;
constexpr UBaseType_t PRIORIDAD = 1;
constexpr unsigned BAUDIOS = 115200;

// Descomentar para que la puerta arranque bloqueada.
// #define INICIO_BLOQUEADO

// ----------------------------------------------------------------------------
//  WiFi + MQTT
// ----------------------------------------------------------------------------
// Wokwi-GUEST: AP abierto que provee internet en la simulación. Para hardware
// real, reemplazar por las credenciales del AP propio.
constexpr char WIFI_SSID[] = "Wokwi-GUEST";
constexpr char WIFI_PASS[] = "";
constexpr int WIFI_CANAL = 6; // canal fijo => asociación rápida en Wokwi

// Broker público HiveMQ (MQTT plano, sin TLS) => se puede simular en Wokwi sin
// depender de la nube AWS. La INTERFAZ (tópicos + payloads JSON) es la MISMA que
// la versión sin IA (AWS IoT Core), así el mismo control/Android sirve para ambas.
constexpr char MQTT_HOST[] = "broker.hivemq.com";
constexpr int MQTT_PORT = 1883;
constexpr char MQTT_CLIENT_ID[] = "esp32-pawgate-soa";

// Identidad del firmware (para la telemetría)
constexpr char FIRMWARE_VERSION[] = "1.0.0";
constexpr char HARDWARE_MODEL[] = "ESP32-WROOM-32";
constexpr uint32_t TELEMETRY_INTERVAL_MS = 30000;

// Tópicos (idéntica estructura a la versión sin IA)
//   entrada : pawgate/pawgate-001/cmd/<comando>  (open|block|unblock|call|cancel|reboot|metrics)
//   salida  : pawgate/pawgate-001/events/door        (eventos de la puerta, JSON)
//             pawgate/pawgate-001/events/telemetry   (telemetría periódica, JSON)
constexpr char MQTT_TOPIC_CMD_FILTER[] = "pawgate/pawgate-001/cmd/+";
constexpr char MQTT_TOPIC_EVENT_DOOR[] = "pawgate/pawgate-001/events/door";
constexpr char MQTT_TOPIC_EVENT_TELEMETRY[] = "pawgate/pawgate-001/events/telemetry";

constexpr size_t TAM_PAYLOAD = 512;
constexpr size_t TAM_TOPIC = 64;
constexpr UBaseType_t TAM_COLA = 10;

struct MensajeMqtt
{
    char topico[TAM_TOPIC];
    char payload[TAM_PAYLOAD];
};

// ----------------------------------------------------------------------------
//  Máquina de estados ÚNICA (dirigida por datos)
// ----------------------------------------------------------------------------
enum class Estado : uint8_t
{
    ARRANQUE,
    CERRADA_LIBRE,
    CERRADA_BLOQUEADA,
    ABIERTA_AFUERA,
    ABIERTA_ADENTRO,
    CANT // cantidad de estados (y centinela "no cambiar")
};

enum class Evento : uint8_t
{
    INIT_LIBRE,
    INIT_BLOQUEADA,
    DESBLOQUEAR,
    BLOQUEAR,
    ANIMAL_ADENTRO,
    ANIMAL_AFUERA,
    TIMEOUT,
    DIA,
    NOCHE,
    CANT // cantidad de eventos
};

enum class Accion : uint8_t
{
    NINGUNA,
    ABRIR_AFUERA,
    ABRIR_ADENTRO,
    CERRAR,
    BLOQUEAR,
    DESBLOQUEAR,
    ENCENDER_LUZ,
    APAGAR_LUZ
};

// Una celda de la tabla: a qué estado ir y qué acción emitir.
struct Transicion
{
    Estado siguiente; // Estado::CANT => permanece en el estado actual
    Accion accion;    // Accion::NINGUNA => no encola nada
};

constexpr Estado SIN_CAMBIO = Estado::CANT;
constexpr Transicion X = {SIN_CAMBIO, Accion::NINGUNA}; // celda "ignorar evento"

constexpr int N_ESTADOS = static_cast<int>(Estado::CANT);
constexpr int N_EVENTOS = static_cast<int>(Evento::CANT);

// Tabla de transiciones [estado][evento]. Columnas en el orden del enum Evento:
// INIT_LIBRE, INIT_BLOQUEADA, DESBLOQUEAR, BLOQUEAR, ANIMAL_ADENTRO,
// ANIMAL_AFUERA, TIMEOUT, DIA, NOCHE
const Transicion TABLA[N_ESTADOS][N_EVENTOS] = {
    // ARRANQUE
    {{Estado::CERRADA_LIBRE, Accion::NINGUNA}, {Estado::CERRADA_BLOQUEADA, Accion::NINGUNA}, X, X, X, X, X, X, X},
    // CERRADA_LIBRE
    {X, X, X,
     {Estado::CERRADA_BLOQUEADA, Accion::BLOQUEAR},
     {Estado::ABIERTA_ADENTRO, Accion::ABRIR_ADENTRO},
     {Estado::ABIERTA_AFUERA, Accion::ABRIR_AFUERA},
     X,
     {SIN_CAMBIO, Accion::APAGAR_LUZ},
     {SIN_CAMBIO, Accion::ENCENDER_LUZ}},
    // CERRADA_BLOQUEADA
    {X, X,
     {Estado::CERRADA_LIBRE, Accion::DESBLOQUEAR},
     X, X, X, X,
     {SIN_CAMBIO, Accion::APAGAR_LUZ},
     {SIN_CAMBIO, Accion::ENCENDER_LUZ}},
    // ABIERTA_AFUERA
    {X, X, X, X, X, X, {Estado::CERRADA_LIBRE, Accion::CERRAR}, X, X},
    // ABIERTA_ADENTRO
    {X, X, X, X, X, X, {Estado::CERRADA_LIBRE, Accion::CERRAR}, X, X},
};

// ----------------------------------------------------------------------------
//  Estado global compartido entre tareas
// ----------------------------------------------------------------------------
volatile Estado estadoActual = Estado::ARRANQUE;
volatile bool deteccionHabilitada = true; // mientras la puerta está abierta, off

QueueHandle_t colaEventos;
QueueHandle_t colaAcciones;
QueueHandle_t colaMqttSalida;
TimerHandle_t timerCierre;

// ----------------------------------------------------------------------------
//  Servo por LEDC directo (arduino-esp32 3.x)
//  Reemplaza a ESP32Servo: esa biblioteca (v3.2.1) adjunta el pin al canal LEDC
//  dos veces durante attach() y, en arduino-esp32 3.x, el segundo intento
//  dispara el error "Pin X is already attached to LEDC". Manejar el LEDC a mano
//  evita ese doble-attach sin perder funcionalidad (mismo API: setPeriodHertz/
//  attach/write).
// ----------------------------------------------------------------------------
class ServoLedc
{
public:
    void setPeriodHertz(uint32_t hz) { frecuenciaHz = hz; }

    bool attach(uint8_t pin, uint16_t minUs, uint16_t maxUs)
    {
        this->pin = pin;
        this->minUs = minUs;
        this->maxUs = maxUs;
        return ledcAttach(pin, frecuenciaHz, RESOLUCION_BITS);
    }

    void write(int angulo)
    {
        if (angulo < 0)
            angulo = 0;
        if (angulo > 180)
            angulo = 180;
        uint32_t pulsoUs = minUs + (uint32_t)(maxUs - minUs) * angulo / 180;
        uint32_t periodoUs = 1000000UL / frecuenciaHz;
        uint32_t duty = (uint64_t)pulsoUs * ((1UL << RESOLUCION_BITS) - 1) / periodoUs;
        ledcWrite(pin, duty);
    }

private:
    static constexpr uint8_t RESOLUCION_BITS = 16;
    uint8_t pin = 0;
    uint16_t minUs = 500;
    uint16_t maxUs = 2400;
    uint32_t frecuenciaHz = 50;
};

ServoLedc servo;
MFRC522 rfid(Pin::RFID_SS, Pin::RFID_RST);
WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);

// Prototipos
void encolarEvento(Evento ev);
void publicarMqtt(const char *topico, const char *payload);
void publicarEvento(const char *tipo, const char *direccion);
void publicarTelemetria();
bool enEstadoCerrado();

// Dirección de la última apertura ("in"/"out"), para informarla al cerrar.
const char *ultimaDireccionApertura = nullptr;
// Dedup de la luz: solo se publica el flanco on->off / off->on.
bool estadoLuzPublicado = false;

// ----------------------------------------------------------------------------
//  Utilidades
// ----------------------------------------------------------------------------
bool enEstadoCerrado()
{
    return estadoActual == Estado::CERRADA_LIBRE || estadoActual == Estado::CERRADA_BLOQUEADA;
}

void encolarEvento(Evento ev)
{
    if (xQueueSend(colaEventos, &ev, 0) != pdPASS)
        Serial.println("[evento] cola LLENA");
}

// Genera una onda cuadrada por software en el buzzer (sin LEDC, evita choque
// con ESP32Servo). Bloquea la tarea de actuadores el tiempo del beep.
void beep(int frecuenciaHz, int duracionMs)
{
    if (frecuenciaHz <= 0 || duracionMs <= 0)
        return;
    unsigned long periodoUs = 1000000UL / (unsigned long)frecuenciaHz;
    unsigned long medioUs = periodoUs / 2;
    unsigned long ciclos = ((unsigned long)duracionMs * 1000UL) / periodoUs;
    for (unsigned long i = 0; i < ciclos; i++)
    {
        digitalWrite(Pin::BUZZER, HIGH);
        delayMicroseconds(medioUs);
        digitalWrite(Pin::BUZZER, LOW);
        delayMicroseconds(medioUs);
    }
}

float medirDistanciaCm()
{
    digitalWrite(Pin::TRIGGER, LOW);
    delayMicroseconds(2);
    digitalWrite(Pin::TRIGGER, HIGH);
    delayMicroseconds(10);
    digitalWrite(Pin::TRIGGER, LOW);
    long us = pulseIn(Pin::ECHO, HIGH, ECHO_TIMEOUT_US);
    return us * VEL_SONIDO / 2.0f; // si hay timeout, us=0 => 0 cm (no dispara)
}

// ----------------------------------------------------------------------------
//  TAREA 1: detección (sensores + serial) -> encola EVENTOS
// ----------------------------------------------------------------------------
void leerSerial()
{
    // BUGFIX: el original solo leía el serial estando NO bloqueada, así que no
    // se podía desbloquear por serial. Acá se lee siempre y la tabla decide.
    if (Serial.available() <= 0)
        return;
    char c = Serial.read();
    if (c == 'B')
        encolarEvento(Evento::BLOQUEAR);
    else if (c == 'D')
        encolarEvento(Evento::DESBLOQUEAR);
    else if (c == 'M')
        finishStats(); // mismo trigger de métricas que por MQTT, pero por serial (para probar)
}

void detectarProximidad()
{
    float cm = medirDistanciaCm();
    // Dispara con CUALQUIER distancia menor a la tolerancia. Solo se descarta
    // cm == 0, que es el "sin eco" de pulseIn al hacer timeout (no una lectura real).
    if (deteccionHabilitada && estadoActual == Estado::CERRADA_LIBRE &&
        cm > 0.0f && cm < DIST_MIN_CM)
    {
        deteccionHabilitada = false; // se rehabilita al cerrar
        Serial.printf("[prox] animal adentro (%.1f cm)\n", cm);
        encolarEvento(Evento::ANIMAL_ADENTRO);
    }
}

void detectarRfid()
{
    if (!(rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()))
        return;
    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
    // Se actúa en el momento: no queda ningún flag "fantasma" entre ciclos.
    if (deteccionHabilitada && estadoActual == Estado::CERRADA_LIBRE)
    {
        deteccionHabilitada = false;
        Serial.println("[rfid] animal afuera");
        encolarEvento(Evento::ANIMAL_AFUERA);
    }
}

void detectarLuz()
{
    static bool esDiaPrevio = true; // asume día => si arranca de noche, enciende
    bool esDiaAhora = analogRead(Pin::LDR) > UMBRAL_LUZ;
    // BUGFIX: solo se emite cuando cambia día<->noche (detección por flanco),
    // en vez de inundar la cola en cada ciclo.
    if (enEstadoCerrado() && esDiaAhora != esDiaPrevio)
    {
        esDiaPrevio = esDiaAhora;
        encolarEvento(esDiaAhora ? Evento::DIA : Evento::NOCHE);
        Serial.println(esDiaAhora ? "[luz] DIA" : "[luz] NOCHE");
    }
}

void tareaDeteccion(void *)
{
    for (;;)
    {
        leerSerial();
        detectarProximidad();
        detectarRfid();
        detectarLuz();
        vTaskDelay(pdMS_TO_TICKS(PERIODO_TAREA_MS));
    }
}

// ----------------------------------------------------------------------------
//  TAREA 2: controlador (FSM) -> consume EVENTOS, encola ACCIONES
// ----------------------------------------------------------------------------
void tareaControlador(void *)
{
    for (;;)
    {
        Evento ev;
        if (xQueueReceive(colaEventos, &ev, 0) == pdPASS)
        {
            const Transicion &t = TABLA[static_cast<int>(estadoActual)][static_cast<int>(ev)];
            if (t.siguiente != SIN_CAMBIO)
                estadoActual = t.siguiente;
            if (t.accion != Accion::NINGUNA)
                xQueueSend(colaAcciones, &t.accion, 0);
        }
        vTaskDelay(pdMS_TO_TICKS(PERIODO_TAREA_MS));
    }
}

// ----------------------------------------------------------------------------
//  TAREA 3: actuadores -> consume ACCIONES (servo, buzzer, LED, MQTT)
// ----------------------------------------------------------------------------
void ejecutarAccion(Accion a)
{
    switch (a)
    {
    case Accion::ABRIR_AFUERA:
        servo.write(ANGULO_ABIERTA_AFUERA); // 0° => apertura "hacia adentro" (animal entra)
        xTimerStart(timerCierre, 0);
        ultimaDireccionApertura = "in";
        publicarEvento("opened", "in");
        break;
    case Accion::ABRIR_ADENTRO:
        servo.write(ANGULO_ABIERTA_ADENTRO); // 180° => apertura "hacia afuera" (animal sale)
        xTimerStart(timerCierre, 0);
        ultimaDireccionApertura = "out";
        publicarEvento("opened", "out");
        break;
    case Accion::CERRAR:
        servo.write(ANGULO_CERRADA);
        deteccionHabilitada = true; // se rehabilitan los sensores
        publicarEvento("closed", ultimaDireccionApertura);
        ultimaDireccionApertura = nullptr;
        break;
    case Accion::BLOQUEAR:
        beep(600, 120);
        beep(300, 200); // descendente: se bloquea
        publicarEvento("blocked", nullptr);
        break;
    case Accion::DESBLOQUEAR:
        beep(600, 120);
        beep(1200, 200); // ascendente: se desbloquea
        publicarEvento("unblocked", nullptr);
        break;
    case Accion::ENCENDER_LUZ:
        digitalWrite(Pin::LED, HIGH);
        if (!estadoLuzPublicado)
        {
            publicarEvento("light_on", nullptr);
            estadoLuzPublicado = true;
        }
        break;
    case Accion::APAGAR_LUZ:
        digitalWrite(Pin::LED, LOW);
        if (estadoLuzPublicado)
        {
            publicarEvento("light_off", nullptr);
            estadoLuzPublicado = false;
        }
        break;
    default:
        break;
    }
}

void tareaActuadores(void *)
{
    for (;;)
    {
        Accion a;
        if (xQueueReceive(colaAcciones, &a, 0) == pdPASS)
            ejecutarAccion(a);
        vTaskDelay(pdMS_TO_TICKS(PERIODO_TAREA_MS));
    }
}

// ----------------------------------------------------------------------------
//  TAREA 4: MQTT (reconexión + loop + drenaje de la cola de salida)
// ----------------------------------------------------------------------------
// Encola un mensaje (tópico + payload) para que la tareaMqtt lo publique.
void publicarMqtt(const char *topico, const char *payload)
{
    MensajeMqtt msg;
    strncpy(msg.topico, topico, TAM_TOPIC - 1);
    msg.topico[TAM_TOPIC - 1] = '\0';
    strncpy(msg.payload, payload, TAM_PAYLOAD - 1);
    msg.payload[TAM_PAYLOAD - 1] = '\0';
    if (xQueueSend(colaMqttSalida, &msg, 0) != pdPASS)
        Serial.println("[mqtt] cola de salida LLENA");
}

// Publica un evento de la puerta en JSON: {"type":..., "direction":..., "ts":...}
// (mismo formato que la versión sin IA, sobre pawgate/.../events/door).
void publicarEvento(const char *tipo, const char *direccion)
{
    char buffer[TAM_PAYLOAD];
    JsonDocument doc;
    doc["type"] = tipo;
    if (direccion != nullptr)
        doc["direction"] = direccion;
    doc["ts"] = millis();
    serializeJson(doc, buffer, sizeof(buffer));
    publicarMqtt(MQTT_TOPIC_EVENT_DOOR, buffer);
}

// Telemetría periódica del dispositivo (mismo formato que la versión sin IA).
void publicarTelemetria()
{
    char buffer[TAM_PAYLOAD];
    JsonDocument doc;
    doc["type"] = "telemetry";
    doc["ts"] = millis();
    doc["uptime_s"] = millis() / 1000;
    doc["rssi_dbm"] = WiFi.RSSI();                         // negativo (-30 muy bueno, -90 malo)
    doc["free_heap_kb"] = ESP.getFreeHeap() / 1024;
    doc["total_heap_kb"] = ESP.getHeapSize() / 1024;
    doc["flash_used_kb"] = ESP.getSketchSize() / 1024;
    doc["flash_total_kb"] = ESP.getFlashChipSize() / 1024;
    doc["cpu_temp_c"] = temperatureRead();                 // sensor interno del ESP32 (°C)
    doc["local_ip"] = WiFi.localIP().toString();
    doc["device_mac"] = WiFi.macAddress();
    doc["firmware_version"] = FIRMWARE_VERSION;
    doc["hardware_model"] = HARDWARE_MODEL;
    doc["wifi_ssid"] = WiFi.SSID();
    doc["wifi_bssid"] = WiFi.BSSIDstr();                   // MAC del AP
    doc["wifi_band"] = "2.4 GHz";
    doc["wifi_gateway"] = WiFi.gatewayIP().toString();
    doc["wifi_security"] = "WPA2-PSK";
    size_t escrito = serializeJson(doc, buffer, sizeof(buffer));
    if (escrito == 0 || escrito >= sizeof(buffer))
        Serial.println("[telemetry] buffer chico, payload truncado");
    publicarMqtt(MQTT_TOPIC_EVENT_TELEMETRY, buffer);
}

// Callback de comandos. El comando es el último segmento del tópico
// (pawgate/pawgate-001/cmd/<comando>), igual que en la versión sin IA.
void mqttCallback(char *topico, byte *payload, unsigned int length)
{
    const char *comando = strrchr(topico, '/');
    if (!comando)
    {
        Serial.println("[mqtt] tópico malformado");
        return;
    }
    comando++; // saltar la '/'
    Serial.printf("[mqtt] cmd '%s'\n", comando);

    if (strcmp(comando, "open") == 0)
    {
        JsonDocument doc;
        DeserializationError err = deserializeJson(doc, payload, length);
        if (err)
        {
            Serial.printf("[mqtt] JSON inválido en open: %s\n", err.c_str());
            return;
        }
        const char *direccion = doc["direction"];
        if (direccion == nullptr)
        {
            Serial.println("[mqtt] open sin 'direction'");
            return;
        }
        if (strcmp(direccion, "in") == 0)
            encolarEvento(Evento::ANIMAL_AFUERA); // abre 0° (animal entra)
        else if (strcmp(direccion, "out") == 0)
            encolarEvento(Evento::ANIMAL_ADENTRO); // abre 180° (animal sale)
        else
            Serial.println("[mqtt] 'direction' debe ser in/out");
    }
    else if (strcmp(comando, "block") == 0)
        encolarEvento(Evento::BLOQUEAR);
    else if (strcmp(comando, "unblock") == 0)
        encolarEvento(Evento::DESBLOQUEAR);
    else if (strcmp(comando, "call") == 0)
    {
        // Llamar al animal: secuencia de beeps (no cambia el estado de la puerta).
        for (int i = 0; i < 5; i++)
        {
            beep(1200, 150);
            vTaskDelay(pdMS_TO_TICKS(100));
            beep(800, 150);
            vTaskDelay(pdMS_TO_TICKS(100));
        }
    }
    else if (strcmp(comando, "cancel") == 0)
        Serial.println("[mqtt] cancel (sin efecto sobre la puerta)");
    else if (strcmp(comando, "reboot") == 0)
    {
        Serial.println("[mqtt] reboot");
        delay(100);
        ESP.restart();
    }
    else if (strcmp(comando, "metrics") == 0)
        finishStats(); // termina el muestreo y muestra promedios de CPU/memoria
    else
        Serial.println("[mqtt] comando desconocido");
}

void reconectarMqtt()
{
    mqtt.setServer(MQTT_HOST, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
    // El buffer por defecto de PubSubClient (256 B) es chico para la telemetría JSON
    // (~400 B) y haría fallar el publish. Lo agrandamos como en la versión sin IA.
    mqtt.setBufferSize(1024);
    for (int intentos = 0; !mqtt.connected() && intentos < 5; intentos++)
    {
        Serial.print("[mqtt] conectando...");
        if (mqtt.connect(MQTT_CLIENT_ID))
        {
            Serial.println("Conexión MQTT OK");
            mqtt.subscribe(MQTT_TOPIC_CMD_FILTER); // suscripción a todos los comandos
            Serial.printf("[mqtt] suscripto a %s\n", MQTT_TOPIC_CMD_FILTER);
        }
        else
        {
            Serial.printf(" rc=%d, reintento en 2s\n", mqtt.state());
            vTaskDelay(pdMS_TO_TICKS(2000));
        }
    }
}

void tareaMqtt(void *)
{
    for (;;)
    {
        if (!mqtt.connected())
        {
            reconectarMqtt();
        }
        else
        {
            mqtt.loop();
            MensajeMqtt msg;
            while (xQueueReceive(colaMqttSalida, &msg, 0) == pdPASS)
            {
                if (!mqtt.publish(msg.topico, msg.payload, true))
                    Serial.println("[mqtt] publish FALLÓ");
            }
        }
        vTaskDelay(pdMS_TO_TICKS(PERIODO_TAREA_MS));
    }
}

// ----------------------------------------------------------------------------
//  TAREA 5: telemetría (publica el estado del dispositivo cada cierto intervalo)
// ----------------------------------------------------------------------------
void tareaTelemetria(void *)
{
    // Espera a tener WiFi antes del primer envío.
    while (WiFi.status() != WL_CONNECTED)
        vTaskDelay(pdMS_TO_TICKS(500));
    for (;;)
    {
        publicarTelemetria();
        vTaskDelay(pdMS_TO_TICKS(TELEMETRY_INTERVAL_MS));
    }
}

// ----------------------------------------------------------------------------
//  Configuración / arranque
// ----------------------------------------------------------------------------
void configurarPines()
{
    pinMode(Pin::LED, OUTPUT);
    digitalWrite(Pin::LED, LOW);
    pinMode(Pin::LDR, INPUT);
    pinMode(Pin::BUZZER, OUTPUT);
    digitalWrite(Pin::BUZZER, LOW);
    pinMode(Pin::TRIGGER, OUTPUT);
    digitalWrite(Pin::TRIGGER, LOW);
    pinMode(Pin::ECHO, INPUT);
    // Servo manejado por LEDC directo (ver clase ServoLedc): adjunta el pin una
    // sola vez, evitando el error "Pin already attached" de ESP32Servo.
    servo.setPeriodHertz(50); // servo estándar de 50 Hz
    servo.attach(Pin::SERVO, 500, 2400);
    servo.write(ANGULO_CERRADA);
}

void configurarRfid()
{
    SPI.begin(Pin::RFID_SCK, Pin::RFID_MISO, Pin::RFID_MOSI, Pin::RFID_SS);
    rfid.PCD_Init();
}

void conectarWifi()
{
    Serial.printf("\nConectando a: %s", WIFI_SSID);
    WiFi.begin(WIFI_SSID, WIFI_PASS, WIFI_CANAL);
    // No bloquear para siempre: si el WiFi no asocia (p. ej. simulación sin
    // internet), igual se sigue para que el resto del sistema —sensores, servo,
    // FSM— arranque. La tareaMqtt reintenta la conexión por su cuenta.
    constexpr uint32_t WIFI_TIMEOUT_MS = 15000;
    uint32_t inicio = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - inicio < WIFI_TIMEOUT_MS)
    {
        delay(500);
        Serial.print('.');
    }
    if (WiFi.status() == WL_CONNECTED)
    {
        Serial.println("\nWiFi Conectado");
        Serial.print("IP: ");
        Serial.println(WiFi.localIP());
    }
    else
    {
        Serial.println("\nWiFi NO conectado (timeout). Sigo sin red; el sensor "
                       "y la puerta funcionan, MQTT reintentará en segundo plano.");
    }
}

void crearColas()
{
    colaEventos = xQueueCreate(TAM_COLA, sizeof(Evento));
    colaAcciones = xQueueCreate(TAM_COLA, sizeof(Accion));
    colaMqttSalida = xQueueCreate(TAM_COLA, sizeof(MensajeMqtt));
}

void timerCierreCallback(TimerHandle_t)
{
    encolarEvento(Evento::TIMEOUT);
}

void crearTareas()
{
    xTaskCreate(tareaDeteccion, "deteccion", STACK_TAREAS, NULL, PRIORIDAD, NULL);
    xTaskCreate(tareaControlador, "controlador", STACK_TAREAS, NULL, PRIORIDAD, NULL);
    xTaskCreate(tareaActuadores, "actuadores", STACK_TAREAS, NULL, PRIORIDAD, NULL);
    xTaskCreate(tareaMqtt, "mqtt", STACK_TAREAS, NULL, PRIORIDAD, NULL);
    xTaskCreate(tareaTelemetria, "telemetria", STACK_TAREAS, NULL, PRIORIDAD, NULL);
}

void setup()
{
    Serial.begin(BAUDIOS);
    configurarPines();
    conectarWifi();
    configurarRfid();
    crearColas();
    timerCierre = xTimerCreate("cierre", pdMS_TO_TICKS(TIMEOUT_CIERRE_MS),
                               pdFALSE, NULL, timerCierreCallback);

    // Arranque vía la propia FSM: desde ARRANQUE, el evento INIT lleva al
    // estado inicial (en vez de fijar el estado a mano).
#ifdef INICIO_BLOQUEADO
    encolarEvento(Evento::INIT_BLOQUEADA);
#else
    encolarEvento(Evento::INIT_LIBRE);
#endif

    crearTareas();

    // Métricas de CPU/memoria: arranca el muestreo. Para terminar y ver los promedios,
    // publicar en pawgate/pawgate-001/cmd/metrics (caso "FinishStats() al recibir un
    // mensaje MQTT"), enviar 'M' por serial, o esperar 10 s sin interacción.
    initStats();
}

void loop()
{
    // Caso "10 s sin interacción" (igual que el ejemplo de la cátedra): a los 10 s
    // termina el muestreo y muestra los promedios de CPU/memoria automáticamente.
    // (Además del trigger manual por 'M' vía serial o MQTT, para el caso por evento.)
    static unsigned long t0 = millis();
    static bool finalizado = false;
    if (!finalizado && millis() - t0 >= 10000)
    {
        finalizado = true;
        finishStats();
    }
    vTaskDelay(pdMS_TO_TICKS(10));
}
