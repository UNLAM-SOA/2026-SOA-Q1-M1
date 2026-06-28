#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Verificación runnable de la FSM ÚNICA de la puerta (src/main.cpp).

Modela en Python, fielmente, el MISMO diseño que está en main.cpp:
 - FSM dirigida por DATOS: tabla [estado][evento] de celdas (siguiente, accion).
 - Luz por flanco (solo emite al cambiar día<->noche).
 - Bloqueo/desbloqueo por serial/MQTT en cualquier estado.
 - Flag único de detección (se apaga al abrir, se rehabilita al cerrar).
 - Arranque vía el evento INIT.

No ejecuta el firmware: valida el contrato de comportamiento. La compilación la
verifica `pio run`; el runtime real sobre hardware simulado se prueba en Wokwi.
"""

# ----------------------------- ENUMS (idénticos a main.cpp) -----------------------------
ARRANQUE, CERRADA_LIBRE, CERRADA_BLOQUEADA, ABIERTA_AFUERA, ABIERTA_ADENTRO = range(5)
EST_NOMBRE = ["ARRANQUE", "CERRADA_LIBRE", "CERRADA_BLOQUEADA", "ABIERTA_AFUERA", "ABIERTA_ADENTRO"]
SIN_CAMBIO = 5  # centinela "permanece en el estado actual" (== Estado::CANT)

(INIT_LIBRE, INIT_BLOQUEADA, DESBLOQUEAR, BLOQUEAR,
 ANIMAL_ADENTRO, ANIMAL_AFUERA, TIMEOUT, DIA, NOCHE) = range(9)

(A_NINGUNA, A_ABRIR_AFUERA, A_ABRIR_ADENTRO, A_CERRAR,
 A_BLOQUEAR, A_DESBLOQUEAR, A_ENCENDER_LUZ, A_APAGAR_LUZ) = range(8)

UMBRAL_LUZ = 2048
DIST_MIN_CM = 30.0
DIST_BASE_CM = 5.0

X = (SIN_CAMBIO, A_NINGUNA)  # ignorar evento

# Tabla [estado][evento] = (siguiente, accion) — idéntica a TABLA[] en main.cpp
TABLA = [
    # ARRANQUE
    [(CERRADA_LIBRE, A_NINGUNA), (CERRADA_BLOQUEADA, A_NINGUNA), X, X, X, X, X, X, X],
    # CERRADA_LIBRE
    [X, X, X, (CERRADA_BLOQUEADA, A_BLOQUEAR), (ABIERTA_ADENTRO, A_ABRIR_ADENTRO),
     (ABIERTA_AFUERA, A_ABRIR_AFUERA), X, (SIN_CAMBIO, A_APAGAR_LUZ), (SIN_CAMBIO, A_ENCENDER_LUZ)],
    # CERRADA_BLOQUEADA
    [X, X, (CERRADA_LIBRE, A_DESBLOQUEAR), X, X, X, X, (SIN_CAMBIO, A_APAGAR_LUZ), (SIN_CAMBIO, A_ENCENDER_LUZ)],
    # ABIERTA_AFUERA
    [X, X, X, X, X, X, (CERRADA_LIBRE, A_CERRAR), X, X],
    # ABIERTA_ADENTRO
    [X, X, X, X, X, X, (CERRADA_LIBRE, A_CERRAR), X, X],
]


class Sistema:
    def __init__(self):
        self.estado = ARRANQUE
        self.cola_eventos = []
        self.servo = 90
        self.led = "LOW"
        self.buzzer = []
        self.mqtt_pub = []
        self.timer_activo = False
        self.deteccion = True
        self.es_dia_previo = True  # asume día => si arranca de noche, enciende

    # --- controlador: tabla dirigida por datos ---
    def _aplicar(self, ev):
        siguiente, accion = TABLA[self.estado][ev]
        if siguiente != SIN_CAMBIO:
            self.estado = siguiente
        if accion != A_NINGUNA:
            self._ejecutar(accion)

    def _ejecutar(self, a):
        if a == A_ABRIR_AFUERA:
            self.servo = 0; self.timer_activo = True; self.mqtt_pub.append("PUERTA ABIERTA AFUERA")
        elif a == A_ABRIR_ADENTRO:
            self.servo = 180; self.timer_activo = True; self.mqtt_pub.append("PUERTA ABIERTA ADENTRO")
        elif a == A_CERRAR:
            self.servo = 90; self.deteccion = True; self.mqtt_pub.append("PUERTA CERRADA")
        elif a == A_BLOQUEAR:
            self.buzzer += [(600, 120), (300, 200)]
        elif a == A_DESBLOQUEAR:
            self.buzzer += [(600, 120), (1200, 200)]
        elif a == A_ENCENDER_LUZ:
            self.led = "HIGH"
        elif a == A_APAGAR_LUZ:
            self.led = "LOW"

    def tick(self):
        while self.cola_eventos:
            self._aplicar(self.cola_eventos.pop(0))

    # --- detección (mismos guards que main.cpp) ---
    def cerrado(self):
        return self.estado in (CERRADA_LIBRE, CERRADA_BLOQUEADA)

    def detectar_proximidad(self, cm):
        if self.deteccion and self.estado == CERRADA_LIBRE and cm > DIST_BASE_CM and cm < DIST_MIN_CM:
            self.deteccion = False
            self.cola_eventos.append(ANIMAL_ADENTRO)

    def detectar_rfid(self, tarjeta):
        if tarjeta and self.deteccion and self.estado == CERRADA_LIBRE:
            self.deteccion = False
            self.cola_eventos.append(ANIMAL_AFUERA)

    def detectar_luz(self, adc):
        es_dia = adc > UMBRAL_LUZ
        if self.cerrado() and es_dia != self.es_dia_previo:  # flanco
            self.es_dia_previo = es_dia
            self.cola_eventos.append(DIA if es_dia else NOCHE)

    def timeout(self):
        if self.timer_activo:
            self.timer_activo = False
            self.cola_eventos.append(TIMEOUT)

    def comando(self, c):  # serial o MQTT, en cualquier estado
        if c == 'B': self.cola_eventos.append(BLOQUEAR)
        elif c == 'D': self.cola_eventos.append(DESBLOQUEAR)


# ----------------------------- HARNESS -----------------------------
PASS, FAIL = 0, 0
def check(nombre, cond, detalle=""):
    global PASS, FAIL
    if cond: PASS += 1; print(f"  PASS  {nombre}")
    else: FAIL += 1; print(f"  FAIL  {nombre}   {detalle}")

def nuevo(init=INIT_LIBRE):
    s = Sistema(); s.cola_eventos.append(init); s.tick(); return s


print("=" * 70)
print("VERIFICACIÓN FSM ÚNICA dirigida por datos (modelo fiel a src/main.cpp)")
print("=" * 70)

print("\n[1] Arranque vía evento INIT")
check("INIT_LIBRE -> CERRADA_LIBRE", nuevo(INIT_LIBRE).estado == CERRADA_LIBRE)
check("INIT_BLOQUEADA -> CERRADA_BLOQUEADA", nuevo(INIT_BLOQUEADA).estado == CERRADA_BLOQUEADA)

print("\n[2] Luz por flanco (día/noche eventos, LED acción)")
s = nuevo()
s.detectar_luz(100); s.tick()
check("noche -> LED HIGH", s.led == "HIGH")
check("noche no cambia el estado de la puerta", s.estado == CERRADA_LIBRE)
s.detectar_luz(100); s.tick()  # sigue de noche: NO debe re-emitir
check("noche repetida no encola (flanco)", len(s.mqtt_pub) == 0 and s.led == "HIGH")
s.detectar_luz(3000); s.tick()
check("día -> LED LOW", s.led == "LOW")
s = nuevo(INIT_BLOQUEADA)
s.detectar_luz(100); s.tick()
check("luz funciona en CERRADA_BLOQUEADA", s.led == "HIGH" and s.estado == CERRADA_BLOQUEADA)

print("\n[3] Luz ignorada con la puerta abierta")
s = nuevo(); s.detectar_rfid(True); s.tick()
led0 = s.led
s.cola_eventos += [DIA, NOCHE]; s.tick()
check("abierta: DIA/NOCHE no cambian el LED", s.led == led0)
check("abierta: estado se mantiene", s.estado == ABIERTA_AFUERA)

print("\n[4] Proximidad: animal adentro")
s = nuevo(); s.detectar_proximidad(15); s.tick()
check("abre adentro (servo=180)", s.estado == ABIERTA_ADENTRO and s.servo == 180)
check("publica 'PUERTA ABIERTA ADENTRO'", "PUERTA ABIERTA ADENTRO" in s.mqtt_pub)
check("detección deshabilitada + timer activo", (not s.deteccion) and s.timer_activo)
s2 = nuevo(); s2.detectar_proximidad(2); s2.tick()
check("distancia 2cm (<base) no dispara", s2.estado == CERRADA_LIBRE)
s3 = nuevo(); s3.detectar_proximidad(50); s3.tick()
check("distancia 50cm (>min) no dispara", s3.estado == CERRADA_LIBRE)

print("\n[5] Timeout cierra")
s.timeout(); s.tick()
check("timeout -> CERRADA_LIBRE (servo=90)", s.estado == CERRADA_LIBRE and s.servo == 90)
check("publica 'PUERTA CERRADA'", "PUERTA CERRADA" in s.mqtt_pub)
check("detección rehabilitada al cerrar", s.deteccion)

print("\n[6] RFID: animal afuera")
s = nuevo(); s.detectar_rfid(True); s.tick()
check("abre afuera (servo=0)", s.estado == ABIERTA_AFUERA and s.servo == 0)
check("publica 'PUERTA ABIERTA AFUERA'", "PUERTA ABIERTA AFUERA" in s.mqtt_pub)

print("\n[7] Bloqueo/desbloqueo (serial o MQTT) + buzzer")
s = nuevo(); s.comando('B'); s.tick()
check("B -> CERRADA_BLOQUEADA", s.estado == CERRADA_BLOQUEADA)
check("buzzer descendente 600/300", s.buzzer[-2:] == [(600, 120), (300, 200)])
s.comando('D'); s.tick()
check("D -> CERRADA_LIBRE", s.estado == CERRADA_LIBRE)
check("buzzer ascendente 600/1200", s.buzzer[-2:] == [(600, 120), (1200, 200)])

print("\n[8] BUGFIX: desbloqueo por serial estando bloqueada")
s = nuevo(INIT_BLOQUEADA)
s.comando('D'); s.tick()  # antes el serial no se leía en estado bloqueado
check("estando bloqueada, 'D' desbloquea", s.estado == CERRADA_LIBRE)

print("\n[9] Bloqueada NO abre por animales")
s = nuevo(INIT_BLOQUEADA)
s.cola_eventos += [ANIMAL_ADENTRO, ANIMAL_AFUERA]; s.tick()
check("eventos de animal no abren (tabla)", s.estado == CERRADA_BLOQUEADA)
s = nuevo(INIT_BLOQUEADA)
s.detectar_proximidad(15); s.detectar_rfid(True); s.tick()
check("guards de sensor no emiten estando bloqueada", s.estado == CERRADA_BLOQUEADA and s.servo == 90)

print("\n[10] Abierta ignora nuevos animales hasta cerrar")
s = nuevo(); s.detectar_proximidad(15); s.tick()
s.detectar_rfid(True)
check("detección off: rfid no encola", len(s.cola_eventos) == 0 and s.estado == ABIERTA_ADENTRO)

print("\n[11] Eventos no aplicables = ignorados")
s = nuevo(); e0 = s.estado
s.cola_eventos += [DESBLOQUEAR, TIMEOUT, INIT_LIBRE]; s.tick()
check("LIBRE + (DESBLOQUEAR/TIMEOUT/INIT) no cambia estado", s.estado == e0)

print("\n[12] Estructura de la tabla dirigida por datos")
check("5 estados", len(TABLA) == 5)
check("9 eventos por estado", all(len(f) == 9 for f in TABLA))
activas = sum(1 for f in TABLA for c in f if c != X)
check("12 celdas activas (resto = ignorar)", activas == 12, f"activas={activas}")

print("\n" + "=" * 70)
print(f"RESULTADO: {PASS} PASS / {FAIL} FAIL")
print("=" * 70)
import sys
sys.exit(1 if FAIL else 0)
