  #include <ESP32Servo.h>
  #include <MFRC522.h>
  #include <WiFi.h>
  #include <WiFiClientSecure.h>
  #include <ArduinoJson.h>
  #include <time.h>          // configTime() + time() para sync NTP previo a TLS
  #include "PubSubClient.h" // Hay que instalar PubSubClient@2.8.0
  #include "aws_certs.h"

  // WiFiClient espClient; // Se activa cuando no queremos correr contra AWS IoT Core
  WiFiClientSecure espClient;
  PubSubClient client(espClient);

  // WIFI
  #define WIFI_SSID "Moriste en madrid 2.4ghz"
  #define WIFI_PASSWORD "tobichester"

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
  #define FIRMWARE_VERSION "1.0.0"
  #define HARDWARE_MODEL "ESP32-WROOM-32"
  #define TELEMETRY_INTERVAL_MS 30000

  // Topics y ClientID
  // #define MQTT_CLIENT_ID "esp32-puerta-soa" // Se podría aleatorizar en runtime
  // #define MQTT_TOPIC_CMD "soa/puerta/cmd" // Para recibir bloqueo/desbloqueo
  // #define MQTT_TOPIC_EVENT_DOOR "soa/puerta/evento" // Para enviar eventos de la puerta
  #define MQTT_CLIENT_ID AWS_THING_NAME
  #define MQTT_TOPIC_CMD_FILTER "pawgate/pawgate-001/cmd/+"
  #define MQTT_TOPIC_EVENT_DOOR "pawgate/pawgate-001/events/door"
  #define MQTT_TOPIC_EVENT_TELEMETRY "pawgate/pawgate-001/events/telemetry"

  #define TAM_PAYLOAD_MQTT 512
  #define TAM_TOPIC_MQTT   64
  #define TAM_COLA_MQTT    10

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
  #define BUTTON_APP 14 // Pulsador de bloqueo/desbloqueo (toggle), pull-down externo en diagram.json

  // Sensores
  #define UMBRAL_LUZ 2048  // Probar en wokwi y ajustar
  #define TIME_OUT_SENSOR_PROXIMIDAD 30000
  #define PUERTO_SERIAL_WOKWY 115200

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
    const float distancia_base_cm = 5;
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
  } estado_actual_puerta; // Declaro el estado global de la puerta

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
  void puerta_deteccion(void *pvParametros);
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
    emitir_accion_puerta(ACC_ABRIR_DESDE_ADENTRO, ">> Acción emitida: ACC_ABRIR_DESDE_ADENTRO");
  }

  void abrir_desde_afuera()
  {
    estado_actual_puerta = ST_ABIERTA_DESDE_AFUERA;
    emitir_accion_puerta(ACC_ABRIR_DESDE_AFUERA, ">> Acción emitida: ACC_ABRIR_DESDE_AFUERA");
  }

  // Cierre de la puerta
  void cerrar_puerta()
  {
    estado_actual_puerta = ST_CERRADA_NO_BLOQUEADA;
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
    // Emitir la acción a la cola de acciones
    //Serial.print("Transición iniciada: Luz apagada\n");
    //emitir_accion_puerta(ACC_APAGAR_LUZ, ">> Acción emitida: ACC_APAGAR_LUZ");
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
    if (sensor_proximidad.distancia_actual_cm < sensor_proximidad.distancia_minima_cm &&
        sensor_proximidad.distancia_actual_cm > sensor_proximidad.distancia_base_cm &&
        sensor_proximidad.estado == ESTADO_HABILITADO &&
        estado_actual_puerta == ST_CERRADA_NO_BLOQUEADA)
    {
      Serial.println("[sensor_proximidad_detectar_animal] Animal detectado desde adentro");
      Serial.println(sensor_proximidad.distancia_actual_cm);
      return true;
    }
    else
    {
      // Serial.println("[sensor_proximidad_detectar_animal] Animal no detectado desde adentro");
      Serial.println(sensor_proximidad.distancia_actual_cm);
      return false;
    }
  }

  void leer_sensor_rfid()
  {
    // Limpiar flag stale antes de evaluar: el flag representa "tarjeta detectada en esta iteracion".
    // Sin este reset, una lectura previa hecha mientras la puerta estaba BLOQUEADA queda flotando
    // y dispara una deteccion fantasma al desbloquear.
    sensor_rfid.acceso_permitido = false;

    if (!rfid.PICC_IsNewCardPresent() && !rfid.PICC_ReadCardSerial())
      return;

    Serial.println(F("RFID detectado"));

    // Magia negra del RFID, no tocar
    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
    sensor_rfid.acceso_permitido = true;
  }

  bool sensor_rfid_detectar_animal()
  {
    if (sensor_rfid.acceso_permitido &&
        sensor_rfid.estado == ESTADO_HABILITADO &&
        estado_actual_puerta == ST_CERRADA_NO_BLOQUEADA)
    {
      Serial.println("[sensor_rfid_detectar_animal] Animal detectado desde afuera");
      sensor_rfid.acceso_permitido = false;
      return true;
    }
    else
    {
      // Serial.println("[sensor_rfid_detectar_animal] Animal no detectado desde afuera");
      return false;
    }
  }

  void detectar_animales_en_puerta()
  {
    leer_sensor_proximidad();
    if (sensor_proximidad_detectar_animal())
    {
      emitir_evento_puerta(EV_ANIMAL_DETECTADO_ADENTRO, "[puerta_deteccion]");
      sensor_rfid.estado       = ESTADO_DESHABILITADO;
      sensor_proximidad.estado = ESTADO_DESHABILITADO;
    }
    leer_sensor_rfid();
    if (sensor_rfid_detectar_animal())
    {
      emitir_evento_puerta(EV_ANIMAL_DETECTADO_AFUERA, "[puerta_deteccion]");
      sensor_proximidad.estado = ESTADO_DESHABILITADO;
      sensor_rfid.estado       = ESTADO_DESHABILITADO;
    }
  }

  void detectar_cambios_luz()
  {
      // Leer el valor del fotoresistor
      // Comparar con el umbral
      // Emitir evento correspondiente a la cola de eventos
      sensor_luz.valor_actual = analogRead(sensor_luz.pin);

      //Serial.print("[luz_deteccion] ADC=");
      //Serial.print(sensor_luz.valor_actual);

      if (estado_actual_puerta == ST_CERRADA_BLOQUEADA || estado_actual_puerta == ST_CERRADA_NO_BLOQUEADA)
      {
        if(sensor_luz.valor_actual > UMBRAL_LUZ)
        {
          emitir_evento_puerta(EV_DIA_DETECTADO, "EV_DIA_DETECTADO");
        }
        else
        {
          emitir_evento_puerta(EV_NOCHE_DETECTADA, "EV_NOCHE_DETECTADA");
        }
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
    sensor_rfid.id_tag    = 0; //esto no lo estamos usando para nada por ahora

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
      // Pulsador de la app (D14): toggle bloqueo/desbloqueo. Funciona en cualquier estado.
      //int btn_actual = digitalRead(BUTTON_APP);
      int btn_actual = 0;
      //Serial.println("--- BOTON: ");
      //Serial.print(btn_actual);
      if (btn_actual == HIGH && btn_estado_previo == LOW)
      {
        app_supuesto_bloqueado = !app_supuesto_bloqueado;
        eventos_puerta evento = app_supuesto_bloqueado ? EV_BLOQUEO_POR_APP : EV_DESBLOQUEO_POR_APP;
        if (xQueueSend(queueEventos_puerta, &evento, 0) == pdPASS)
        {
          Serial.print(">> Evento puerta (boton): ");
          Serial.println(app_supuesto_bloqueado ? "EV_BLOQUEO_POR_APP" : "EV_DESBLOQUEO_POR_APP");
        }
      }
      btn_estado_previo = btn_actual;

      if (estado_actual_puerta == ST_CERRADA_NO_BLOQUEADA)
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
      }
      detectar_animales_en_puerta();
      detectar_cambios_luz();

      vTaskDelay(pdMS_TO_TICKS(200));
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

  // Direction de la ultima apertura, para incluirla en el evento "closed".
  // Asi la app puede saber HACIA DONDE se cerro la puerta (in/out) y matchear
  // el ciclo visual abrir->cerrar de manera consistente.
  static const char* ultima_direction_apertura = nullptr;

  // Ultimo estado de la luz que PUBLICAMOS al backend. La maquina dispara
  // ACC_ENCENDER_LUZ continuamente mientras detecta oscuridad — solo
  // publicamos en la TRANSICION para no inundar el backend.
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
          // ABRIR_DESDE_AFUERA = el animal viene de afuera y entra a casa.
          // La puerta se abre HACIA ADENTRO -> direction = "in".
          Serial.println("ACC_ABRIR_DESDE_AFUERA");
          servo.write(0);
          xTimerStart(timer_puerta, 0);
          ultima_direction_apertura = "in";
          publicar_evento_puerta("opened", "in");
        }
        else if (action_recibido == ACC_ABRIR_DESDE_ADENTRO)
        {
          // ABRIR_DESDE_ADENTRO = el animal sale al patio.
          // La puerta se abre HACIA AFUERA -> direction = "out".
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
          sensor_proximidad.estado = ESTADO_HABILITADO;
          sensor_rfid.estado       = ESTADO_HABILITADO;
          // Pasamos la misma direction que el opened previo, asi la app
          // matchea el ciclo (abriendo->abierta->cerrando hacia la misma X).
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
          // Dedup: la maquina dispara ACC_ENCENDER_LUZ con cada lectura del
          // sensor de luz (si esta oscuro), no solo cuando cambia. Publicamos
          // light_on solo en la TRANSICION off -> on para no inundar el
          // backend. estado_luz_publicado se inicializa en false al boot.
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
    xTaskCreate(puerta_controlador, "Puerta controlador", tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(puerta_accion,      "Puerta accion",      tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(mqtt_task,          "MQTT task",          tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
    xTaskCreate(telemetry_task,     "Telemetry task",     tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
  }

  void setup_puerta()
  {
    crear_colas_puerta();
    configuracion_sensores_puerta();
    configuracion_sensores_luz();
    configuracion_estado_inicial_puerta();
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
    // pinMode(BUTTON_APP, INPUT);
  }

  void setup()
  {
    configuracion_debbug_esp32();
    configuracion_pines_esp32();
    setup_wifi_mqtt();
    setup_puerta();
  }

  void loop() {}


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

    // SNTP sync ANTES de definir broker. Critico para TLS contra AWS IoT:
    // mTLS valida notBefore/notAfter del cert y para eso necesita la hora
    // actual. Sin esto, el ESP32 arranca en 1970 y client.connect() se
    // cuelga en el handshake TLS sin nunca devolver error visible.
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
    // configTime arranca el sntp client interno del ESP32. Argumentos:
    //   gmtOffset_sec = 0   -> UTC (TLS necesita UTC, no la timezone local)
    //   daylightOffset_sec = 0
    //   servers NTP        -> redundancia con 2 servers publicos
    configTime(0, 0, "pool.ntp.org", "time.nist.gov");
    Serial.print("Sincronizando hora via NTP");
    time_t now = 0;
    int intentos = 0;
    // Esperamos hasta que time(NULL) devuelva un valor > 2024-01-01
    // (segundo 1700000000 ~= mediados 2023). Antes de eso el RTC sigue
    // sin sincronizar y TLS va a fallar.
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

    // Timeouts explicitos para no quedarnos colgados eternamente en el TLS
    // handshake. Si supera 15s, espClient.connect() devuelve false y
    // PubSubClient hace return -> entramos al else y vemos rc=N.
    espClient.setHandshakeTimeout(15);   // segundos para TLS handshake
    espClient.setTimeout(15);            // segundos para read/write TCP
    client.setSocketTimeout(15);         // segundos para MQTT-level

    Serial.printf("\n[mqtt] target=%s:%d clientId=%s\n",
                  mqtt_server, mqtt_port, MQTT_CLIENT_ID);

    // Smoke test TCP crudo (sin TLS) — descarta firewall / ISP bloqueando
    // el puerto 8883 outgoing. Si esto NO completa, el TLS handshake nunca
    // tendra chance — el bug es de red, no de certs.
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
        // PubSubClient state codes:
        //   -4 timeout, -3 connection lost, -2 connect failed (TLS),
        //   -1 disconnected, 0 connected, 1-5 wrong proto / id / cred
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
    // Ver código de esteban, hace mas cosas que solo definir variables
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
        // direction "in" = puerta HACIA ADENTRO => animal viene de afuera
        //                  => EV_ANIMAL_DETECTADO_AFUERA (afuera_entrando)
        // direction "out"= puerta HACIA AFUERA  => animal sale desde adentro
        //                  => EV_ANIMAL_DETECTADO_ADENTRO (adentro_saliendo)
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
      Serial.println("LLAMAR AL ANIMAL DESDE MQTT");
      for (int i = 0; i < 3; i++) {
        buzzer_beep(1200, 100);
      }
      return; // No cambia el estado de la puerta
    } else if(strcmp(comando + 1, "cancel") == 0) {
      Serial.println("CANCELAR COMANDO DESDE MQTT");
      return; // No cambia el estado de la puerta
    } else if(strcmp(comando + 1, "reboot") == 0) {
      Serial.println("REINICIO ESP32 DESDE MQTT");
      delay(100); // Damos tiempo a que se imprima al serial
      ESP.restart();
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