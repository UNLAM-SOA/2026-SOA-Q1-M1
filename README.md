# 2026 SOA Q1 — Equipo M1 · PawGate

Repositorio del equipo M1 para la cursada de **Sistemas Operativos Avanzados** (SOA — UNLaM).

El repositorio sigue la estructura establecida por la cátedra. A continuación se especifica el contenido de cada directorio:

- **Android/** — Código fuente del proyecto desarrollado en Android Studio (Java + XML, Material 3).
- **Embebido/** — Código fuente del firmware del embebido (ESP32 + PlatformIO + Wokwi simulator).
- **Informes/** — Informes, diagramas, archivos de diseño (Pencil) y material complementario (imágenes, videos, etc.).

---

## Proyecto: PawGate — Puerta inteligente para mascotas

Sistema IoT que permite controlar de forma remota una puerta automática para mascotas. Combina:

- **App Android nativa** (Java + XML) con Material 3, RecyclerView, SharedPreferences y state machine derivado de timestamps.
- **Firmware ESP32** (PlatformIO + Wokwi) con conexión WiFi + MQTT-TLS contra AWS IoT Core.
- **Backend AWS serverless** — IoT Core (broker MQTT), Lambda (lógica), DynamoDB (eventos/horarios), API Gateway + Cognito (auth), SNS → FCM (notificaciones push).
- **Simulador del dispositivo en Python** para desarrollo y demos sin hardware.

### Pantallas implementadas

Splash · Login · Registro · Dashboard · Control de puerta (7 estados: IDLE/OPENING/OPEN/CLOSING/BLOCKED/CALLING/CALL_ENDING) · Historial · Notificaciones · Horarios · Ajustes · Form Nuevo/Editar horario · Filtros historial · Detalle Red/ESP32/Usuario.

### Equipo M1

- Federico Martucci
- Agustín Brocani
- Juan Esteban

### Cátedra

SO Avanzados — Universidad Nacional de La Matanza (UNLaM) — 2026 Q1.
