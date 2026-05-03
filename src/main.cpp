#include <ESP32Servo.h>
#include <MFRC522.h>

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
#define MAX_CANT_SENSORES 1
#define IDX_SENSOR_LUZ 0
#define UMBRAL_LUZ 2048  // Probar en wokwi y ajustar
#define TIME_OUT_SENSOR_PROXIMIDAD 30000
#define PUERTO_SERIAL_WOKWY 115200

// Tareas
#define TIME_OUT_CERO 0
#define TAM_STACK_TAREAS 8192
#define PRECEDENCIA_POR_DEFECTO 1
#define MILISEGUNDOS_DE_DELAY 2000

// Luz — tamaños de colas y tabla de estados
#define CANT_MAX_EVENTOS_LUZ 3
#define CANT_MAX_ESTADOS_LUZ 2
#define CANT_MAX_ACCIONES_LUZ 2
#define TAM_EV_COLA_LUZ 10
#define TAM_ACC_COLA_LUZ 10

// Puerta — tamaños de colas y tabla de estados
#define CANT_MAX_EVENTOS_PUERTA 7
#define CANT_MAX_ESTADOS_PUERTA 5
#define CANT_MAX_ACCIONES_PUERTA 5
#define TAM_EV_COLA_PUERTA 10
#define TAM_ACC_COLA_PUERTA 10
#define TIEMPO_TIMEOUT_PUERTA 4500


// ================================================================
// TIPOS COMPARTIDOS
// ================================================================

enum estado_sensor
{
  ESTADO_HABILITADO,
  ESTADO_DESHABILITADO
};

struct stSensor
{
  int pin;
  int estado; // arreglar estado
  long valor_actual;
  long valor_previo;
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
};

struct stSensorRFID
{
  int pin_ss;
  int pin_reset;
  estado_sensor estado;
  int id_tag;
  bool acceso_permitido;
};

typedef void (*transicion)();

void none()
{
  return;
}


// ================================================================
// SUBSISTEMA LUZ
// ================================================================

// --- Enums & variables globales ---
enum eventos_luz
{
  EV_CONT,
  EV_DIA_DETECTADO,
  EV_NOCHE_DETECTADA
};

enum estados_luz
{
  ST_LUZ_APAGADA,
  ST_LUZ_ENCENDIDA
} estado_actual_luz; // Declaro el estado global de la luz

enum acciones_luz
{
  ACC_ENCENDER_LUZ,
  ACC_APAGAR_LUZ
};

QueueHandle_t queueEventos_luz;
QueueHandle_t queueAcciones_luz;
stSensor sensores[MAX_CANT_SENSORES]; // sacar array

// --- Prototipos ---
void encender_luz();
void apagar_luz();
void luz_deteccion(void *pvParametros);
void luz_controlador(void *pvParametros);
void luz_accion(void *pvParametros);
void configuracion_sensores_luz();
void configuracion_estado_inicial_luz();
void crear_colas_luz();
void crear_tareas_luz();
void setup_luz();
void emitir_accion_luz(acciones_luz action, const char* nombre);
void emitir_evento_luz(eventos_luz evento, const char* nombre);

// --- Tabla de estados ---
transicion luz_tabla_estados[CANT_MAX_ESTADOS_LUZ][CANT_MAX_EVENTOS_LUZ] =
{
    {  none,      none,               encender_luz      }, // state ST_LUZ_APAGADA
    {  none,      apagar_luz,         none              }  // state ST_LUZ_ENCENDIDA
    // EV_CONT  , EV_DIA_DETECTADO  , EV_NOCHE_DETECTADA
};

// --- Helpers de cola ---
void emitir_accion_luz(acciones_luz action, const char* nombre)
{
  if (xQueueSend(queueAcciones_luz, &action, TIME_OUT_CERO) != pdPASS)
  {
    Serial.println("[luz] Cola de acciones LLENA");
  }
  else 
  {
    Serial.print(nombre);
  }
}

void emitir_evento_luz(eventos_luz evento, const char* nombre)
{
  if (xQueueSend(queueEventos_luz, &evento, TIME_OUT_CERO) != pdPASS)
  {
    Serial.println("[luz] Cola de eventos LLENA");
  }
  else
  {
    Serial.print(">> Evento emitido: ");
    Serial.println(nombre);
  }
}

