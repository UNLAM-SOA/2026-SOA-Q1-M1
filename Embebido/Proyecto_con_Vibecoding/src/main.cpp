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
#include <ESP32Servo.h>
#include <SPI.h>
#include <MFRC522.h>
#include <WiFi.h>
#include <PubSubClient.h>
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

constexpr char MQTT_HOST[] = "broker.hivemq.com"; // broker público HiveMQ
constexpr int MQTT_PORT = 1883;
constexpr char MQTT_CLIENT_ID[] = "esp32-puerta-soa";
constexpr char MQTT_TOPIC_CMD[] = "soa/puerta/cmd";       // entrada: 'B'/'D'
constexpr char MQTT_TOPIC_EVENTO[] = "soa/puerta/evento"; // salida: estados

constexpr size_t TAM_PAYLOAD = 32;
constexpr UBaseType_t TAM_COLA = 10;

struct MensajeMqtt
{
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

Servo servo;
MFRC522 rfid(Pin::RFID_SS, Pin::RFID_RST);
WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);

// Prototipos
void encolarEvento(Evento ev);
void publicarEvento(const char *payload);
bool enEstadoCerrado();

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
        servo.write(ANGULO_ABIERTA_AFUERA);
        xTimerStart(timerCierre, 0);
        publicarEvento("PUERTA ABIERTA AFUERA");
        break;
    case Accion::ABRIR_ADENTRO:
        servo.write(ANGULO_ABIERTA_ADENTRO);
        xTimerStart(timerCierre, 0);
        publicarEvento("PUERTA ABIERTA ADENTRO");
        break;
    case Accion::CERRAR:
        servo.write(ANGULO_CERRADA);
        deteccionHabilitada = true; // se rehabilitan los sensores
        publicarEvento("PUERTA CERRADA");
        break;
    case Accion::BLOQUEAR:
        beep(600, 120);
        beep(300, 200); // descendente: se bloquea
        break;
    case Accion::DESBLOQUEAR:
        beep(600, 120);
        beep(1200, 200); // ascendente: se desbloquea
        break;
    case Accion::ENCENDER_LUZ:
        digitalWrite(Pin::LED, HIGH);
        break;
    case Accion::APAGAR_LUZ:
        digitalWrite(Pin::LED, LOW);
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
void publicarEvento(const char *payload)
{
    MensajeMqtt msg;
    strncpy(msg.payload, payload, TAM_PAYLOAD - 1);
    msg.payload[TAM_PAYLOAD - 1] = '\0';
    if (xQueueSend(colaMqttSalida, &msg, 0) != pdPASS)
        Serial.println("[mqtt] cola de salida LLENA");
}

void mqttCallback(char *topico, byte *payload, unsigned int length)
{
    if (length == 0)
        return;
    Serial.printf("[mqtt] msg en %s: %c\n", topico, (char)payload[0]);
    if (payload[0] == 'B')
        encolarEvento(Evento::BLOQUEAR);
    else if (payload[0] == 'D')
        encolarEvento(Evento::DESBLOQUEAR);
    else if (payload[0] == 'M')
        finishStats(); // termina el muestreo y muestra promedios de CPU/memoria
}

void reconectarMqtt()
{
    mqtt.setServer(MQTT_HOST, MQTT_PORT);
    mqtt.setCallback(mqttCallback);
    for (int intentos = 0; !mqtt.connected() && intentos < 5; intentos++)
    {
        Serial.print("[mqtt] conectando...");
        if (mqtt.connect(MQTT_CLIENT_ID))
        {
            Serial.println("Conexión MQTT OK");
            mqtt.subscribe(MQTT_TOPIC_CMD); // BUGFIX: solo el tópico de comandos
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
                if (!mqtt.publish(MQTT_TOPIC_EVENTO, msg.payload, true))
                    Serial.println("[mqtt] publish FALLÓ");
            }
        }
        vTaskDelay(pdMS_TO_TICKS(PERIODO_TAREA_MS));
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
    // ESP32Servo en arduino-esp32 3.x: hay que reservar los timers LEDC antes de
    // attach(), si no falla la configuración PWM del servo ("Pin already attached").
    ESP32PWM::allocateTimer(0);
    ESP32PWM::allocateTimer(1);
    ESP32PWM::allocateTimer(2);
    ESP32PWM::allocateTimer(3);
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
    // publicar 'M' en soa/puerta/cmd (caso "FinishStats() al recibir un mensaje MQTT"),
    // o esperar 10 s sin interacción y luego publicar 'M'.
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
