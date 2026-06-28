#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Demostración runnable del MQTT del proyecto, contra el broker REAL broker.hivemq.com.

Como no se puede correr el firmware fuera de Wokwi, este script levanta:
  - "puerta": un emulador de la ESP32 (misma FSM + misma lógica MQTT que main.cpp).
              Se suscribe a soa/puerta/cmd y publica en soa/puerta/evento.
  - "control": el control remoto (lo que harías con MQTT Explorer). Publica B/D y
               escucha los eventos.

Ambos se conectan al broker público real, así que se prueba el círculo completo:
comando -> broker -> FSM -> evento -> broker -> control.
"""
import sys, time, queue, threading
import paho.mqtt.client as mqtt

BROKER, PORT = "broker.hivemq.com", 1883
T_CMD, T_EVT = "soa/puerta/cmd", "soa/puerta/evento"
TIMEOUT_S = 4.5  # cierre automático (igual que TIMEOUT_CIERRE_MS en el firmware)

# --- FSM idéntica a main.cpp (tabla dirigida por datos) ---
ARRANQUE, CERRADA_LIBRE, CERRADA_BLOQUEADA, ABIERTA_AFUERA, ABIERTA_ADENTRO = range(5)
SIN_CAMBIO = 5
INIT_LIBRE, INIT_BLOQUEADA, DESBLOQUEAR, BLOQUEAR, ANIMAL_ADENTRO, ANIMAL_AFUERA, TIMEOUT, DIA, NOCHE = range(9)
A_NINGUNA, A_ABRIR_AFUERA, A_ABRIR_ADENTRO, A_CERRAR, A_BLOQUEAR, A_DESBLOQUEAR, A_ENCENDER, A_APAGAR = range(8)
X = (SIN_CAMBIO, A_NINGUNA)
TABLA = [
    [(CERRADA_LIBRE, A_NINGUNA), (CERRADA_BLOQUEADA, A_NINGUNA), X, X, X, X, X, X, X],
    [X, X, X, (CERRADA_BLOQUEADA, A_BLOQUEAR), (ABIERTA_ADENTRO, A_ABRIR_ADENTRO),
     (ABIERTA_AFUERA, A_ABRIR_AFUERA), X, (SIN_CAMBIO, A_APAGAR), (SIN_CAMBIO, A_ENCENDER)],
    [X, X, (CERRADA_LIBRE, A_DESBLOQUEAR), X, X, X, X, (SIN_CAMBIO, A_APAGAR), (SIN_CAMBIO, A_ENCENDER)],
    [X, X, X, X, X, X, (CERRADA_LIBRE, A_CERRAR), X, X],
    [X, X, X, X, X, X, (CERRADA_LIBRE, A_CERRAR), X, X],
]


class PuertaEmulada:
    """La ESP32: corre la FSM, recibe comandos por MQTT y publica eventos."""
    def __init__(self, publicar):
        self.estado = CERRADA_LIBRE
        self.deteccion = True
        self.cola = queue.Queue()
        self.publicar = publicar
        self.timer = None
        self.lock = threading.Lock()
        threading.Thread(target=self._correr, daemon=True).start()

    def comando(self, c):  # callback MQTT (soa/puerta/cmd)
        if c == 'B': self.cola.put(BLOQUEAR); print("   [puerta] recibí comando B -> evento BLOQUEAR")
        elif c == 'D': self.cola.put(DESBLOQUEAR); print("   [puerta] recibí comando D -> evento DESBLOQUEAR")

    def sensor_animal_adentro(self):  # "sensor" de proximidad (mismo guard que el firmware)
        with self.lock:
            if self.deteccion and self.estado == CERRADA_LIBRE:
                self.deteccion = False
                self.cola.put(ANIMAL_ADENTRO)

    def _arrancar_timer(self):
        if self.timer: self.timer.cancel()
        self.timer = threading.Timer(TIMEOUT_S, lambda: self.cola.put(TIMEOUT))
        self.timer.start()

    def _ejecutar(self, a):
        if a == A_ABRIR_ADENTRO:
            self._arrancar_timer(); self.publicar("PUERTA ABIERTA ADENTRO")
        elif a == A_ABRIR_AFUERA:
            self._arrancar_timer(); self.publicar("PUERTA ABIERTA AFUERA")
        elif a == A_CERRAR:
            with self.lock: self.deteccion = True
            self.publicar("PUERTA CERRADA")
        # BLOQUEAR/DESBLOQUEAR: en el firmware suenan el buzzer; acá no hay audio.

    def _correr(self):
        while True:
            ev = self.cola.get()
            sig, acc = TABLA[self.estado][ev]
            with self.lock:
                if sig != SIN_CAMBIO: self.estado = sig
            if acc != A_NINGUNA: self._ejecutar(acc)


# ----------------------------- conexión MQTT -----------------------------
def nuevo_cliente(nombre):
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"demo-{nombre}")
    return c

conectado = {"puerta": threading.Event(), "control": threading.Event()}
recibidos = []  # eventos que ve el control

# Emulador de la puerta
cli_puerta = nuevo_cliente("puerta")
def on_connect_puerta(c, u, f, rc, p):
    if rc == 0: c.subscribe(T_CMD); conectado["puerta"].set()
def on_msg_puerta(c, u, msg):
    if len(msg.payload): puerta.comando(chr(msg.payload[0]))
cli_puerta.on_connect = on_connect_puerta
cli_puerta.on_message = on_msg_puerta

def publicar_evento(payload):
    print(f"   [puerta] -> publico en {T_EVT}: '{payload}'")
    cli_puerta.publish(T_EVT, payload, qos=0, retain=False)

puerta = PuertaEmulada(publicar_evento)

# Control remoto
cli_control = nuevo_cliente("control")
def on_connect_control(c, u, f, rc, p):
    if rc == 0: c.subscribe(T_EVT); conectado["control"].set()
def on_msg_control(c, u, msg):
    txt = msg.payload.decode(errors="ignore")
    recibidos.append(txt)
    print(f"   [control] <- recibí en {T_EVT}: '{txt}'")
cli_control.on_connect = on_connect_control
cli_control.on_message = on_msg_control

def mandar_comando(c):
    print(f"   [control] -> publico en {T_CMD}: '{c}'")
    cli_control.publish(T_CMD, c)

# ----------------------------- helpers de test -----------------------------
PASS, FAIL = 0, 0
def check(nombre, cond, det=""):
    global PASS, FAIL
    if cond: PASS += 1; print(f"  PASS  {nombre}")
    else: FAIL += 1; print(f"  FAIL  {nombre}  {det}")

def espero(payload, timeout=5):
    fin = time.time() + timeout
    while time.time() < fin:
        if payload in recibidos: return True
        time.sleep(0.1)
    return False

def limpio(): recibidos.clear()

# ----------------------------- run -----------------------------
print("=" * 68)
print("DEMO MQTT contra broker REAL:", BROKER)
print("=" * 68)

for cli in (cli_puerta, cli_control):
    cli.connect(BROKER, PORT, 60)
    cli.loop_start()

ok_p = conectado["puerta"].wait(10)
ok_c = conectado["control"].wait(10)
print(f"\n[0] Conexión al broker")
check("la 'puerta' (ESP32) conecta y se suscribe a cmd", ok_p)
check("el 'control' conecta y se suscribe a evento", ok_c)
if not (ok_p and ok_c):
    print("\nNo se pudo conectar al broker (¿red/firewall bloquea el 1883?).")
    sys.exit(1)
time.sleep(1)

print("\n[1] Apertura por sensor -> evento publicado por MQTT")
limpio()
puerta.sensor_animal_adentro()
check("llega 'PUERTA ABIERTA ADENTRO' por MQTT", espero("PUERTA ABIERTA ADENTRO"))
print("    (esperando cierre automático por timeout ~4.5s...)")
check("llega 'PUERTA CERRADA' tras el timeout", espero("PUERTA CERRADA", timeout=8))

print("\n[2] BLOQUEO por MQTT -> la puerta NO abre")
limpio()
mandar_comando("B")          # viaja por el broker real hasta la FSM
time.sleep(1.5)
puerta.sensor_animal_adentro()
no_abrio = not espero("PUERTA ABIERTA ADENTRO", timeout=3)
check("estando bloqueada, el sensor NO abre la puerta", no_abrio)

print("\n[3] DESBLOQUEO por MQTT -> vuelve a abrir")
limpio()
mandar_comando("D")
time.sleep(1.5)
puerta.sensor_animal_adentro()
check("tras desbloquear por MQTT, abre de nuevo", espero("PUERTA ABIERTA ADENTRO"))

print("\n" + "=" * 68)
print(f"RESULTADO: {PASS} PASS / {FAIL} FAIL  (todo sobre {BROKER})")
print("=" * 68)
for cli in (cli_puerta, cli_control):
    cli.loop_stop(); cli.disconnect()
sys.exit(1 if FAIL else 0)