// --- Funciones de transición ---
void encender_luz()
{
  // Emitir la acción a la cola de acciones
  // Transicionar a ST_LUZ_ENCENDIDA
  Serial.print("Transición iniciada: Luz encendida\n");
  estado_actual_luz = ST_LUZ_ENCENDIDA;
  emitir_accion_luz(ACC_ENCENDER_LUZ, ">> Acción emitida: ACC_ENCENDER_LUZ");
  return;
}

void apagar_luz()
{
  // Emitir la acción a la cola de acciones
  // Transicionar a ST_LUZ_APAGADA
  Serial.print("Transición iniciada: Luz apagada\n");
  estado_actual_luz = ST_LUZ_APAGADA;
  emitir_accion_luz(ACC_APAGAR_LUZ, ">> Acción emitida: ACC_APAGAR_LUZ");
  return;
}

// --- Setup ---
void configuracion_sensores_luz()
{
  sensores[IDX_SENSOR_LUZ].pin = FOTORESISTOR;
  sensores[IDX_SENSOR_LUZ].estado = 1; // Esto lo vamos a usar?
  sensores[IDX_SENSOR_LUZ].valor_actual = 0;
  sensores[IDX_SENSOR_LUZ].valor_previo = 0; // Esto lo vamos a usar?
}

void configuracion_estado_inicial_luz()
{
  estado_actual_luz = ST_LUZ_APAGADA;
}

void crear_colas_luz()
{
  queueEventos_luz  = xQueueCreate(TAM_EV_COLA_LUZ,  sizeof(eventos_luz));
  queueAcciones_luz = xQueueCreate(TAM_ACC_COLA_LUZ, sizeof(acciones_luz));
}

