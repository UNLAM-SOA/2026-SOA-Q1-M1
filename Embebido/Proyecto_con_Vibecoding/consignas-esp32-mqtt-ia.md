# Consignas TP2 — ESP32 + MQTT con IA

> Briefing para Claude Code. Nuestro grupo trabaja la parte de **ESP32 y MQTT con IA** (Comisión Martes en lo referido a métricas).
> Materia: Sistemas Operativos Avanzados — Año 2026, 1° Cuatrimestre.

---

## Contexto general

El objetivo del TP es pasar el circuito **simulado** (del TP1) a un circuito **físico** con ESP32, y comunicarlo con un dispositivo Android. La conectividad entre el sistema embebido (ESP32) y el smartphone se hace por **WiFi**. Adicionalmente, se incorpora una actividad de IA: desarrollar el mismo proyecto de dos formas (manual y con vibecoding) y comparar métricas.

A nosotros nos toca el **lado ESP32**: el firmware, la comunicación por WiFi/MQTT, y las métricas de CPU/memoria en el ESP32 usando la biblioteca `Metrics.h`.

### Fechas de entrega
- **Parte 1 (Actividad 2):** 2 de junio (Comisión martes).
- **Parte 2 (Actividad Integradora):** 30 de junio (Comisión martes).

---

## Estructura del repositorio (parte EMBEBIDO)

El repo de GitHub asignado por la cátedra debe tener, para la parte embebida:

- **EMBEBIDO/** — archivo `.ino` y las bibliotecas usadas en el código del ESP32 físico.
  - **Proyecto_sin_vibecoding/** — código del proyecto desarrollado **de forma manual** (sin vibecoding).
  - **Proyecto_con_vibecoding/** — código del proyecto desarrollado **usando vibecoding**.
- **INFORMES/** — documentos de los informes generados.

> El directorio ANDROID lo maneja la otra parte del equipo; no es nuestra responsabilidad pero conviene coordinar la interfaz de comunicación (comandos WiFi/MQTT y formato de mensajes).

---

## PARTE 1 — Circuito y código del ESP32 físico

### Circuito ESP32 físico
- Pasar el circuito simulado a circuito físico usando la placa de prototipado elegida.
- Respetar la **cantidad y tipo de sensores y actuadores** presentados en el TP1.

### Código del ESP32 físico
Adaptar la máquina de estados (FSM) desarrollada en el TP1 para que:

1. **Reciba un comando por WiFi** → ejecute una acción en el sistema embebido (actuador).
2. **Informe por WiFi el valor de un sensor** al smartphone en un determinado momento.
3. Realizar **pruebas de conectividad** entre Android y ESP32 usando una **app cliente descargada de la Play Store** (cliente genérico para validar antes de tener la app propia).
4. **Implementar la biblioteca `Metrics.h`** para medir CPU y memoria (esto es **obligatorio para Comisión Martes** → nos aplica). Detalle de uso más abajo.

### Forma de entrega (Parte 1)
El circuito físico, junto con la conexión a un cliente Android descargado de la Play Store, debe **mostrarse en clase a los docentes** en la fecha pautada.

> **Node-RED es opcional**, pero suma puntos extra en la nota final si se implementa.

---

## PARTE 2 — TP Integrador (lado ESP32)

En la integración, nuestro firmware debe soportar lo que la app Android va a ejercitar:

1. **Recibir el comando WiFi** (el mismo de la Parte 1) enviado desde el embebido físico y **ejecutar una acción**.
2. **Enviar el valor de un sensor** por WiFi para que la app lo muestre en pantalla.

Es decir: el ESP32 actúa como contraparte del flujo completo comando→acción y sensor→pantalla.

### Forma de entrega (Parte 2)
- Mostrar en el **laboratorio** el circuito físico comunicándose con la app Android, en la fecha pautada.
- Entregar el **informe** en la plataforma Miel, sección práctica.

---

## Uso de la biblioteca `Metrics.h` (Comisión Martes — nos aplica)

**Objetivo:** medir y promediar el uso de **CPU (por core)** y de **memoria (heap)** en el ESP32 usando la biblioteca provista, y documentar resultados en dos situaciones distintas.

### Biblioteca y restricciones
- Repositorio de la biblioteca:
  `https://gitlab.com/so-unlam/Material-SOA/-/tree/master/Ejemplos%20SE/UsoCpuMemESP32`
- **Importante:** funciona solamente en **Wokwi** y en el **IDE de Arduino**. **No funciona en VSCode.**

### Uso en código
- Incluir la biblioteca.
- En `setup()` → llamar a `initStats()` para empezar el muestreo de mediciones de CPU y memoria.
- Cuando se quiera cerrar el período de medición y obtener promedios → llamar a `finishStats()`. Esto imprime en el Monitor Serie el promedio de uso de CPU y de memoria.

### Casos de prueba (dos situaciones)

**A. Reposo del sistema:**
En `loop()`, tras **10 segundos sin interacción** del usuario, llamar a `finishStats()` y registrar la impresión del monitor serie.

**B. Tras una acción específica:**
Repetir el proceso pero **disparando una acción concreta** dentro de la ventana de medición (entre `initStats()` y `finishStats()`).
- Ejemplo para nuestro caso: **recepción de un mensaje por MQTT desde el broker**, o lectura de un sensor.
- La acción debe ocurrir **dentro** de la ventana de medición.

### Entrega de métricas
- Presentar un archivo **Word llamado `metricasCPU`** con la impresión del promedio de uso de CPU y memoria obtenidos con `finishStats()`, tal como se ve en el monitor serial.
- El archivo debe incluir el resultado de **ambas situaciones (A y B)**, para **los dos códigos**: el manual (sin vibecoding) y el hecho con vibecoding total.

---

## Actividad de IA / Vibecoding (lado ESP32)

### Qué es vibecoding
Crear código fuente indicándole a la IA (LLM) en lenguaje natural lo que debe hacer, mediante prompts. Para esta actividad usamos la modalidad **Total**.

- **Vibecoding Total:** la IA genera **completamente** el código. El humano **no escribe código manualmente**, solo le indica a la IA qué corregir. **Esta es la modalidad que hay que usar en el TP.**

### Qué nos toca (Comisión Martes)
- Generar métricas: **solo código ESP32** (la Comisión Lunes hace solo App Android).
- Desarrollar el sistema embebido ESP32 de **dos formas**:
  - **Código manual.**
  - **Código con Vibecoding Total.**

### Cómo hacer el prompt
- Hacer un **prompt técnico** que describa nuestro TP.
- Refinarlo bastante, de a poco (una parte → probar → refinar).
- **No corregir los errores manualmente** → dejar que los corrija la IA.

### Métricas de la actividad de IA (lado ESP32)
Medir y comparar entre código manual y código con vibecoding:
- Uso de CPU y memoria (con la biblioteca de ESP32 / `Metrics.h`).
- **Tiempo de desarrollo** (manual vs vibecoding total).

---

## Informes a entregar

Se entregan **dos informes**:

### 1. Informe de Actividad Integradora (ESP32 + Android)
Formato **paper CACIC**, con bibliografía en **formato IEEE**. Plantilla:
`https://www.dropbox.com/scl/fi/bpp2ypzsuk640fjyo7kql/00_EstructuraPaper_cacic.doc?rlkey=0rpypbimvlzu88i565ijxbq8m&dl=0`

Secciones requeridas:

- **Encabezado:**
  - Nombre de la aplicación como título del paper.
  - Nombre, Apellido y DNI de cada integrante; día de cursada y número de grupo.
  - Resumen del trabajo (máx. 150 palabras).
- **Introducción:** funcionalidad de la aplicación (para qué sirve y qué hace).
- **Desarrollo:**
  - Enlace al repositorio **GitHub** y al de **Wokwi**.
  - Diagrama de **máquina de estados** generado en la Actividad 1.
  - Captura de pantalla del **circuito en Wokwi**.
  - Diagrama funcional / de navegación de las Activities (qué Activity llama a cuál).
  - Manual de usuario (cómo se usa la app y cómo interactúa con el sistema embebido).
- **Bibliografía:** referenciada y estructurada en **formato IEEE**.

### 2. Informe sobre IA
Checklist de puntos necesarios (algunos aplican a nuestra parte ESP32):
- Herramienta de IA utilizada.
- Modelo de IA utilizado.
- Prompt técnico utilizado.
- Cantidad de tokens de IA utilizados.
- Tiempo para obtener una versión funcional (Manual + Vibecoding).
- Métricas de CPU y memoria (Manual + Vibecoding).
- Código en Git (Manual + Vibecoding).
- Rúbrica de funcionalidad.
- Repositorio Git con directorio **Manual** y directorio **Vibecoding**.

> La plantilla del informe de IA está en **Miel**. El repositorio es **GitHub**.
> Ambos informes se entregan junto al integrador.

---

## Resumen de lo que Claude Code debe ayudar a producir (parte ESP32)

1. Firmware `.ino` para ESP32 físico con FSM adaptada: recibe comandos por WiFi/MQTT → acciona; lee sensor → publica por WiFi/MQTT.
2. **Dos versiones** del firmware: una manual y una con vibecoding total, cada una en su directorio del repo.
3. Integración de `Metrics.h` con `initStats()` / `finishStats()` y los dos casos de prueba (reposo 10 s y tras acción MQTT).
4. (Opcional, puntos extra) Integración con Node-RED.