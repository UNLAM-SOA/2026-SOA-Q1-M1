# PawGate · Device Simulator

Simulador del firmware del ESP32 para desarrollar el flujo end-to-end **sin necesidad del hardware**. Habla MQTT contra AWS IoT Core con mTLS exactamente igual que lo va a hacer el ESP32 real.

## Por qué existe

- Permite que el equipo de Android pruebe el flujo completo (Login → comandos → estados) sin esperar al firmware.
- Permite reproducir edge cases difíciles de generar en hardware (latencia alta, desconexión, BLOQUEADO repetido).
- En la defensa del parcial, podemos demoarlo aunque el ESP32 no esté conectado.

## Prerequisitos

1. **Python 3.10+**
2. **Archivos secrets/pawgate-001/** del repo (pediles a Fede por canal privado, NO están en git):
   - `certificate.pem.crt` — cert X.509 del device
   - `private.pem.key` — clave privada del device
   - `AmazonRootCA1.pem` — CA de Amazon
   - `config.json` — endpoint + thing name + paths

## Setup

```bash
cd Embebido/simulator
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Correrlo

Desde la raíz del repo:

```bash
python Embebido/simulator/device_simulator.py
```

O con un config custom (por ej. para un segundo device):

```bash
python Embebido/simulator/device_simulator.py --config secrets/pawgate-002/config.json
```

Salida esperada:

```
20:30:01 [INFO] Conectando a a20b7a26ajng1l-ats.iot.us-east-1.amazonaws.com:8883 como pawgate-001 ...
20:30:02 [INFO] Conectado a IoT Core. Suscribiendo a comandos...
20:30:02 [INFO] ⬆ PUB pawgate/pawgate-001/status -> {"state": "idle", "ts": 1717273802000}
```

## Testearlo desde la consola MQTT de AWS

1. AWS Console → IoT Core → **MQTT test client**.
2. **Subscribe** a `pawgate/pawgate-001/#` (todo lo que el device publica).
3. **Publish** a `pawgate/pawgate-001/cmd/open` con payload `{"source":"test"}`.
4. En la pestaña de Subscribe, vas a ver la secuencia:
   ```
   pawgate/pawgate-001/status         {"state": "opening", ...}
   pawgate/pawgate-001/status         {"state": "open", ...}      (después de 2s)
   pawgate/pawgate-001/events/door    {"type": "opened", ...}
   pawgate/pawgate-001/status         {"state": "closing", ...}   (después de 5s más)
   pawgate/pawgate-001/status         {"state": "idle", ...}      (después de 2s más)
   pawgate/pawgate-001/events/door    {"type": "closed", ...}
   ```

Probá también `cmd/block` (estado BLOCKED sticky), `cmd/unblock`, `cmd/call` (3s + 1s call_ending), y `cmd/cancel`.

## Topics que maneja

Inputs (subscribe):
```
pawgate/<thing>/cmd/open
pawgate/<thing>/cmd/block
pawgate/<thing>/cmd/unblock
pawgate/<thing>/cmd/call
pawgate/<thing>/cmd/cancel
```

Outputs (publish):
```
pawgate/<thing>/status            estado actual (retain=true para late-joiners)
pawgate/<thing>/events/door       transiciones de puerta (opened/closed/blocked/...)
pawgate/<thing>/events/sensor     lecturas del sensor de ultrasonido (mock)
pawgate/<thing>/telemetry         stats periodicos cada 60s
```

## Arquitectura

```
┌──────────────────┐         mTLS:8883          ┌─────────────────┐
│  device_         │  ─── pawgate/+/cmd/+  ──→  │   AWS IoT Core  │
│  simulator.py    │  ←── pawgate/+/status ───  │   (Broker MQTT) │
└──────────────────┘                            └─────────────────┘
                                                          │
                                                  IoT Rules + Lambda
                                                          │
                                                          ▼
                                                ┌─────────────────┐
                                                │   DynamoDB      │
                                                │  (events, etc.) │
                                                └─────────────────┘
```

Cuando llegue Fase 14 (firmware real), reemplazamos `device_simulator.py` por el binario flasheado en el ESP32 y **el flujo no cambia** — mismos topics, mismo state machine.

## State machine implementado

```
       cmd/open       2s        5s         2s
IDLE ─────────→ OPENING ──→ OPEN ──→ CLOSING ──→ IDLE
                                       │
                                       cmd/cancel
                                       │
                                       ▼
                                      IDLE

       cmd/call       3s            1s
IDLE ─────────→ CALLING ──→ CALL_ENDING ──→ IDLE

       cmd/block                cmd/unblock
* ─────────────────→ BLOCKED ─────────────────→ IDLE
```

(Mismo que el `DoorStateMachine.java` del Android — la app y el device están sincronizados por contrato.)
