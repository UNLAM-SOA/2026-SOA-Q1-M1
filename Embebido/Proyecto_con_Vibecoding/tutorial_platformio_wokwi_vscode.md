# Configuracion de proyecto ESP32 con PlatformIO y Wokwi en VS Code

Este tutorial explica como configurar desde cero un proyecto ESP32 para trabajar en **VS Code** usando:

- **PlatformIO** para compilar el firmware.
- **Wokwi Simulator** para simular el circuito.
- **Arduino framework** como base de programacion.
- Archivos locales `platformio.ini`, `wokwi.toml`, `diagram.json` y `src/main.cpp`.

---

## 1. Extensiones necesarias en VS Code

Instalar estas extensiones desde el Marketplace de VS Code:

1. **PlatformIO IDE**
2. **Wokwi Simulator**
3. Opcional, pero recomendado:
   - C/C++
   - Error Lens
   - GitLens
   - Markdown All in One

---

## 2. Crear el proyecto con PlatformIO

Abrir VS Code y entrar al panel de **PlatformIO**.

Luego seleccionar:

```txt
PIO Home -> New Project
```

Configurar el proyecto asi:

```txt
Name: Sistemas-Operativos-Avanzados
Board: Espressif ESP32 Dev Module
Framework: Arduino
```

El board correcto para el ESP32 clasico de Wokwi es:

```txt
Espressif ESP32 Dev Module
```

En `platformio.ini`, esto corresponde a:

```ini
board = esp32dev
```

PlatformIO puede crear el proyecto en esta ruta por defecto:

```txt
C:\Users\PC\Documents\PlatformIO\Projects\NombreDelProyecto
```

Es importante trabajar desde esa carpeta, porque ahi esta el archivo `platformio.ini`.

---

## 3. Estructura esperada del proyecto

El proyecto deberia quedar asi:

```txt
NombreDelProyecto/
├── .pio/
├── .vscode/
├── include/
├── lib/
├── src/
│   └── main.cpp
├── test/
├── diagram.json
├── platformio.ini
└── wokwi.toml
```

Los archivos importantes son:

| Archivo | Funcion |
|---|---|
| `platformio.ini` | Configura PlatformIO, placa, framework y velocidad del monitor serial |
| `src/main.cpp` | Codigo principal del ESP32 |
| `diagram.json` | Describe el circuito de Wokwi |
| `wokwi.toml` | Le indica a Wokwi que firmware debe ejecutar |

---

## 4. Configurar `platformio.ini`

Abrir el archivo `platformio.ini` y dejarlo asi:

```ini
[env:esp32dev]
platform = espressif32
board = esp32dev
framework = arduino
monitor_speed = 115200
```

Significado:

| Linea | Explicacion |
|---|---|
| `[env:esp32dev]` | Define el entorno de compilacion |
| `platform = espressif32` | Usa la plataforma ESP32 |
| `board = esp32dev` | Usa una placa ESP32 generica compatible con Wokwi |
| `framework = arduino` | Usa Arduino como framework |
| `monitor_speed = 115200` | Define la velocidad del Serial Monitor |

---

## 5. Crear el archivo `wokwi.toml`

En la raiz del proyecto, al lado de `platformio.ini`, crear un archivo llamado:

```txt
wokwi.toml
```

Contenido:

```toml
[wokwi]
version = 1
firmware = ".pio/build/esp32dev/firmware.bin"
elf = ".pio/build/esp32dev/firmware.elf"
```

Este archivo conecta Wokwi con el firmware generado por PlatformIO.

PlatformIO compila el codigo y genera:

```txt
.pio/build/esp32dev/firmware.bin
.pio/build/esp32dev/firmware.elf
```

Wokwi usa esos archivos para ejecutar la simulacion.

---

## 6. Crear o copiar `diagram.json`

El archivo `diagram.json` describe el circuito de Wokwi.

Hay dos formas de obtenerlo:

### Opcion A: copiarlo desde Wokwi Web

1. Abrir la simulacion en Wokwi web.
2. Entrar en la pestaña `diagram.json`.
3. Copiar todo el contenido.
4. Crear un archivo `diagram.json` en la raiz del proyecto local.
5. Pegar el contenido.

### Opcion B: editarlo en VS Code

Con la licencia Community, Wokwi puede no permitir editar visualmente el diagrama desde VS Code.

Pero se puede editar como texto:

1. Click derecho sobre `diagram.json`.
2. Elegir **Open With...**
3. Seleccionar **Text Editor**.

---

## 7. Codigo minimo de prueba en `src/main.cpp`

Ejemplo minimo para probar que PlatformIO y Wokwi funcionan:

```cpp
#include <Arduino.h>

const int PIN_LED = 25;
const int PIN_FOTORESISTOR = 35;

void setup() {
  Serial.begin(115200);

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_FOTORESISTOR, INPUT);

  Serial.println("ESP32 iniciado desde VS Code + PlatformIO + Wokwi");
}

void loop() {
  int lecturaLuz = analogRead(PIN_FOTORESISTOR);

  Serial.print("Lectura fotoresistor: ");
  Serial.println(lecturaLuz);

  if (lecturaLuz < 2000) {
    digitalWrite(PIN_LED, HIGH);
    Serial.println("Noche detectada: luz encendida");
  } else {
    digitalWrite(PIN_LED, LOW);
    Serial.println("Dia detectado: luz apagada");
  }

  delay(1000);
}
```

---

## 8. Configurar el comando `pio` en PowerShell

A veces PlatformIO funciona dentro de VS Code, pero el comando `pio` no esta disponible en PowerShell.

Para agregarlo al `PATH` del usuario, ejecutar:

```powershell
$pioPath = "$env:USERPROFILE\.platformio\penv\Scripts"

[Environment]::SetEnvironmentVariable(
  "Path",
  [Environment]::GetEnvironmentVariable("Path", "User") + ";$pioPath",
  "User"
)

$env:Path += ";$pioPath"

pio --version
```

Si esta bien configurado, deberia mostrar algo como:

```txt
PlatformIO Core, version 6.1.19
```

---

## 9. Compilar el proyecto

Abrir una terminal en la carpeta donde esta `platformio.ini`.

Ejemplo:

```powershell
cd "C:\Users\PC\Documents\PlatformIO\Projects\Sistemas-Operativos-Avanzados"
pio run
```

Si todo esta correcto, debe terminar con:

```txt
[SUCCESS]
```

Esto genera el firmware que Wokwi va a ejecutar.

---

## 10. Iniciar la simulacion en Wokwi

Una vez compilado correctamente:

1. Abrir el panel de **Wokwi Simulator** en VS Code.
2. Seleccionar:

```txt
Start Simulation
```

Si pregunta por archivo de configuracion, seleccionar:

```txt
wokwi.toml
```

Tambien se puede iniciar desde la paleta de comandos:

```txt
Ctrl + Shift + P
Wokwi: Start Simulator
```

---

## 11. Flujo normal de trabajo

Cada vez que se quiera probar un cambio:

```txt
1. Editar src/main.cpp
2. Guardar
3. Ejecutar pio run
4. Si da SUCCESS, tocar Play / Restart en Wokwi
```

Resumen:

```txt
main.cpp -> pio run -> firmware.bin -> Wokwi Simulator
```

No hay hot reload como en React. En ESP32 hay que recompilar el firmware para que los cambios se vean en la simulacion.

---

## 12. Errores comunes

### Error: `pio` no se reconoce

Mensaje:

```txt
pio: The term 'pio' is not recognized...
```

Solucion:

Configurar el PATH con:

```powershell
$pioPath = "$env:USERPROFILE\.platformio\penv\Scripts"

[Environment]::SetEnvironmentVariable(
  "Path",
  [Environment]::GetEnvironmentVariable("Path", "User") + ";$pioPath",
  "User"
)

$env:Path += ";$pioPath"

pio --version
```

---

### Error: `Not a PlatformIO project`

Mensaje:

```txt
NotPlatformIOProjectError: Not a PlatformIO project. `platformio.ini` file has not been found...
```

Causa:

La terminal no esta parada en la carpeta donde existe `platformio.ini`.

Solucion:

Buscar la ubicacion real del proyecto:

```powershell
Get-ChildItem -Path "$env:USERPROFILE\Desktop", "$env:USERPROFILE\Documents" -Recurse -Filter platformio.ini -ErrorAction SilentlyContinue | Select-Object FullName
```

Despues entrar a esa carpeta:

```powershell
cd "RUTA_DONDE_ESTA_PLATFORMIO_INI"
pio run
```

---

### Wokwi no permite editar visualmente `diagram.json`

Con licencia Community puede aparecer:

```txt
Upgrade to Edit Diagram
```

Esto significa que no se puede usar el editor visual desde VS Code, pero si se puede editar el JSON como texto.

Solucion:

1. Click derecho en `diagram.json`.
2. **Open With...**
3. **Text Editor**

O editar el circuito en Wokwi Web y copiar el `diagram.json`.

---

## 13. Recomendacion final

Para trabajar comodo:

1. Editar visualmente el circuito en Wokwi Web si hace falta.
2. Copiar el `diagram.json` al proyecto local.
3. Programar en VS Code.
4. Compilar con `pio run`.
5. Simular con Wokwi Simulator.
6. Versionar el proyecto con Git.

Flujo recomendado:

```txt
VS Code + PlatformIO + Wokwi Simulator + Git
```

