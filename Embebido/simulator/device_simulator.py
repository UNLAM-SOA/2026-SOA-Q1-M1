"""
PawGate Device Simulator
========================

Se hace pasar por un ESP32 real a nivel MQTT contra AWS IoT Core. Implementa
exactamente el mismo state machine que vamos a flashear en el ESP32 + responde
a los mismos comandos sobre los mismos topics.

Permite desarrollar y testear el flujo end-to-end (Android <-> IoT Core <-> Device)
sin necesidad de tener el hardware fisico conectado.

Conexion: mTLS contra AWS IoT Core en el puerto 8883
  - Root CA -> AmazonRootCA1.pem
  - Client cert + private key -> los que descargaste al crear el "Thing" en IoT Core

Topics (pawgate/<thing_name>/...):
  Inputs (subscribe):
    cmd/open        Abre la puerta (gatilla ciclo OPENING -> OPEN -> CLOSING -> IDLE)
    cmd/block       Pone la puerta en modo seguridad (estado BLOCKED, persistente)
    cmd/unblock     Saca del modo seguridad (vuelve a IDLE)
    cmd/call        Activa el buzzer (CALLING -> CALL_ENDING -> IDLE)
    cmd/cancel      Cancela el ciclo activo (vuelve a IDLE)

  Outputs (publish):
    status                 Estado actual cada vez que cambia (retain=False)
    events/door            Evento de puerta (opened, closed, blocked, unblocked)
    events/sensor          Lectura del sensor de ultrasonido (mock)
    telemetry              Stats periodicos cada 60s (rssi, uptime, mem)

Estados (mismos que en Android DoorStateMachine):
  IDLE -> OPENING(2s) -> OPEN(5s) -> CLOSING(2s) -> IDLE
  IDLE -> CALLING(3s) -> CALL_ENDING(1s) -> IDLE
  IDLE/cualquiera -> BLOCKED (sticky hasta cmd/unblock)

Uso:
  pip install -r requirements.txt
  python device_simulator.py [--config PATH]
"""

import argparse
import json
import logging
import os
import random
import ssl
import sys
import threading
import time
from enum import Enum
from pathlib import Path

import paho.mqtt.client as mqtt


# ============================================================
# CONFIGURACION
# ============================================================

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = REPO_ROOT / "secrets" / "pawgate-001" / "config.json"


class DoorState(Enum):
    IDLE = "idle"
    OPENING = "opening"
    OPEN = "open"
    CLOSING = "closing"
    BLOCKED = "blocked"
    CALLING = "calling"
    CALL_ENDING = "call_ending"


# Duraciones del state machine (segundos). Mismo numero que el Android.
OPENING_DURATION_S = 2.0
OPEN_DURATION_S = 5.0
CLOSING_DURATION_S = 2.0
CALLING_DURATION_S = 3.0
CALL_ENDING_DURATION_S = 1.0

TELEMETRY_INTERVAL_S = 60.0
SENSOR_EMIT_INTERVAL_S = 5.0


# ============================================================
# LOGGING
# ============================================================

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("pawgate-sim")


# ============================================================
# DEVICE SIMULATOR
# ============================================================

