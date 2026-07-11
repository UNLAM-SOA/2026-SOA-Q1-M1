  #include <ESP32Servo.h>
  #include <MFRC522.h>
  #include <WiFi.h>
  #include <WiFiClientSecure.h>
  #include <HTTPClient.h>    // OTA: cliente HTTP para descargar el .bin desde S3
  #include <Update.h>        // OTA: API del bootloader para escribir a la particion app1
  #include <ArduinoJson.h>
  #include <time.h>          // configTime() + time() para sync NTP previo a TLS
  #include "PubSubClient.h" // Hay que instalar PubSubClient@2.8.0
  #include "aws_certs.h"
  #include "Metrics.h"

  //cantidad de tiempo que se desea tomar valores de muestreo de cpu y memoria
  #define SAMPLING_TIME 10000
  unsigned long initTime=0;
  unsigned long  actualTime=0;

  // WiFiClient espClient; // Se activa cuando no queremos correr contra AWS IoT Core
  WiFiClientSecure espClient;
  PubSubClient client(espClient);

  // WIFI
  #define WIFI_SSID "ApPoloPB5"
  #define WIFI_PASSWORD "-"

  enum tipo_broker {
    EMQX,
    HIVEMQ_PUBLIC,
    MOSQUITTO_LOCAL,
    AWS_IOT_CORE
  };


  // MQTT
  #define BROKER AWS_IOT_CORE // Nosotros vamos a usar este, acá lo seteo

  // Configuración dependiente del broker
  const char* mqtt_server;
  int         mqtt_port;
  const char* mqtt_user;
  const char* mqtt_pass;

  // Identidad del Firmware
  // FIRMWARE_VERSION se puede sobreescribir desde platformio.ini via build_flags:
  //   build_flags = -DFW_VERSION_OVERRIDE='"1.0.0"'
  // Si no se define nada, default = "1.1.0" (version actual con La Cucaracha).
  #ifdef FW_VERSION_OVERRIDE
    #define FIRMWARE_VERSION FW_VERSION_OVERRIDE
  #else
    #define FIRMWARE_VERSION "1.1.0"
  #endif
  #define HARDWARE_MODEL "ESP32-WROOM-32"
  #define TELEMETRY_INTERVAL_MS 30000

  // Topics y ClientID
  #define MQTT_CLIENT_ID AWS_THING_NAME
  #define MQTT_TOPIC_CMD_FILTER "pawgate/pawgate-001/cmd/+"
  #define MQTT_TOPIC_EVENT_DOOR "pawgate/pawgate-001/events/door"
  #define MQTT_TOPIC_EVENT_TELEMETRY "pawgate/pawgate-001/events/telemetry"
  #define MQTT_TOPIC_EVENT_OTA "pawgate/pawgate-001/events/ota"

  #define TAM_PAYLOAD_MQTT 512
  #define TAM_TOPIC_MQTT   64
  // TAM_COLA_MQTT: tamano de la cola FreeRTOS entre publicar_mqtt() y mqtt_task.
  // 10 era demasiado chico para OTA: el firmware publica ota_started + 9 eventos
  // ota_progress (10/20/.../90%) muy rapido y la cola se llenaba al 90%.
  // 32 da margen comodo y consume solo ~18 KB de RAM (32 * sizeof(stMensajeMqtt)).
  #define TAM_COLA_MQTT    32

  struct stMensajeMqtt
  {
    char topico[TAM_TOPIC_MQTT];
    char payload[TAM_PAYLOAD_MQTT];
  };

  // ================================================================
  // DEFINICIÓN DE PINES & CONSTANTES
  // ================================================================

  // Pines
  #define LED 25
  #define FOTORESISTOR 35

  // RFID MFRC522
  #define RFID_SS 21 // SDA en el módulo, SS en SPI
  #define RFID_SCK 18
  #define RFID_MOSI 23
  #define RFID_MISO 19
  #define RFID_RST 22

  // Proximidad HC-SR04
  #define SENSOR_PROXIMIDAD_ECHO 34
  #define SENSOR_PROXIMIDAD_TRIGGER 5

  // Buzzer, Servo y botón de la app
  #define BUZZER 4
  #define SERVO 12

  // Sensores
  #define UMBRAL_LUZ 2048  // Probar en wokwi y ajustar
  #define TIME_OUT_SENSOR_PROXIMIDAD 30000
  #define PUERTO_SERIAL_WOKWY 9600

  // Tareas
  #define TIME_OUT_CERO 0
  #define TAM_STACK_TAREAS 8192
  #define PRECEDENCIA_POR_DEFECTO 1

  // Puerta — tamaños de colas y tabla de estados
  #define CANT_MAX_EVENTOS_PUERTA 9
  #define CANT_MAX_ESTADOS_PUERTA 5
  #define TAM_EV_COLA_PUERTA 10
  #define TAM_ACC_COLA_PUERTA 10
  #define TIEMPO_TIMEOUT_PUERTA 4500

  // Buffer para envío de mensajes en MQTT para AWS IoT Core
  #define BUFFER_SIZE 1024

  // ================================================================
  // TIPOS COMPARTIDOS
  // ================================================================

  enum estado_sensor
  {
    ESTADO_HABILITADO,
    ESTADO_DESHABILITADO
  };

  struct stSensorLuz
  {
    int pin;
    long valor_actual;
  };

  struct stSensorProximidad
  {
    int pin_echo;
    int pin_trigger;
    estado_sensor estado;
    float distancia_actual_cm;
    int tiempo_transcurrido_ms;
    const float velocidad_sonido = 0.0343;
    const float distancia_minima_cm = 30;
    const float distancia_base_cm = 1;
  };

  struct stSensorRFID
  {
    estado_sensor estado;
    int id_tag;
    bool acceso_permitido;
  };

  typedef void (*transicion)();

  void none()
  {
    return;
  }

  stSensorLuz sensor_luz;

  // ================================================================
  // SUBSISTEMA PUERTA
  // ================================================================

  // --- Enums & variables globales ---
  enum eventos_puerta
  {
    EV_INIT_NO_BLOQUEADA,
    EV_INIT_BLOQUEADA,
    EV_DESBLOQUEO_POR_APP,
    EV_BLOQUEO_POR_APP,
    EV_ANIMAL_DETECTADO_ADENTRO,
    EV_ANIMAL_DETECTADO_AFUERA,
    EV_TIMEOUT,
    EV_DIA_DETECTADO,
    EV_NOCHE_DETECTADA
  };

  enum estados_puerta
  {
    ST_ARRANQUE,
    ST_CERRADA_NO_BLOQUEADA,
    ST_CERRADA_BLOQUEADA,
    ST_ABIERTA_DESDE_AFUERA,
    ST_ABIERTA_DESDE_ADENTRO
  } estado_actual_puerta; // Declaro estado global de la puerta

  enum acciones_puerta
  {
    ACC_ABRIR_DESDE_AFUERA,
    ACC_ABRIR_DESDE_ADENTRO,
    ACC_CERRAR,
    ACC_BLOQUEAR,
    ACC_DESBLOQUEAR,
    ACC_ENCENDER_LUZ,
    ACC_APAGAR_LUZ
  };

  QueueHandle_t queueEventos_puerta;
  QueueHandle_t queueAcciones_puerta;
  QueueHandle_t queueMqttOut;
  SemaphoreHandle_t mutex_sensores;

  MFRC522 rfid(RFID_SS, RFID_RST); // RFID (crea el objeto que ocupa el lector)

  Servo servo;

  stSensorProximidad sensor_proximidad;
  stSensorRFID sensor_rfid;

  TimerHandle_t timer_puerta;

  // --- Prototipos ---
  void init_no_bloqueada();
  void init_bloqueada();
  void bloquear_puerta();
  void desbloquear_puerta();
  void abrir_desde_adentro();
  void abrir_desde_afuera();
  void cerrar_puerta();
  void encender_luz();
  void apagar_luz();
  void timer_callback_puerta(TimerHandle_t xTimer);
  void buzzer_beep(int freq_hz, int duration_ms);
  void leer_sensor_proximidad();
  bool sensor_proximidad_detectar_animal();
  void leer_sensor_rfid();
  bool sensor_rfid_detectar_animal();
  void detectar_animales_en_puerta();
  void detectar_cambios_luz();
  char leer_serial_puerta();
  void configuracion_sensores_puerta();
  void configuracion_sensores_luz();
  void configuracion_estado_inicial_puerta();
  void crear_mutex_sensores();
  void puerta_deteccion(void *pvParametros);
  void luz_deteccion(void *pvParametros);
  void puerta_controlador(void *pvParametros);
  void puerta_accion(void *pvParametros);
  void crear_colas_puerta();
  void crear_tareas_puerta();
  void setup_puerta();
  void emitir_accion_puerta(acciones_puerta action, const char* nombre);
  void emitir_evento_puerta(eventos_puerta evento, const char* caller);
  void callback(char* topico, byte* message, unsigned int length);
  void mqtt_task(void *pvParametros);
  void definir_broker();
  void conectar_mqtt();
  void setup_wifi_mqtt();
  void wifiConnect();
  void sincronizar_hora_ntp();
  void publicar_mqtt(const char* topico, const char* payload);

  // OTA: descarga de un .bin via HTTP y flasheo a la particion app1.
  void ejecutar_ota(const char* url, const char* version_target, const char* sha256_target);
  void publicar_evento_ota_progress(const char* version, size_t bytes_written,
                                     int total_bytes, int percent);
  void publicar_evento_ota(const char* status, const char* version, const char* error_msg);

  // --- Tabla de estados ---
  transicion puerta_tabla_estados[CANT_MAX_ESTADOS_PUERTA][CANT_MAX_EVENTOS_PUERTA] =
  {
      {  init_no_bloqueada,    init_bloqueada,    none,                  none,               none,                         none,                      none,            none,             none        }, // state ST_ARRANQUE
      {  none,                 none,              none,                  bloquear_puerta,    abrir_desde_adentro,          abrir_desde_afuera,        none,            apagar_luz,       encender_luz}, // state ST_CERRADA_NO_BLOQUEADA
      {  none,                 none,              desbloquear_puerta,    none,               none,                         none,                      none,            apagar_luz,       encender_luz}, // state ST_CERRADA_BLOQUEADA
      {  none,                 none,              none,                  none,               none,                         none,                      cerrar_puerta,   none,             none        }, // state ST_ABIERTA_DESDE_AFUERA
      {  none,                 none,              none,                  none,               none,                         none,                      cerrar_puerta,   none,             none        }  // state ST_ABIERTA_DESDE_ADENTRO
      // EV_INIT_NO_BLOQUEADA, EV_INIT_BLOQUEADA, EV_DESBLOQUEO_POR_APP, EV_BLOQUEO_POR_APP, EV_ANIMAL_DETECTADO_ADENTRO, EV_ANIMAL_DETECTADO_AFUERA, EV_TIMEOUT,      EV_DIA_DETECTADO, EV_NOCHE_DETECTADA
  };

  // --- Helpers de cola ---
  void emitir_accion_puerta(acciones_puerta action, const char* nombre)
  {
    if (xQueueSend(queueAcciones_puerta, &action, TIME_OUT_CERO) != pdPASS)
      Serial.println("[puerta] Cola de acciones LLENA");
    else
      Serial.print(nombre);
  }

  void emitir_evento_puerta(eventos_puerta evento, const char* caller)
  {
    if (xQueueSend(queueEventos_puerta, &evento, TIME_OUT_CERO) != pdPASS)
    {
      Serial.print(caller);
      Serial.println(" Cola de eventos LLENA");
    }
  }

  // --- Funciones de transición ---
  // Init
  void init_no_bloqueada()
  {
    estado_actual_puerta = ST_CERRADA_NO_BLOQUEADA;
  }

  void init_bloqueada()
  {
    estado_actual_puerta = ST_CERRADA_BLOQUEADA;
  }

  // APP
  void bloquear_puerta()
  {
    estado_actual_puerta = ST_CERRADA_BLOQUEADA;
    emitir_accion_puerta(ACC_BLOQUEAR, ">> Acción emitida: ACC_BLOQUEAR");
  }

  void desbloquear_puerta()
  {
    estado_actual_puerta = ST_CERRADA_NO_BLOQUEADA;
    emitir_accion_puerta(ACC_DESBLOQUEAR, ">> Acción emitida: ACC_DESBLOQUEAR");
  }

  // Aperturas de la puerta
  void abrir_desde_adentro()
  {
    estado_actual_puerta = ST_ABIERTA_DESDE_ADENTRO;
    if (xSemaphoreTake(mutex_sensores, portMAX_DELAY) == pdTRUE) {
      sensor_proximidad.estado = ESTADO_DESHABILITADO;
      sensor_rfid.estado       = ESTADO_DESHABILITADO;
      xSemaphoreGive(mutex_sensores);
    }
    emitir_accion_puerta(ACC_ABRIR_DESDE_ADENTRO, ">> Acción emitida: ACC_ABRIR_DESDE_ADENTRO");
  }

  void abrir_desde_afuera()
  {
    estado_actual_puerta = ST_ABIERTA_DESDE_AFUERA;
    if (xSemaphoreTake(mutex_sensores, portMAX_DELAY) == pdTRUE) {
      sensor_proximidad.estado = ESTADO_DESHABILITADO;
      sensor_rfid.estado       = ESTADO_DESHABILITADO;
      xSemaphoreGive(mutex_sensores);
    }
    emitir_accion_puerta(ACC_ABRIR_DESDE_AFUERA, ">> Acción emitida: ACC_ABRIR_DESDE_AFUERA");
  }

  // Cierre de la puerta
  void cerrar_puerta()
  {
    estado_actual_puerta = ST_CERRADA_NO_BLOQUEADA;
    // Rehabilitar sensores cuando la puerta vuelve a estar cerrada.
    if (xSemaphoreTake(mutex_sensores, portMAX_DELAY) == pdTRUE) {
      sensor_proximidad.estado = ESTADO_HABILITADO;
      sensor_rfid.estado       = ESTADO_HABILITADO;
      xSemaphoreGive(mutex_sensores);
    }
    emitir_accion_puerta(ACC_CERRAR, ">> Acción emitida: ACC_CERRAR");
  }

  // --- Timer ---
  void timer_callback_puerta(TimerHandle_t xTimer)
  {
    Serial.println("[timer_callback_puerta] Timeout de la puerta");
    emitir_evento_puerta(EV_TIMEOUT, "[timer_callback_puerta]");
  }

  // --- Manejo de la luz ---
  void encender_luz()
  {
    // Emitir la acción a la cola de acciones
    Serial.print("Transición iniciada: Luz encendida\n");
    emitir_accion_puerta(ACC_ENCENDER_LUZ, ">> Acción emitida: ACC_ENCENDER_LUZ");
    return;
  }

  void apagar_luz()
  {
    emitir_accion_puerta(ACC_APAGAR_LUZ, "");
    return;
  }


  // --- Sensores ---
  // Genera onda cuadrada en BUZZER sin usar LEDC (evita conflicto con ESP32Servo)
  void buzzer_beep(int frecuencia_hz, int duracion_ms)
  {
    if (frecuencia_hz <= 0 || duracion_ms <= 0)
    {
      return;    
    }
    unsigned long periodo_us = 1000000UL / (unsigned long)frecuencia_hz;
    unsigned long medio_periodo_us = periodo_us / 2;
    unsigned long ciclos = ((unsigned long)duracion_ms * 1000UL) / periodo_us;

    for (unsigned long i = 0; i < ciclos; i++)
    {
      digitalWrite(BUZZER, LOW);
      delayMicroseconds(medio_periodo_us);
      digitalWrite(BUZZER, HIGH);
      delayMicroseconds(medio_periodo_us);
    }
    digitalWrite(BUZZER, HIGH);
  }

  // ---------------------------------------------------------------------------
  // La Cucaracha — melodia para "llamar a la mascota".
  // Usamos PWM por software (buzzer_beep) en vez de LEDC porque los 4 timers
  // LEDC del ESP32 los reserva ESP32Servo para el motor de la puerta. La salida
  // sigue siendo una onda cuadrada a la frecuencia indicada (PWM 50% duty).
  //
  // Frecuencias en escala de Do mayor (OCTAVA 6, 1-2 kHz):
  //   C6=1047  D6=1175  E6=1319  F6=1397  G6=1568  A6=1760  B6=1976  C7=2093
  // Elegimos esta octava porque los buzzers piezo (KY-006 y similares) tienen
  // su pico de respuesta entre 2-4 kHz; por debajo de 1 kHz suenan opacos.
  // Entrada con frecuencia_hz==0 = silencio.
  //
  // Letra y notas (version tradicional, fragmento estribillo):
  //   "La  cu-  ca-  ra-  cha,  la  cu-  ca-  ra-  cha"
  //    C6  C6   C6   F6   A6    C6  C6   C6   F6   A6
  //   "ya  no   pue- de   ca-   mi-  nar"   (7 silabas, 7 notas)
  //    A6  G6   F6   F6   E6   D6   C6
  // ---------------------------------------------------------------------------
  struct NotaMelodia {
    int frecuencia_hz;
    int duracion_ms;
  };

  static const NotaMelodia LA_CUCARACHA[] = {
    // Frase 1: "La cu-ca-ra-cha"
    {1047, 160}, {1047, 160}, {1047, 160}, {1397, 200}, {1760, 360},
    {0, 120},
    // Frase 2: "la cu-ca-ra-cha"
    {1047, 160}, {1047, 160}, {1047, 160}, {1397, 200}, {1760, 360},
    {0, 120},
    // Frase 3: "ya no pue-de ca-mi-nar"
    {1760, 160}, {1568, 160}, {1397, 160}, {1397, 160}, {1319, 160}, {1175, 160}, {1047, 460},
  };

  // Reproduce La Cucaracha completa. Bloquea la tarea ~3.6s.
  void tocar_la_cucaracha()
  {
    const size_t total = sizeof(LA_CUCARACHA) / sizeof(LA_CUCARACHA[0]);
    for (size_t i = 0; i < total; i++)
    {
      if (LA_CUCARACHA[i].frecuencia_hz > 0)
      {
        buzzer_beep(LA_CUCARACHA[i].frecuencia_hz, LA_CUCARACHA[i].duracion_ms);
        // Mini gap entre notas (20 ms) para que se distingan en vez de
        // sonar como una nota continua. Sin esto, dos notas iguales seguidas
        // (C6 C6 C6) suenan como un solo zumbido largo.
        digitalWrite(BUZZER, HIGH);
        vTaskDelay(pdMS_TO_TICKS(20));
      }
      else
      {
        // Silencio entre frases: apagamos el buzzer y esperamos.
        digitalWrite(BUZZER, HIGH);
        vTaskDelay(pdMS_TO_TICKS(LA_CUCARACHA[i].duracion_ms));
      }
    }
    // Garantizar que el buzzer queda apagado al terminar.
    digitalWrite(BUZZER, HIGH);
  }

  // ===========================================================================
  // OTA — Over The Air Update
  //
  // Patron usado:
  //   - La app Android publica MQTT pawgate/{device_id}/cmd/ota con payload
  //     {url, version, sha256?}.
  //   - El callback MQTT (mas abajo) recibe el cmd y llama a ejecutar_ota.
  //   - ejecutar_ota descarga el binario por HTTP (S3 publico), lo escribe a
  //     la particion app1 via Update.h y reinicia el ESP32. El bootloader
  //     marca app1 como activa en el proximo boot.
  //   - Mientras tanto se publican eventos en pawgate/{device_id}/events/ota
  //     (ota_started / ota_success / ota_failed) para que la app y el backend
  //     puedan trackear el progreso.
  //
  // Notas:
  //   - Bloquea la tarea MQTT durante la descarga (varios segundos). No es un
  //     problema porque post-OTA el ESP32 reinicia y reconecta.
  //   - HTTP (no HTTPS) porque el .bin esta en un bucket S3 publico, no hay
  //     informacion sensible. TLS agregaria 50-100 KB al binario para nada.
  //   - sha256_target es OPCIONAL: si viene en el payload, despues del Update
  //     verificamos contra el hash que reporta el bootloader. Si no coincide,
  //     fallamos antes de reiniciar (el binario corrupto NO se ejecuta).
  // ===========================================================================

  // El campo JSON DEBE llamarse "type" — eventIngest (lambda) lo lee con
  // `event.get("type")` para determinar event_type. Si usamos "event_type"
  // se ignora y el evento queda con type="ota" (el kind del topic) en vez
  // del subtipo real, y la app no lo encuentra al pollear por "ota_started".
  // Helper interno: publica DIRECTO al broker (sincrono), sin pasar por la cola
  // FreeRTOS queueMqttOut. Necesario para los eventos OTA: ejecutar_ota se
  // llama desde el callback MQTT, que corre en el thread de mqtt_task. Mientras
  // ejecutar_ota esta bloqueada (descarga + flash, ~15s), mqtt_task NO puede
  // drenar la cola — todos los mensajes encolados via publicar_mqtt() quedan
  // esperando. Y como ejecutar_ota termina con ESP.restart(), la cola se pierde.
  // Solucion: publicar directo aca, en el mismo thread donde corre client.loop().
  static void publicar_mqtt_sync_ota(const char* topic, const char* payload)
  {
    if (!client.connected()) {
      Serial.println("[mqtt] sync OTA: client NO conectado, skip");
      return;
    }
    bool ok = client.publish(topic, payload, true);
    if (!ok) {
      Serial.printf("[mqtt] sync OTA publish FALLO state=%d topic=%s\n",
                    client.state(), topic);
    } else {
      Serial.printf("[mqtt] sync OTA publish OK topic=%s len=%u\n",
                    topic, (unsigned)strlen(payload));
    }
    // Forzar a PubSubClient a flushear su TCP buffer
    client.loop();
  }

  void publicar_evento_ota(const char* status, const char* version, const char* error_msg)
  {
    char payload[256];
    if (error_msg && error_msg[0] != '\0') {
      snprintf(payload, sizeof(payload),
               "{\"type\":\"%s\",\"version\":\"%s\",\"error\":\"%s\","
               "\"current_version\":\"" FIRMWARE_VERSION "\"}",
               status, version ? version : "", error_msg);
    } else {
      snprintf(payload, sizeof(payload),
               "{\"type\":\"%s\",\"version\":\"%s\","
               "\"current_version\":\"" FIRMWARE_VERSION "\"}",
               status, version ? version : "");
    }
    Serial.printf("[ota] event %s -> %s\n", MQTT_TOPIC_EVENT_OTA, payload);
    publicar_mqtt_sync_ota(MQTT_TOPIC_EVENT_OTA, payload);
  }

  // Variante con bytes_written/total_bytes/percent — solo para ota_progress.
  void publicar_evento_ota_progress(const char* version, size_t bytes_written,
                                     int total_bytes, int percent)
  {
    char payload[256];
    snprintf(payload, sizeof(payload),
             "{\"type\":\"ota_progress\",\"version\":\"%s\","
             "\"bytes_written\":%u,\"total_bytes\":%d,\"percent\":%d,"
             "\"current_version\":\"" FIRMWARE_VERSION "\"}",
             version ? version : "", (unsigned)bytes_written, total_bytes, percent);
    Serial.printf("[ota] progress %d%% (%u/%d bytes)\n", percent, (unsigned)bytes_written, total_bytes);
    publicar_mqtt_sync_ota(MQTT_TOPIC_EVENT_OTA, payload);
  }

  void ejecutar_ota(const char* url, const char* version_target, const char* sha256_target)
  {
    Serial.printf("[ota] iniciando OTA url=%s version=%s\n",
                  url ? url : "(null)", version_target ? version_target : "(null)");

    if (!url || url[0] == '\0') {
      Serial.println("[ota] ERROR: url vacia");
      publicar_evento_ota("ota_failed", version_target, "missing_url");
      return;
    }
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("[ota] ERROR: WiFi desconectado");
      publicar_evento_ota("ota_failed", version_target, "wifi_down");
      return;
    }

    publicar_evento_ota("ota_started", version_target, NULL);
    // Damos tiempo a que el publish salga antes de bloquear el ciclo MQTT
    // con la descarga (el loop de MQTT NO va a poder responder pings durante
    // los proximos segundos).
    client.loop();
    vTaskDelay(pdMS_TO_TICKS(200));

    HTTPClient http;
    http.setTimeout(10000);                  // 10s para conectar + headers
    http.setConnectTimeout(8000);
    http.setReuse(false);

    Serial.printf("[ota] GET %s\n", url);
    if (!http.begin(url)) {
      Serial.println("[ota] ERROR: http.begin() fallo");
      publicar_evento_ota("ota_failed", version_target, "http_begin_failed");
      return;
    }

    int httpCode = http.GET();
    if (httpCode != HTTP_CODE_OK) {
      Serial.printf("[ota] ERROR: GET devolvio %d\n", httpCode);
      http.end();
      char err[48];
      snprintf(err, sizeof(err), "http_%d", httpCode);
      publicar_evento_ota("ota_failed", version_target, err);
      return;
    }

    int content_length = http.getSize();
    if (content_length <= 0) {
      Serial.println("[ota] ERROR: Content-Length invalido");
      http.end();
      publicar_evento_ota("ota_failed", version_target, "bad_content_length");
      return;
    }
    Serial.printf("[ota] descarga: %d bytes\n", content_length);

    if (!Update.begin(content_length)) {
      Serial.printf("[ota] ERROR: Update.begin fallo: %s\n", Update.errorString());
      http.end();
      publicar_evento_ota("ota_failed", version_target, "update_begin_failed");
      return;
    }

    // Nota sobre verificacion de hash: arduino-esp32 v2.x NO tiene
    // Update.setSHA256(); solo expone setMD5(const char*) que internamente
    // hace strlen() sin null-check (NULL -> crash inmediato con LoadProhibited).
    // Como en nuestro setup la confianza viene de S3 (solo nosotros podemos
    // subir .bin al bucket), no hacemos verificacion local. El sha256_target
    // que viene en el cmd queda como referencia pero no se valida.
    (void)sha256_target;

    // Stream del body HTTP -> particion OTA. Hacemos un loop CHUNKED en vez
    // de Update.writeStream() para poder publicar eventos ota_progress cada
    // 10% del binario. Eso le permite a la app Android (AsyncTask con
    // onProgressUpdate) mostrar una barra de avance en vez de "esperando..."
    // por 30 segundos.
    WiFiClient* stream = http.getStreamPtr();
    const size_t CHUNK = 1024;
    uint8_t buffer[CHUNK];
    size_t written = 0;
    int last_percent_reported = -1;
    unsigned long t_start = millis();
    unsigned long t_last_data = millis();

    while ((int)written < content_length) {
      size_t avail = stream->available();
      if (avail > 0) {
        size_t to_read = avail > CHUNK ? CHUNK : avail;
        int n = stream->readBytes(buffer, to_read);
        if (n <= 0) break;
        size_t w = Update.write(buffer, n);
        if (w != (size_t)n) {
          Serial.printf("[ota] ERROR: Update.write fallo: %s\n", Update.errorString());
          Update.abort();
          http.end();
          publicar_evento_ota("ota_failed", version_target, "write_failed");
          return;
        }
        written += n;
        t_last_data = millis();

        // Reportar progreso cada 10%
        int percent = (int)((written * 100ULL) / (size_t)content_length);
        int decile = (percent / 10) * 10;
        if (decile != last_percent_reported && decile > 0 && decile <= 90) {
          last_percent_reported = decile;
          publicar_evento_ota_progress(version_target, written, content_length, decile);
          // Damos tiempo al mqtt_task (que corre en otra tarea FreeRTOS) para que
          // efectivamente publique el mensaje al broker AWS. Sin este delay, los
          // 9 ota_progress se encolaban en rafaga y la cola FreeRTOS se llenaba
          // antes de que mqtt_task pudiera drenarla — perdiendo los ultimos
          // ota_progress y especialmente ota_success.
          vTaskDelay(pdMS_TO_TICKS(150));
        }
      } else {
        // Timeout: si pasan >10s sin datos, fallamos
        if (millis() - t_last_data > 10000UL) {
          Serial.println("[ota] ERROR: timeout sin datos del servidor");
          Update.abort();
          http.end();
          publicar_evento_ota("ota_failed", version_target, "stream_timeout");
          return;
        }
        vTaskDelay(pdMS_TO_TICKS(10));
      }
    }
    Serial.printf("[ota] descarga OK: %u bytes en %lums\n",
                  (unsigned)written, (unsigned long)(millis() - t_start));

    if ((int)written != content_length) {
      Serial.printf("[ota] ERROR: escribi %u de %d bytes\n",
                    (unsigned)written, content_length);
      Update.abort();
      http.end();
      publicar_evento_ota("ota_failed", version_target, "partial_write");
      return;
    }

    if (!Update.end(true)) {
      Serial.printf("[ota] ERROR: Update.end fallo: %s\n", Update.errorString());
      http.end();
      publicar_evento_ota("ota_failed", version_target, Update.errorString());
      return;
    }

    if (!Update.isFinished()) {
      Serial.println("[ota] ERROR: Update.isFinished() = false");
      http.end();
      publicar_evento_ota("ota_failed", version_target, "not_finished");
      return;
    }

    http.end();
    Serial.println("[ota] OK: nueva imagen escrita. Reiniciando en 2s...");
    publicar_evento_ota("ota_success", version_target, NULL);
    client.loop();
    vTaskDelay(pdMS_TO_TICKS(2000));   // tiempo para que el publish salga
    ESP.restart();
  }

  void leer_sensor_proximidad()
  {
    digitalWrite(sensor_proximidad.pin_trigger, LOW);
    delayMicroseconds(2);
    digitalWrite(sensor_proximidad.pin_trigger, HIGH);
    delayMicroseconds(10);
    digitalWrite(sensor_proximidad.pin_trigger, LOW);
    // Leer el tiempo de la señal
    sensor_proximidad.tiempo_transcurrido_ms = pulseIn(sensor_proximidad.pin_echo, HIGH, TIME_OUT_SENSOR_PROXIMIDAD);
    float distanciaCm = sensor_proximidad.tiempo_transcurrido_ms * sensor_proximidad.velocidad_sonido / 2;
    sensor_proximidad.distancia_actual_cm = distanciaCm;
  }

  bool sensor_proximidad_detectar_animal()
  {
    bool resultado = false;
    if (xSemaphoreTake(mutex_sensores, portMAX_DELAY) == pdTRUE) {
      if (sensor_proximidad.distancia_actual_cm < sensor_proximidad.distancia_minima_cm &&
          sensor_proximidad.distancia_actual_cm > sensor_proximidad.distancia_base_cm &&
          sensor_proximidad.estado == ESTADO_HABILITADO)
      {
        Serial.println("[sensor_proximidad_detectar_animal] Animal detectado desde adentro");
        Serial.println(sensor_proximidad.distancia_actual_cm);
        resultado = true;
      }
      else
      {
        Serial.println(sensor_proximidad.distancia_actual_cm);
      }
      xSemaphoreGive(mutex_sensores);
    }
    return resultado;
  }

  void leer_sensor_rfid()
  {
    sensor_rfid.acceso_permitido = false;

    if (!rfid.PICC_IsNewCardPresent() && !rfid.PICC_ReadCardSerial())
      return;

    Serial.println(F("RFID detectado"));

    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
    sensor_rfid.acceso_permitido = true;
  }

  bool sensor_rfid_detectar_animal()
  {
    bool resultado = false;
    if (xSemaphoreTake(mutex_sensores, portMAX_DELAY) == pdTRUE) {
      if (sensor_rfid.acceso_permitido &&
          sensor_rfid.estado == ESTADO_HABILITADO)
      {
        Serial.println("[sensor_rfid_detectar_animal] Animal detectado desde afuera");
        sensor_rfid.acceso_permitido = false;
        resultado = true;
      }
      xSemaphoreGive(mutex_sensores);
    }
    return resultado;
  }

  void detectar_animales_en_puerta()
  {
    leer_sensor_proximidad();
    if (sensor_proximidad_detectar_animal())
    {
      emitir_evento_puerta(EV_ANIMAL_DETECTADO_ADENTRO, "[puerta_deteccion]");
    }
    leer_sensor_rfid();
    if (sensor_rfid_detectar_animal())
    {
      emitir_evento_puerta(EV_ANIMAL_DETECTADO_AFUERA, "[puerta_deteccion]");
    }
  }

  void detectar_cambios_luz()
  {
      sensor_luz.valor_actual = analogRead(sensor_luz.pin);

      if(sensor_luz.valor_actual > UMBRAL_LUZ)
      {
        emitir_evento_puerta(EV_DIA_DETECTADO, "EV_DIA_DETECTADO");
      }
      else
      {
        emitir_evento_puerta(EV_NOCHE_DETECTADA, "EV_NOCHE_DETECTADA");
      }

  }

  // --- Helpers ---
  char leer_serial_puerta()
  {
    if (Serial.available() > 0)
    {
      char comando = Serial.read();
      if (comando == 'B')
      {
        return 'B';
      }
      else if (comando == 'D')
      {
        return 'D';
      }
    }
    return 'N';
  }

  void configuracion_sensores_puerta()
  {
    // Sensor de proximidad
    sensor_proximidad.pin_echo = SENSOR_PROXIMIDAD_ECHO;
    sensor_proximidad.pin_trigger = SENSOR_PROXIMIDAD_TRIGGER;
    sensor_proximidad.estado = ESTADO_HABILITADO;
    sensor_proximidad.distancia_actual_cm = 0;
    sensor_proximidad.tiempo_transcurrido_ms = 0;

    // Sensor RFID
    sensor_rfid.estado    = ESTADO_HABILITADO;
    sensor_rfid.id_tag    = 0;

    SPI.begin(RFID_SCK, RFID_MISO, RFID_MOSI, RFID_SS);
    rfid.PCD_Init();
  }

  void configuracion_sensores_luz()
  {
    sensor_luz.pin = FOTORESISTOR;
    sensor_luz.valor_actual = 0;
  }

  void configuracion_estado_inicial_puerta()
  {
    estado_actual_puerta = ST_CERRADA_NO_BLOQUEADA; // Agregar arranque!
    servo.write(90);                                // Posición inicial del servomotor
  }

  // --- Tareas ---
  void puerta_deteccion(void *pvParametros)
  {
    static bool app_supuesto_bloqueado = false;
    static int btn_estado_previo = LOW;

    while (1)
    {
      char bloqueo = leer_serial_puerta();
      if (bloqueo == 'B')
      {
        emitir_evento_puerta(EV_BLOQUEO_POR_APP, "[puerta_deteccion]");
      }
      else if (bloqueo == 'D')
      {
        emitir_evento_puerta(EV_DESBLOQUEO_POR_APP, "[puerta_deteccion]");
      }
      detectar_animales_en_puerta();

      vTaskDelay(pdMS_TO_TICKS(200));
    }
  }

  void luz_deteccion(void *pvParametros)
  {
    while (1)
    {
      detectar_cambios_luz();
      vTaskDelay(pdMS_TO_TICKS(2000));
    }
  }

  void puerta_controlador(void *pvParametros)
  {
    while (1)
    {
      eventos_puerta evento_recibido;
      if (xQueueReceive(queueEventos_puerta, &evento_recibido, 0) == pdPASS)
      {
        Serial.print("[puerta_controlador] Evento recibido");
        if (evento_recibido < CANT_MAX_EVENTOS_PUERTA)
        {
          transicion funcion_transicion = puerta_tabla_estados[estado_actual_puerta][evento_recibido];
          funcion_transicion();
        }
        else
        {
          Serial.println("[puerta_controlador] Evento fuera de rango");
        }
      }
      vTaskDelay(pdMS_TO_TICKS(200));
    }
  }

  void publicar_evento_puerta(const char* tipo, const char* direccion)
  {
    char buffer[TAM_PAYLOAD_MQTT];
    JsonDocument doc;
    doc["type"] = tipo;
    if (direccion != nullptr) doc["direction"] = direccion;
    doc["ts"] = millis();
    serializeJson(doc, buffer, sizeof(buffer));
    publicar_mqtt(MQTT_TOPIC_EVENT_DOOR, buffer);
  }

  static const char* ultima_direction_apertura = nullptr;
  static bool estado_luz_publicado = false;

  void puerta_accion(void *pvParametros)
  {
    while (1)
    {
      acciones_puerta action_recibido;
      if (xQueueReceive(queueAcciones_puerta, &action_recibido, 0) == pdPASS)
      {
        Serial.print("[puerta_accion] Accion recibida=");
        if (action_recibido == ACC_ABRIR_DESDE_AFUERA)
        {
          Serial.println("ACC_ABRIR_DESDE_AFUERA");
          servo.write(0);
          xTimerStart(timer_puerta, 0);
          ultima_direction_apertura = "in";
          publicar_evento_puerta("opened", "in");
        }
        else if (action_recibido == ACC_ABRIR_DESDE_ADENTRO)
        {
          Serial.println("ACC_ABRIR_DESDE_ADENTRO 180 grados ACA");
          servo.write(180);
          xTimerStart(timer_puerta, 0);
          ultima_direction_apertura = "out";
          publicar_evento_puerta("opened", "out");
        }
        else if (action_recibido == ACC_CERRAR)
        {
          Serial.println("ACC_CERRAR");
          servo.write(90);
          publicar_evento_puerta("closed", ultima_direction_apertura);
          ultima_direction_apertura = nullptr;
        }
        else if (action_recibido == ACC_BLOQUEAR)
        {
          Serial.println("ACC_BLOQUEAR");
          // Sonido descendente grave (600 -> 300 Hz): se bloquea
          buzzer_beep(600, 120);
          buzzer_beep(300, 200);
          publicar_evento_puerta("blocked", nullptr);
        }
        else if (action_recibido == ACC_DESBLOQUEAR)
        {
          Serial.println("ACC_DESBLOQUEAR");
          // Sonido ascendente agudo (600 -> 1200 Hz): se desbloquea
          buzzer_beep(600, 120);
          buzzer_beep(1200, 200);
          publicar_evento_puerta("unblocked", nullptr);
        }
        else if (action_recibido == ACC_ENCENDER_LUZ)
        {
          digitalWrite(LED, HIGH);
          if (!estado_luz_publicado) {
            publicar_evento_puerta("light_on", nullptr);
            estado_luz_publicado = true;
          }
        }
        else if (action_recibido == ACC_APAGAR_LUZ)
        {
          digitalWrite(LED, LOW);
          // Mismo dedup: publicar solo on -> off.
          if (estado_luz_publicado) {
            publicar_evento_puerta("light_off", nullptr);
            estado_luz_publicado = false;
          }
        }
        else
        {
          Serial.println("[puerta_accion] Accion fuera de rango");
        }
      }
      vTaskDelay(pdMS_TO_TICKS(200));
    }
  }
  
  // --- Telemetría ---
  void publicar_telemetry()
  {
    char buffer[TAM_PAYLOAD_MQTT];
    JsonDocument doc;
    doc["type"] = "telemetry";
    doc["ts"] = millis();
    doc["uptime_s"] = millis() / 1000;
    doc["rssi_dbm"] = WiFi.RSSI(); // Devuelve int. Valor negativo (-30 muy bueno, -90 muy malo)
    doc["free_heap_kb"] = ESP.getFreeHeap() / 1024; // Pasamos a KB
    doc["total_heap_kb"] = ESP.getHeapSize() / 1024; // RAM total disponible para heap. ~320KB en ESP32 estándar
    doc["flash_used_kb"] = ESP.getSketchSize() / 1024; // Bytes ocupados por el firmware en flash
    doc["flash_total_kb"] = ESP.getFlashChipSize() / 1024; // Tamaño total del chip de flash. Típicamente 4096 KB
    doc["cpu_temp_c"] = temperatureRead(); // Disponible en ESP32, devuelve float en °C
    doc["local_ip"] = WiFi.localIP().toString().c_str();
    doc["device_mac"] = WiFi.macAddress().c_str(); // MAC propia del ESP32 (no la del AP)
    doc["firmware_version"] = FIRMWARE_VERSION;
    doc["hardware_model"] = HARDWARE_MODEL;
    doc["wifi_ssid"] = WiFi.SSID().c_str();
    doc["wifi_bssid"] = WiFi.BSSIDstr().c_str(); // MAC address del AP
    doc["wifi_band"] = "2.4 GHz"; // ESP32 estándar no tiene 5GHz nativo
    doc["wifi_gateway"] = WiFi.gatewayIP().toString().c_str(); // IP del router
    doc["wifi_security"] = "WPA2-PSK";

    size_t written = serializeJson(doc, buffer, sizeof(buffer));
    if (written == 0 || written >= sizeof(buffer)) {
      Serial.println("[telemetry] buffer chico, payload truncado"); // Verificamos si nos alcanzó el TAM_PAYLOAD_MQTT
    }
    publicar_mqtt(MQTT_TOPIC_EVENT_TELEMETRY, buffer);
  }

  void telemetry_task(void *pvParametros)
  {
    // Esperar a que WiFi esté conectado antes del primer publish
    while (WiFi.status() != WL_CONNECTED) {
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    while (1) {
        publicar_telemetry();
        vTaskDelay(pdMS_TO_TICKS(TELEMETRY_INTERVAL_MS));
    }
  }

  // --- Setup ---
  void crear_colas_puerta()
  {
    queueEventos_puerta  = xQueueCreate(TAM_EV_COLA_PUERTA,  sizeof(eventos_puerta));
    queueAcciones_puerta = xQueueCreate(TAM_ACC_COLA_PUERTA, sizeof(acciones_puerta));
    queueMqttOut = xQueueCreate(TAM_COLA_MQTT, sizeof(stMensajeMqtt));
  }

  void crear_tareas_puerta()
  {
    int tam_stack_bytes = TAM_STACK_TAREAS;
    xTaskCreate(puerta_deteccion,   "Puerta detección",   tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(luz_deteccion,      "Luz detección",      tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(puerta_controlador, "Puerta controlador", tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(puerta_accion,      "Puerta accion",      tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(mqtt_task,          "MQTT task",          tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(telemetry_task,     "Telemetry task",     tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
  }

  void crear_mutex_sensores()
  {
    mutex_sensores = xSemaphoreCreateMutex();
    if (mutex_sensores == NULL)
    {
      Serial.println("[crear_mutex_sensores] No se pudo crear mutex_sensores");
    }
  }

  void setup_puerta()
  {
    crear_colas_puerta();
    configuracion_sensores_puerta();
    configuracion_sensores_luz();
    configuracion_estado_inicial_puerta();
    crear_mutex_sensores();
    timer_puerta = xTimerCreate("Timer_Puerta", pdMS_TO_TICKS(TIEMPO_TIMEOUT_PUERTA), pdFALSE, NULL, timer_callback_puerta);
    crear_tareas_puerta();
  }

  // ================================================================
  // ENTRY POINTS
  // ================================================================

  void configuracion_debbug_esp32()
  {
    // Configurar el puerto serial para debugguear
    Serial.begin(PUERTO_SERIAL_WOKWY);
  }

  void configuracion_pines_esp32()
  {
    pinMode(LED, OUTPUT);
    pinMode(FOTORESISTOR, INPUT);
    pinMode(BUZZER, OUTPUT);
    digitalWrite(BUZZER, HIGH);
    servo.attach(SERVO);
    pinMode(SENSOR_PROXIMIDAD_ECHO, INPUT);
    pinMode(SENSOR_PROXIMIDAD_TRIGGER, OUTPUT);
  }

  void setup()
  {
    configuracion_debbug_esp32();
    configuracion_pines_esp32();
    setup_wifi_mqtt();
    setup_puerta();

    initStats();
    initTime=millis();
  }


  // ---------------- WIFI y Broker MQTT ----------------

  //funcion genérica para mandar a MQTT desde cualquier parte del código a cualquier tópico
  void publicar_mqtt(const char* topico, const char* payload)
  {
    Serial.printf("\n[mqtt] queueing topic=%s payload=%s\n", topico, payload);
    stMensajeMqtt msg;
    strncpy(msg.topico,   topico,   TAM_TOPIC_MQTT   - 1);
    strncpy(msg.payload, payload, TAM_PAYLOAD_MQTT - 1);
    msg.topico[TAM_TOPIC_MQTT - 1]     = '\0';
    msg.payload[TAM_PAYLOAD_MQTT - 1] = '\0';

    if (xQueueSend(queueMqttOut, &msg, TIME_OUT_CERO) != pdPASS)
    {
      Serial.println("[mqtt] Cola de salida LLENA");
    } else {
      Serial.println("[mqtt] queued OK");
    }
  }

  void setup_wifi_mqtt()
  {
    Serial.println();
    Serial.print("Conectando a: ");
    Serial.println(WIFI_SSID);

    wifiConnect();

    sincronizar_hora_ntp();
    definir_broker();

    Serial.println("\nWiFi Conectado");
    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());
    Serial.print("MAC address: ");
    Serial.println(WiFi.macAddress());
  }

  void wifiConnect()
  {
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    while (WiFi.status() != WL_CONNECTED)
    {
      delay(500);
      Serial.print(".");
    }
  }

  void sincronizar_hora_ntp()
  {
    configTime(0, 0, "pool.ntp.org", "time.nist.gov");
    Serial.print("Sincronizando hora via NTP");
    time_t now = 0;
    int intentos = 0;
    while (now < 1700000000 && intentos < 30)
    {
      delay(500);
      Serial.print(".");
      now = time(nullptr);
      intentos++;
    }
    if (now < 1700000000) {
      Serial.println("\n[ntp] WARN: hora no sincronizada, TLS puede fallar");
      return;
    }
    Serial.print("\n[ntp] hora UTC: ");
    Serial.println(ctime(&now));
  }

  void conectar_mqtt()
  {
    int intentos = 0;
    client.setServer(mqtt_server, mqtt_port);
    client.setCallback(callback); // Esto es necesario para recibir mensajes del broker

    espClient.setHandshakeTimeout(15);   // segundos para TLS handshake
    espClient.setTimeout(15);            // segundos para read/write TCP
    client.setSocketTimeout(15);         // segundos para MQTT-level

    Serial.printf("\n[mqtt] target=%s:%d clientId=%s\n",
                  mqtt_server, mqtt_port, MQTT_CLIENT_ID);

    {
      WiFiClient tcpTest;
      Serial.printf("[debug] TCP raw test %s:%d ... ", mqtt_server, mqtt_port);
      unsigned long t0 = millis();
      int tcpOk = tcpTest.connect(mqtt_server, mqtt_port, 5000); // 5s timeout
      unsigned long dt = millis() - t0;
      Serial.printf("result=%d took=%lums\n", tcpOk, dt);
      tcpTest.stop();
      if (!tcpOk) {
        Serial.println("[debug] TCP raw FAILED — verificar firewall/ISP en pto 8883");
      }
    }

    Serial.print("Intentando conexión MQTT...");
    while (!client.connected() && intentos < 5)
    {
      intentos++;
      Serial.printf("\n[mqtt] intento %d Conectando...", intentos);
      unsigned long t0 = millis();
      bool ok = client.connect(MQTT_CLIENT_ID);
      unsigned long dt = millis() - t0;
      Serial.printf(" took=%lums ", dt);
      if (ok)
      {
        Serial.println("OK");
        client.subscribe(MQTT_TOPIC_CMD_FILTER);
        Serial.printf("[mqtt] subscribed to %s\n", MQTT_TOPIC_CMD_FILTER);
      }
      else
      {
        int state = client.state();
        Serial.printf("FALLO rc=%d, retry 5s\n", state);
        vTaskDelay(pdMS_TO_TICKS(5000));
      }
    }
    if (intentos >= 5) {
      Serial.println("Error: No se pudo conectar al broker MQTT");
      return;
    }
  }

  void definir_broker()
  {
    switch (BROKER)
    {
      case HIVEMQ_PUBLIC:
        mqtt_server = "broker.hivemq.com";
        mqtt_port   = 1883;
        mqtt_user   = NULL;   // sin auth
        mqtt_pass   = NULL;
        break;
      case EMQX:
        mqtt_server = "broker.emqx.io";
        mqtt_port   = 1883;
        mqtt_user   = "emqx";
        mqtt_pass   = "public";
        break;
      case MOSQUITTO_LOCAL:
        mqtt_server = "192.168.0.10"; // tu IP local
        mqtt_port   = 1883;
        mqtt_user   = NULL;
        mqtt_pass   = NULL;
        break;
      case AWS_IOT_CORE:
        mqtt_server = AWS_IOT_ENDPOINT;
        mqtt_port   = AWS_IOT_PORT;
        mqtt_user   = NULL;
        mqtt_pass   = NULL;
        espClient.setCACert(AWS_ROOT_CA);
        espClient.setCertificate(AWS_DEVICE_CERT);
        espClient.setPrivateKey(AWS_PRIVATE_KEY);
        client.setBufferSize(BUFFER_SIZE);
        break;
      default:
        Serial.println("Error: Broker mal seleccionado");
        break;
    }
  }

  // Función Callback que recibe los mensajes enviados por los dispositivos
  void callback(char* topico, byte* message, unsigned int length) 
  {    
    Serial.print("Se recibió mensaje en el tópico: ");
    Serial.println(topico);

    char* comando = strrchr(topico, '/');
    eventos_puerta ev;

    if(!comando) {
      Serial.print("El tópico recibido está malformado.");
      return;
    } else if(strcmp(comando + 1, "open") == 0) {
      JsonDocument doc;
      DeserializationError err = deserializeJson(doc, message, length);
      if (err) {
          Serial.print("JSON inválido: ");
          Serial.println(err.c_str());
          return;
      }
      const char* direction = doc["direction"];
      if(direction != nullptr) {
        if(strcmp(direction, "in") == 0) {
          Serial.println("cmd open direction=in -> EV_ANIMAL_DETECTADO_AFUERA");
          ev = EV_ANIMAL_DETECTADO_AFUERA;
        } else if (strcmp(direction, "out") == 0){
          Serial.println("cmd open direction=out -> EV_ANIMAL_DETECTADO_ADENTRO");
          ev = EV_ANIMAL_DETECTADO_ADENTRO;
      } else {
        Serial.println("ERROR: LA DIRECCION DE APERTURA NO ES CORRECTA");
        return;
      }
    } else {
      Serial.println("ERROR: FALTA DIRECCION DE APERTURA DE PUERTA");
      return;
    }
    } else if(strcmp(comando + 1, "block") == 0) {
      Serial.println("EV_BLOQUEO_POR_APP DESDE MQTT");
      ev = EV_BLOQUEO_POR_APP;
    } else if(strcmp(comando + 1, "unblock") == 0) {
      Serial.println("EV_DESBLOQUEO_POR_APP DESDE MQTT");
      ev = EV_DESBLOQUEO_POR_APP;
    } else if(strcmp(comando + 1, "call") == 0) {
      // En v1.0.0 (BEEP_SIMPLE definido en platformio.ini) hacemos el beep
      // alternado historico para que el dia de la demo se vea claramente el
      // ANTES de la OTA. En v1.1.0 (default) tocamos La Cucaracha completa
      // con PWM por software. Duracion ~4s, alineada con CALLING_MS=4000 del
      // state machine del Android.
      #ifdef BEEP_SIMPLE
        Serial.println("LLAMAR AL ANIMAL DESDE MQTT (beep simple v1.0.0)");
        for (int i = 0; i < 5; i++) {
          buzzer_beep(1200, 150);
          vTaskDelay(pdMS_TO_TICKS(100));
          buzzer_beep(800, 150);
          vTaskDelay(pdMS_TO_TICKS(100));
        }
      #else
        Serial.println("LLAMAR AL ANIMAL DESDE MQTT (La Cucaracha v1.1.0+)");
        tocar_la_cucaracha();
      #endif
      return; // No cambia el estado de la puerta
    } else if(strcmp(comando + 1, "cancel") == 0) {
      Serial.println("CANCELAR COMANDO DESDE MQTT");
      return; // No cambia el estado de la puerta
    } else if(strcmp(comando + 1, "reboot") == 0) {
      Serial.println("REINICIO ESP32 DESDE MQTT");
      delay(100); // Damos tiempo a que se imprima al serial
      ESP.restart();
    } else if(strcmp(comando + 1, "ota") == 0) {
      // Payload: {"url": "http://...bin", "version": "1.1.0", "sha256": "..."}
      // sha256 es opcional. Si viene, Update lo verifica antes de marcar
      // la particion como booteable.
      Serial.println("OTA: cmd recibido");
      JsonDocument doc;
      DeserializationError err = deserializeJson(doc, message, length);
      if (err) {
        Serial.printf("[ota] JSON invalido: %s\n", err.c_str());
        publicar_evento_ota("ota_failed", "", "bad_json");
        return;
      }
      const char* url = doc["url"] | "";
      const char* version_target = doc["version"] | "";
      const char* sha256_target = doc["sha256"] | "";
      if (url[0] == '\0') {
        Serial.println("[ota] ERROR: campo 'url' vacio");
        publicar_evento_ota("ota_failed", version_target, "missing_url");
        return;
      }
      // Nota: ejecutar_ota es BLOQUEANTE. La tarea MQTT no va a procesar
      // mas mensajes hasta que termine. Despues del OTA el ESP32 reinicia
      // y vuelve a suscribirse limpio.
      ejecutar_ota(url, version_target, sha256_target);
      return; // No toca la FSM de la puerta
    } else {
      Serial.println("COMANDO DESCONOCIDO");
      return;
    }

    xQueueSend(queueEventos_puerta, &ev, 0); // no bloqueante
  }

  void mqtt_task(void *pvParametros)
  {
    while (1)
    {
      if (!client.connected())
      {
        conectar_mqtt();
      }
      else
      {
        client.loop();

        // Obtiene un mensaje de la cola y lo publica en MQTT
        stMensajeMqtt msg;
        while (xQueueReceive(queueMqttOut, &msg, 0) == pdPASS)
        {
          Serial.printf("\n[mqtt] publishing topic=%s payload=%s\n",
                        msg.topico, msg.payload);
          bool ok = client.publish(msg.topico, msg.payload, true);
          if (!ok)
          {
            Serial.printf("[mqtt] publish FALLO state=%d\n", client.state());
          } else {
            Serial.println("[mqtt] publish OK");
          }
        }
      }
      vTaskDelay(pdMS_TO_TICKS(200));
    }
  }

  void loop() {
   
  actualTime=millis();
  
  //cantidad de tiempo que se va a tomar las muestras 
  if(actualTime-initTime>SAMPLING_TIME){
    initTime=actualTime;
    finishStats();
   }
   vTaskDelay(pdMS_TO_TICKS(100)); // se cede CPU
  }