void crear_tareas_luz()
{
  int tam_stack_bytes = TAM_STACK_TAREAS;
  xTaskCreate(luz_deteccion, "Luz detección", tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
  xTaskCreate(luz_controlador, "Luz controlador", tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
  xTaskCreate(luz_accion, "Luz accion", tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
}

void setup_luz()
{
  crear_colas_luz();
  configuracion_sensores_luz();
  configuracion_estado_inicial_luz();
  crear_tareas_luz();
}

// --- Tareas ---
void luz_deteccion(void *pvParametros)
{
  while (1)
  {
    // Leer el valor del fotoresistor
    // Comparar con el umbral
    // Emitir evento correspondiente a la cola de eventos
    sensores[IDX_SENSOR_LUZ].valor_actual = analogRead(FOTORESISTOR);

    Serial.print("[luz_deteccion] ADC=");
    Serial.print(sensores[IDX_SENSOR_LUZ].valor_actual);

    eventos_luz evento;
    bool hay_evento = false;

    if (estado_actual_luz == ST_LUZ_APAGADA &&
        sensores[IDX_SENSOR_LUZ].valor_actual > UMBRAL_LUZ)
    {
      evento = EV_NOCHE_DETECTADA;
      hay_evento = true;
    }
    else if (estado_actual_luz == ST_LUZ_ENCENDIDA &&
             sensores[IDX_SENSOR_LUZ].valor_actual <= UMBRAL_LUZ)
    {
      evento = EV_DIA_DETECTADO;
      hay_evento = true;
    }
    if (hay_evento)
    {
      const char* nombre_ev = evento == EV_DIA_DETECTADO ? "EV_DIA_DETECTADO" : "EV_NOCHE_DETECTADA";
      emitir_evento_luz(evento, nombre_ev);
    }
    vTaskDelay(pdMS_TO_TICKS(MILISEGUNDOS_DE_DELAY));
  }
}

void luz_controlador(void *pvParametros)
{
  eventos_luz evento_recibido;
  while (1)
  {
    // Esperar eventos en la cola de eventos
    // Ejecutar la transición correspondiente de la tabla de estados
    TickType_t timeOut = TIME_OUT_CERO; // hace falta ponerle un valor? creo que no porque usamos vTaskDelay(pdMS_TO_TICKS(200));
    if (xQueueReceive(queueEventos_luz, &evento_recibido, timeOut) == pdPASS)
    {
      Serial.print("[luz_controlador] Evento recibido=");
      Serial.print(evento_recibido == EV_NOCHE_DETECTADA ? "EV_NOCHE_DETECTADA" : "EV_DIA_DETECTADO");
      Serial.print(" | estado_previo=");
      Serial.println(estado_actual_luz == ST_LUZ_APAGADA ? "ST_LUZ_APAGADA" : "ST_LUZ_ENCENDIDA");

      if (evento_recibido < CANT_MAX_EVENTOS_LUZ)
      {
        transicion funcion_transicion = luz_tabla_estados[estado_actual_luz][evento_recibido];
        funcion_transicion();
      }
      else
      {
        Serial.println("[luz_controlador] Evento fuera de rango");
      }
    }
    vTaskDelay(pdMS_TO_TICKS(MILISEGUNDOS_DE_DELAY)); // Falta pdMS_TO_TICKS si queremos pasar de segundos a ticks. Para nosotros es un await
  }
}

void luz_accion(void *pvParametros)
{
  while (1)
  {
    acciones_luz action_recibido;
    // Esperar acciones en la cola de acciones
    // Ejecutar la acción correspondiente (encender o apagar el LED)
    TickType_t timeOut = TIME_OUT_CERO; // hace falta ponerle un valor? creo que no porque usamos vTaskDelay(pdMS_TO_TICKS(200));
    if (xQueueReceive(queueAcciones_luz, &action_recibido, timeOut) == pdPASS)
    {
      Serial.print("[luz_accion] Accion recibida=");
      Serial.print(action_recibido == ACC_ENCENDER_LUZ ? "ACC_ENCENDER_LUZ" : "ACC_APAGAR_LUZ");
      if (action_recibido == ACC_ENCENDER_LUZ)
      {
        digitalWrite(LED, HIGH);
      }
      else if (action_recibido == ACC_APAGAR_LUZ)
      {
        digitalWrite(LED, LOW);
      }
      else
      {
        Serial.println("[luz_accion] Accion fuera de rango");
      }
    }
    vTaskDelay(pdMS_TO_TICKS(MILISEGUNDOS_DE_DELAY));
  }
}


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
  EV_TIMEOUT
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
  ACC_DESBLOQUEAR
};

QueueHandle_t queueEventos_puerta;
QueueHandle_t queueAcciones_puerta;

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
void timer_callback_puerta(TimerHandle_t xTimer);
void buzzer_beep(int freq_hz, int duration_ms);
void leer_sensor_proximidad();
bool sensor_proximidad_detectar_animal();
void leer_sensor_rfid();
bool sensor_rfid_detectar_animal();
void detectar_animales_en_puerta();
char leer_serial_puerta();
void configuracion_sensores_puerta();
void configuracion_estado_inicial_puerta();
void puerta_deteccion(void *pvParametros);
void puerta_controlador(void *pvParametros);
void puerta_accion(void *pvParametros);
void crear_colas_puerta();
void crear_tareas_puerta();
void setup_puerta();
void emitir_accion_puerta(acciones_puerta action, const char* nombre);
void emitir_evento_puerta(eventos_puerta evento, const char* caller);

// --- Tabla de estados ---
transicion puerta_tabla_estados[CANT_MAX_ESTADOS_PUERTA][CANT_MAX_EVENTOS_PUERTA] =
{
    {  init_no_bloqueada,    init_bloqueada,    none,                  none,               none,                         none,                      none            }, // state ST_ARRANQUE
    {  none,                 none,              none,                  bloquear_puerta,    abrir_desde_adentro,          abrir_desde_afuera,        none            }, // state ST_CERRADA_NO_BLOQUEADA
    {  none,                 none,              desbloquear_puerta,    none,               none,                         none,                      none            }, // state ST_CERRADA_BLOQUEADA
    {  none,                 none,              none,                  none,               none,                         none,                      cerrar_puerta   }, // state ST_ABIERTA_DESDE_AFUERA
    {  none,                 none,              none,                  none,               none,                         none,                      cerrar_puerta   }  // state ST_ABIERTA_DESDE_ADENTRO
    // EV_INIT_NO_BLOQUEADA, EV_INIT_BLOQUEADA, EV_DESBLOQUEO_POR_APP, EV_BLOQUEO_POR_APP, EV_ANIMAL_DETECTADO_ADENTRO, EV_ANIMAL_DETECTADO_AFUERA, EV_TIMEOUT
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
    digitalWrite(BUZZER, HIGH);
    delayMicroseconds(medio_periodo_us);
    digitalWrite(BUZZER, LOW);
    delayMicroseconds(medio_periodo_us);
  }
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
      sensor_proximidad.estado == ESTADO_HABILITADO &&
      estado_actual_puerta == ST_CERRADA_NO_BLOQUEADA)
  {
    Serial.println("[sensor_proximidad_detectar_animal] Animal detectado desde adentro");
    return true;
  }
  else
  {
    Serial.println("[sensor_proximidad_detectar_animal] Animal no detectado desde adentro");
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
    Serial.println("[sensor_rfid_detectar_animal] Animal no detectado desde afuera");
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
  sensor_rfid.pin_ss    = RFID_SS;
  sensor_rfid.pin_reset = RFID_RST;
  sensor_rfid.estado    = ESTADO_HABILITADO;
  sensor_rfid.id_tag    = 0;

  SPI.begin(RFID_SCK, RFID_MISO, RFID_MOSI, RFID_SS);
  rfid.PCD_Init();
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
    int btn_actual = digitalRead(BUTTON_APP);
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
      }
      else if (action_recibido == ACC_ABRIR_DESDE_ADENTRO)
      {
        Serial.println("ACC_ABRIR_DESDE_ADENTRO 180 grados ACA");
        servo.write(180);
        xTimerStart(timer_puerta, 0);
      }
      else if (action_recibido == ACC_CERRAR)
      {
        Serial.println("ACC_CERRAR");
        servo.write(90);
        sensor_proximidad.estado = ESTADO_HABILITADO;
        sensor_rfid.estado       = ESTADO_HABILITADO;
      }
      else if (action_recibido == ACC_BLOQUEAR)
      {
        Serial.println("ACC_BLOQUEAR");
        // Sonido descendente grave (600 -> 300 Hz): "se cierra con llave"
        buzzer_beep(600, 120);
        buzzer_beep(300, 200);
      }
      else if (action_recibido == ACC_DESBLOQUEAR)
      {
        Serial.println("ACC_DESBLOQUEAR");
        // Sonido ascendente agudo (600 -> 1200 Hz): "se abre con llave"
        buzzer_beep(600, 120);
        buzzer_beep(1200, 200);
      }
      else
      {
        Serial.println("[puerta_accion] Accion fuera de rango");
      }
    }
    vTaskDelay(pdMS_TO_TICKS(200));
  }
}