class DeviceSimulator:
    """
    Simulador del firmware del ESP32. Maneja:
      - Conexion mTLS al broker MQTT de AWS IoT Core
      - Subscripcion a topics de comandos
      - State machine de la puerta
      - Publicacion de status, eventos y telemetria
    """

    def __init__(self, config: dict):
        self.config = config
        self.thing_name = config["thing_name"]
        self.topic_prefix = f"pawgate/{self.thing_name}"

        # Estado interno
        self.state = DoorState.IDLE
        self.boot_time = time.time()
        self._state_lock = threading.RLock()
        self._pending_timers: list[threading.Timer] = []

        # MQTT client. Usamos CallbackAPIVersion.VERSION2 (paho-mqtt 2.x) para
        # evitar el DeprecationWarning del API antiguo. Las firmas de los
        # callbacks cambian: ahora reciben `reason_code` + `properties`.
        # client_id = thing_name para que matche con ${iot:ClientId} de la policy.
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=self.thing_name,
            clean_session=True,
        )
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        self.client.on_disconnect = self._on_disconnect
        self.client.on_subscribe = self._on_subscribe
        # Activamos el logger interno de paho para debuggear handshakes y CONNACKs.
        self.client.enable_logger(logging.getLogger("paho"))

        # Configurar TLS (mTLS: server cert + client cert)
        self.client.tls_set(
            ca_certs=config["root_ca_path"],
            certfile=config["certificate_path"],
            keyfile=config["private_key_path"],
            cert_reqs=ssl.CERT_REQUIRED,
            tls_version=ssl.PROTOCOL_TLSv1_2,
        )

    # ============================================================
    # CONEXION
    # ============================================================

    def connect_forever(self) -> None:
        endpoint = self.config["iot_endpoint"]
        port = self.config["iot_port"]
        log.info("Conectando a %s:%d como %s ...", endpoint, port, self.thing_name)
        self.client.connect(endpoint, port, keepalive=60)
        self.client.loop_forever(retry_first_connection=True)

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        # Firma de CallbackAPIVersion.VERSION2.
        if reason_code != 0:
            log.error(
                "Fallo conexion MQTT (reason_code=%s). Revisa cert/policy/endpoint.",
                reason_code,
            )
            return
        log.info("✅ Conectado a IoT Core. Suscribiendo a comandos...")
        # Suscribirse a todos los comandos del device
        subscribe_topic = f"{self.topic_prefix}/cmd/+"
        result, mid = client.subscribe(subscribe_topic, qos=1)
        if result == mqtt.MQTT_ERR_SUCCESS:
            log.info("📨 SUB request enviado a %s (mid=%d) - esperando SUBACK...", subscribe_topic, mid)
        else:
            log.error("Fallo el subscribe a %s (rc=%d)", subscribe_topic, result)
        # Publicar estado inicial (IDLE) para que la app sepa que estamos vivos
        self._publish_status()
        # Arrancar los loops periodicos
        threading.Thread(target=self._telemetry_loop, daemon=True).start()
        threading.Thread(target=self._sensor_loop, daemon=True).start()

    def _on_subscribe(self, client, userdata, mid, reason_code_list, properties):
        # Callback de CallbackAPIVersion.VERSION2 cuando AWS responde el SUBACK.
        # reason_code_list es una lista (una entrada por cada topic filter del request).
        for rc in reason_code_list:
            if hasattr(rc, "is_failure") and rc.is_failure:
                log.error("❌ SUBSCRIBE rechazado por broker: %s (mid=%d)", rc, mid)
            else:
                log.info("✅ SUBSCRIBE confirmado por broker: %s (mid=%d)", rc, mid)

    def _on_disconnect(self, client, userdata, flags, reason_code, properties):
        # Firma de CallbackAPIVersion.VERSION2.
        if reason_code != 0:
            log.warning(
                "Desconectado inesperadamente (reason_code=%s). Reintentando...",
                reason_code,
            )
        else:
            log.info("Desconectado limpiamente.")

    # ============================================================
    # COMMAND HANDLERS (callback de MQTT)
    # ============================================================

    def _on_message(self, client, userdata, msg):
        topic = msg.topic
        try:
            payload = json.loads(msg.payload.decode("utf-8")) if msg.payload else {}
        except json.JSONDecodeError:
            payload = {"raw": msg.payload.decode("utf-8", errors="replace")}

        log.info("⬇ CMD %s -> %s", topic, payload)
        cmd = topic.split("/")[-1]

        if cmd == "open":
            self._handle_open()
        elif cmd == "block":
            self._handle_block()
        elif cmd == "unblock":
            self._handle_unblock()
        elif cmd == "call":
            self._handle_call()
        elif cmd == "cancel":
            self._handle_cancel()
        else:
            log.warning("Comando desconocido: %s", cmd)

    def _handle_open(self):
        with self._state_lock:
            if self.state in (DoorState.BLOCKED, DoorState.OPENING, DoorState.OPEN, DoorState.CLOSING):
                log.info("Open ignorado (estado actual: %s)", self.state.value)
                return
            self._cancel_pending_timers()
            self._transition_to(DoorState.OPENING)
            # Cadena: OPENING -> OPEN -> CLOSING -> IDLE
            self._schedule(OPENING_DURATION_S, self._transition_to, DoorState.OPEN)
            self._schedule(OPENING_DURATION_S + OPEN_DURATION_S, self._transition_to, DoorState.CLOSING)
            self._schedule(OPENING_DURATION_S + OPEN_DURATION_S + CLOSING_DURATION_S,
                           self._transition_to, DoorState.IDLE)

    def _handle_block(self):
        with self._state_lock:
            if self.state == DoorState.BLOCKED:
                return
            self._cancel_pending_timers()
            self._transition_to(DoorState.BLOCKED)
            self._publish_event("door", {"type": "blocked", "reason": "user_command"})

    def _handle_unblock(self):
        with self._state_lock:
            if self.state != DoorState.BLOCKED:
                return
            self._transition_to(DoorState.IDLE)
            self._publish_event("door", {"type": "unblocked"})

    def _handle_call(self):
        with self._state_lock:
            if self.state in (DoorState.CALLING, DoorState.CALL_ENDING, DoorState.BLOCKED):
                return
            self._cancel_pending_timers()
            self._transition_to(DoorState.CALLING)
            self._schedule(CALLING_DURATION_S, self._transition_to, DoorState.CALL_ENDING)
            self._schedule(CALLING_DURATION_S + CALL_ENDING_DURATION_S,
                           self._transition_to, DoorState.IDLE)

    def _handle_cancel(self):
        with self._state_lock:
            if self.state == DoorState.IDLE or self.state == DoorState.BLOCKED:
                return
            self._cancel_pending_timers()
            self._transition_to(DoorState.IDLE)
            self._publish_event("door", {"type": "cancelled"})

    # ============================================================
    # STATE MACHINE
    # ============================================================

    def _transition_to(self, new_state: DoorState):
        with self._state_lock:
            old_state = self.state
            self.state = new_state
            log.info("◆ STATE %s -> %s", old_state.value, new_state.value)
            self._publish_status()
            # Eventos derivados de transiciones
            if old_state == DoorState.OPENING and new_state == DoorState.OPEN:
                self._publish_event("door", {"type": "opened"})
            elif old_state == DoorState.CLOSING and new_state == DoorState.IDLE:
                self._publish_event("door", {"type": "closed"})

    def _schedule(self, delay_s: float, fn, *args):
        """Programa una transicion futura. Se guarda para poder cancelarla."""
        timer = threading.Timer(delay_s, fn, args=args)
        timer.daemon = True
        timer.start()
        self._pending_timers.append(timer)

    def _cancel_pending_timers(self):
        for t in self._pending_timers:
            t.cancel()
        self._pending_timers = []

    # ============================================================
    # PUBLISHERS
    # ============================================================

    def _publish_status(self):
        msg = {
            "state": self.state.value,
            "ts": int(time.time() * 1000),
        }
        self._publish(f"{self.topic_prefix}/status", msg, qos=1, retain=True)

    def _publish_event(self, kind: str, payload: dict):
        full = {**payload, "ts": int(time.time() * 1000)}
        self._publish(f"{self.topic_prefix}/events/{kind}", full, qos=1)

    def _publish(self, topic: str, payload: dict, qos: int = 0, retain: bool = False):
        body = json.dumps(payload)
        result = self.client.publish(topic, body, qos=qos, retain=retain)
        if result.rc != mqtt.MQTT_ERR_SUCCESS:
            log.error("Fallo publish a %s (rc=%d)", topic, result.rc)
        else:
            log.info("⬆ PUB %s -> %s", topic, body)

    # ============================================================
    # LOOPS PERIODICOS (telemetria + sensor)
    # ============================================================

    def _telemetry_loop(self):
        while True:
            time.sleep(TELEMETRY_INTERVAL_S)
            uptime_s = int(time.time() - self.boot_time)
            self._publish(
                f"{self.topic_prefix}/telemetry",
                {
                    "uptime_s": uptime_s,
                    "rssi_dbm": random.randint(-65, -35),
                    "free_heap_kb": random.randint(140, 180),
                    "cpu_temp_c": round(random.uniform(38.0, 48.0), 1),
                    "ts": int(time.time() * 1000),
                },
                qos=0,
            )

    def _sensor_loop(self):
        """Emite lecturas del sensor de ultrasonido cada SENSOR_EMIT_INTERVAL_S segundos."""
        while True:
            time.sleep(SENSOR_EMIT_INTERVAL_S)
            # Si esta abriendo/abierta/cerrando, simulamos que el sensor "ve" algo cerca
            with self._state_lock:
                if self.state in (DoorState.OPENING, DoorState.OPEN, DoorState.CLOSING):
                    distance_cm = random.randint(5, 25)
                else:
                    # En idle/blocked/calling: el sensor no detecta nada cerca
                    distance_cm = random.randint(80, 200)
            self._publish_event("sensor", {
                "distance_cm": distance_cm,
                "freq_hz": 20,
            })