// --- Setup ---
void crear_colas_puerta()
{
  queueEventos_puerta  = xQueueCreate(TAM_EV_COLA_PUERTA,  sizeof(eventos_puerta));
  queueAcciones_puerta = xQueueCreate(TAM_ACC_COLA_PUERTA, sizeof(acciones_puerta));
}

void crear_tareas_puerta()
{
  int tam_stack_bytes = TAM_STACK_TAREAS;
  xTaskCreate(puerta_deteccion,   "Puerta detección",   tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
  xTaskCreate(puerta_controlador, "Puerta controlador", tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
  xTaskCreate(puerta_accion,      "Puerta accion",      tam_stack_bytes, NULL, PRECEDENCIA_POR_DEFECTO, NULL);
}

void setup_puerta()
{
  crear_colas_puerta();
  configuracion_sensores_puerta();
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
  Serial.begin(PUERTO_SERIAL_WOKWY); // default de wokwi?
}

void configuracion_pines_esp32()
{
  pinMode(LED, OUTPUT);
  pinMode(FOTORESISTOR, INPUT);
  pinMode(BUZZER, OUTPUT);
  servo.attach(SERVO);
  pinMode(SENSOR_PROXIMIDAD_ECHO, INPUT);
  pinMode(SENSOR_PROXIMIDAD_TRIGGER, OUTPUT);
  pinMode(BUTTON_APP, INPUT);
}

void setup()
{
  configuracion_debbug_esp32();
  configuracion_pines_esp32();
  setup_luz();
  setup_puerta();
}

void loop()
{
}