# ============================================================
# MAIN
# ============================================================

def load_config(config_path: Path) -> dict:
    if not config_path.exists():
        log.error("No existe el archivo de config: %s", config_path)
        log.error("Pedile al equipo los archivos PEM + config.json y ponelos en secrets/pawgate-001/")
        sys.exit(1)
    with open(config_path) as f:
        cfg = json.load(f)
    # Resolver paths relativos al repo root
    for k in ("certificate_path", "private_key_path", "root_ca_path"):
        p = Path(cfg[k])
        if not p.is_absolute():
            p = REPO_ROOT / p
        if not p.exists():
            log.error("No existe el archivo %s: %s", k, p)
            sys.exit(1)
        cfg[k] = str(p)
    return cfg


def main():
    parser = argparse.ArgumentParser(description="PawGate device simulator (ESP32 mock).")
    parser.add_argument(
        "--config",
        default=str(DEFAULT_CONFIG),
        help="Path al config.json con endpoint + paths de los PEM (default: secrets/pawgate-001/config.json)",
    )
    args = parser.parse_args()

    cfg = load_config(Path(args.config))
    sim = DeviceSimulator(cfg)
    try:
        sim.connect_forever()
    except KeyboardInterrupt:
        log.info("Cerrando simulador (Ctrl+C).")
        sim.client.disconnect()


if __name__ == "__main__":
    main()
