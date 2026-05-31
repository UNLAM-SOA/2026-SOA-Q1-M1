# PawGate — Plan completo (Android Java + AWS IoT Core)

Stack:

- **Cliente:** Android + **Java + XML** (Android Studio). Min SDK 26.
- **Edge:** ESP32 + FreeRTOS con WiFi + MQTT-TLS.
- **Broker IoT:** AWS IoT Core.
- **Backend serverless:** Lambda + DynamoDB + API Gateway + Cognito.
- **Push:** SNS Platform Application → FCM → Android.
- **Dashboard paralelo:** Node-RED en Docker local.

Diagrama: `arquitectura-pawgate.svg`.

---

## Regla de juego del aprendizaje

**Yo no escribo código por vos.** Te explico el concepto, el archivo a tocar y qué buscar en la doc. Vos escribís. Si te trabás, preguntás "no entiendo X". **Excepción:** recursos no son código (drawables, paletas, snippets de config AWS, layouts auto-generados por wizard).

---

## Bloques y fases (23 totales)

```
A. Fundamentos         (1, 2, 3)     ← hecho
B. UI Construction     (4-8)         ← acá estamos
C. Estado y persistencia (9-11)
D. Cloud backend       (12-15)
E. Integración         (16-18)
F. Features avanzados  (19-21)
G. Cierre              (22-23)
```

---

# BLOCK A · Fundamentos Android

## FASE 1 — Crear proyecto · ✅

Empty Views Activity, Java, Min SDK 26, Groovy DSL. Tour de carpetas. MainActivity, activity_main, AndroidManifest, ambos build.gradle.

**Para defender:** qué declara el Manifest. Diferencia entre los dos build.gradle.

---

## FASE 2 — Ciclo de vida · ✅ ⭐ CAE EN PARCIAL

Logs en los 7 callbacks. Pruebas con Logcat: abrir, home, rotar, back, finish.

**Para defender:** secuencia al rotar (destroy → recreate), por qué `super.onX()` siempre, cómo preservar estado con `savedInstanceState`.

---

## FASE 3 — Intents · ✅

Splash → Login → Dashboard con Intents explícitos, `putExtra` + Bundle. `Handler.postDelayed` en Splash.

**Para defender:** explícito vs implícito, putExtra, Intent Filter.

---

# BLOCK B · UI Construction

## FASE 4 — Layouts XML básicos · ⏳ EN CURSO

**Pantallas:** Splash, Login, Registro, Onboarding (las 4 "simples", sin listas ni cards complejas).

**Conceptos:**
- ViewGroups: ConstraintLayout, LinearLayout, FrameLayout.
- Views: TextView, EditText, Button, ImageView.
- Atributos `layout_*` (gravity vs layout_gravity, width/height, margin/padding).
- Referencia a recursos (`@drawable`, `@string`, `@color`, `@dimen`).
- Unidades: dp vs sp vs px.

**Tarea:** rehacer los XML de las 4 pantallas usando los drawables y íconos ya instalados.

**Para defender:** ConstraintLayout vs LinearLayout, gravity vs layout_gravity, dp vs sp.

---

## FASE 5 — Layouts XML complejos

**Pantallas:** Dashboard, Control de puerta, Perfil Toby, Horarios, Ajustes.

**Conceptos nuevos respecto a Fase 4:**
- Composición avanzada con `<include>` para reutilizar partes (BottomNav, TopBar).
- Layouts en grilla 2x2 con LinearLayout anidados o GridLayout.
- Cards visuales con sombras, bordes y elevación.
- `<merge>` para optimizar jerarquías.
- Layouts adaptativos con `wrap_content` + `weight`.

**Tarea:** rehacer las 5 pantallas. Crear `bottom_nav.xml`, `top_bar.xml` como layouts reutilizables y usarlos con `<include>`.

**Para defender:** por qué `<include>` mejora mantenibilidad, qué es `<merge>` y cuándo usarlo, cómo funciona `weight` en LinearLayout.

---

## FASE 6 — RecyclerView (Historial + Notificaciones) ⭐ CAE EN PARCIAL

**Pantallas:** Historial (timeline de eventos), Notificaciones (lista con tipos).

**Conceptos:**
- **RecyclerView**: ViewGroup especializado para listas largas con scroll, reciclando views.
- **Adapter**: clase intermediaria entre los datos y las views. Implementa `onCreateViewHolder`, `onBindViewHolder`, `getItemCount`.
- **ViewHolder**: contenedor que cachea las referencias a las views de un item (evita llamar `findViewById` mil veces).
- **LayoutManager**: define cómo se acomodan los items (Linear, Grid, Staggered).
- **DiffUtil**: cuando actualizás la lista, calcula el diff y solo redibuja lo que cambió.
- **Item XML** (`item_event.xml`, `item_notification.xml`).
- **Click listener** sobre items.

**Tarea:**
1. Crear `item_event.xml` con la fila visual (ícono + texto + hora).
2. Crear `EventsAdapter extends RecyclerView.Adapter<EventsAdapter.ViewHolder>`.
3. Implementar `ViewHolder` interno con `findViewById` en su constructor.
4. En HistorialActivity, instanciar adapter, setear LayoutManager, llenarlo con datos hardcodeados.
5. Repetir patrón para Notificaciones.

**Para defender:** explicar la trilogía Adapter + ViewHolder + LayoutManager, por qué el patrón View Holder ahorra performance, qué hace DiffUtil.

---

## FASE 7 — Material Components + Forms

**Conceptos:**
- Diferencia entre **Views clásicos** (`Button`, `EditText`) y **Material Components** (`MaterialButton`, `TextInputEditText` envuelto en `TextInputLayout`).
- `TextInputLayout` te da: label flotante, hint, error inline, contador de caracteres, ícono al lado.
- `MaterialCardView` para cards con elevation, ripple, corner radius — todo nativo.
- `SwitchMaterial`, `Checkbox`, `RadioGroup`.
- **Validación de forms en vivo** con `TextWatcher` (callback en cada keystroke).

**Tarea:**
1. Reemplazar `EditText` por `TextInputLayout` + `TextInputEditText` en Login y Registro.
2. Validación de email con regex: muestra error si no es válido.
3. Validación de password: mínimo 6 chars.
4. Botón "Iniciar sesión" habilitado solo cuando ambos campos son válidos.
5. Cambiar `Button` por `MaterialButton` (te da ripple gratis).

**Para defender:** TextInputLayout vs EditText pelado, ventajas de los Material Components, cómo se valida en vivo con TextWatcher, qué es ripple feedback.

---

## FASE 8 — Dialogs, BottomSheets, TimePicker

**Conceptos:**
- **AlertDialog**: cuadro de confirmación modal (con botones positivo/negativo).
- **MaterialAlertDialogBuilder**: la versión moderna.
- **BottomSheetDialog**: deslizable desde abajo (más moderno que un dialog).
- **TimePickerDialog** / **MaterialTimePicker**: para elegir horas (en Horarios).
- **DatePickerDialog**: para fechas (ej. próximo control veterinario en Perfil).
- **Toast** vs **Snackbar**: feedback rápido vs feedback con acción ("Deshacer").

**Tarea:**
1. En Control puerta, al tocar "Bloquear" mostrar `MaterialAlertDialog` con título "¿Bloquear puerta?" y botones "Cancelar" / "Bloquear".
2. En Horarios, al tocar el bloque "Desde 22:00", abrir `MaterialTimePicker`. Al confirmar, actualizar el TextView.
3. En Perfil, al apretar editar abrir un `BottomSheetDialog` con form de datos del perro.
4. Usar `Snackbar` en lugar de Toast para feedback de acciones.

**Para defender:** diferencia AlertDialog vs BottomSheet (modal full-screen vs deslizable), Toast vs Snackbar, cómo se devuelve el resultado del picker.

---

# BLOCK C · Estado y persistencia local

## FASE 9 — Background: Thread/Handler vs AsyncTask vs Service ⭐ CAE EN PARCIAL

Misma tarea (delay 5s + actualizar TextView) hecha de 4 formas:

1. **Versión rota:** todo en Main Thread. Provoca ANR.
2. **Thread + Handler:** Thread secundario hace el sleep, Handler en Main actualiza UI.
3. **AsyncTask:** los 4 métodos (`onPreExecute`, `doInBackground`, `onProgressUpdate`, `onPostExecute`).
4. **Service:** Started Service que crea Thread interno.

**Para defender:** por qué bloquear Main es malo, los 4 métodos de AsyncTask y en qué thread corre cada uno, por qué AsyncTask quedó deprecado, diferencia Service vs IntentService.

---

## FASE 10 — SharedPreferences (persistencia local clave-valor)

**Conceptos:**
- `SharedPreferences`: archivo XML con pares clave-valor que sobrevive a reinstalación parcial de la app.
- Ideal para: token JWT, "primer-arranque-completo", settings simples (modo oscuro forzado, frecuencia de polling).
- **NO usar para datos sensibles sin encriptar.** Para tokens reales se usa `EncryptedSharedPreferences` del Security library.
- API: `getSharedPreferences("name", MODE_PRIVATE)`, `.edit()`, `.putString()`, `.apply()` (asincrónico) vs `.commit()` (sincrónico).

**Tarea:**
1. Crear clase `SessionManager` (singleton): expone `saveToken(jwt)`, `getToken()`, `clearSession()`.
2. En LoginActivity, después de auth exitoso, guardar el JWT con `sessionManager.saveToken(...)`.
3. En SplashActivity, antes de redirigir: chequear `sessionManager.getToken()`. Si existe, ir directo a Dashboard; si no, a Login.
4. En Ajustes → "Cerrar sesión", llamar `sessionManager.clearSession()` y volver a Login.

**Para defender:** qué son SharedPreferences, cuándo NO usarlas, `apply()` vs `commit()`, por qué hacés un Singleton para SessionManager.

---

## FASE 11 — savedInstanceState + back stack

**Conceptos:**
- Cuando una Activity es destruida (rotación, low memory), Android llama `onSaveInstanceState(Bundle outState)` antes de matarla.
- Al recrearla, te pasa ese Bundle en `onCreate(Bundle savedInstanceState)`.
- **Esto preserva estado entre rotaciones sin tener que mover todo a un ViewModel.**
- El **back stack** es la pila LIFO de Activities. Cada `startActivity` agrega. Cada `finish` saca. Flags como `FLAG_ACTIVITY_CLEAR_TOP` o `singleTop` modifican el comportamiento.

**Tarea:**
1. En LoginActivity, si el usuario escribió el email pero todavía no envió, persistir el valor entre rotaciones (override `onSaveInstanceState`).
2. Probar: escribir "fede@", rotar el celular, ver que se mantiene el texto.
3. En LoginActivity, al lanzar Dashboard usar `Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK` — eso vacía el back stack para que Back no vuelva a Login.
4. En SplashActivity llamar `finish()` después del `startActivity` para no quedar en la pila.

**Para defender:** cuándo se llama `onSaveInstanceState`, qué es el Bundle, qué diferencia hay entre rotación vs low-memory-kill, cómo funciona el back stack, flags útiles.

---

# BLOCK D · Cloud backend (AWS)

## FASE 12 — Setup AWS + IAM + Billing Alerts

1. Crear cuenta en aws.amazon.com (tarjeta + verificación tel).
2. **Billing Alarms** en CloudWatch (alerta a 1 USD).
3. Crear usuario IAM (nunca usar root). Group + MFA.
4. Instalar AWS CLI (`brew install awscli`). Configurar con `aws configure`. Región: us-east-1.
5. Verificar con `aws sts get-caller-identity`.

**Para defender:** por qué no usar root, qué es IAM (user/group/role/policy), por qué MFA, qué es la región.

---

## FASE 13 — AWS IoT Core: Thing + Cert + Policy

1. Crear Thing `pawgate-001`.
2. Generar cert X.509 + private key. Descargar los 3 archivos (cert.pem, private.key, AmazonRootCA1.pem).
3. Crear policy `pawgate-device-policy` con permisos mínimos por thing (variable `${iot:Connection.Thing.ThingName}`).
4. Anotar endpoint MQTT con `aws iot describe-endpoint --endpoint-type iot:Data-ATS`.

**Para defender:** qué es mTLS, qué es un Thing, cómo la policy aísla un dispositivo de otro.

---

## FASE 14 — Firmware ESP32: WiFi + MQTT-TLS

1. Nueva tarea FreeRTOS `tarea_mqtt` con su cola.
2. `setup()`: WiFi.begin + cargar certs X.509 en PROGMEM + PubSubClient sobre WiFiClientSecure.
3. La tarea: conecta al broker, suscribe a `.../commands`, publica de la cola hacia `.../events` y `.../state`.
4. La tarea de detección también mete eventos a la cola MQTT.
5. Callback de mensaje entrante parsea JSON y mete el evento equivalente a la cola del controller.

**Para defender:** topología MQTT, pub/sub, por qué TLS, qué es keep-alive MQTT, por qué tarea aparte para I/O de red.

---

## FASE 15 — Backend serverless (DynamoDB + Lambdas + Rules + API Gateway + Cognito)

1. **DynamoDB**: tablas `DeviceState`, `Events`, `Schedules`, `Users`.
2. **Lambdas**: `ProcessEvent` (triggered by IoT Rule, escribe a DDB), `SendCommand` (triggered by API, publica MQTT), `GetState`, `GetEvents`, `GetSchedules`, `UpdateSchedules`.
3. **IoT Rules**: `events_to_lambda`, `state_to_ddb`, `alerts_to_sns`.
4. **Cognito User Pool**: login email/password, JWT, sub claim.
5. **API Gateway REST**: authorizer Cognito + rutas conectadas a Lambdas (`POST /auth/login`, `GET /devices/me/state`, `POST /devices/me/commands`, `GET /events`, `GET /schedules`, `PUT /schedules`).
6. **CORS** habilitado para probar desde browser.

**Para defender:** qué es Lambda y serverless, qué es event-driven, qué es API Gateway, cómo un JWT prueba autenticación stateless, por qué Cognito en lugar de auth propio.

---

# BLOCK E · Integración Android ↔ Cloud

## FASE 16 — Retrofit + Cognito SDK

**Conceptos:**
- **Retrofit**: cliente HTTP que se configura con anotaciones (`@GET`, `@POST`, `@Header`).
- **Gson converter**: mapea JSON ↔ POJOs Java.
- **OkHttp interceptor**: middleware en cada request — útil para meter el JWT como `Authorization: Bearer xxx` automático.
- **Cognito Android SDK**: maneja login, signup, refresh tokens.

**Tarea:**
1. Agregar dependencias en `build.gradle (Module: app)`.
2. Crear interface `PawGateApi` con los endpoints.
3. POJOs: `LoginRequest`, `LoginResponse`, `DeviceState`, `Event`, `CommandRequest`.
4. `ApiClient` singleton con builder de Retrofit + OkHttp interceptor que lee el JWT de SharedPreferences y lo mete en cada request.
5. Conectar LoginActivity al `/auth/login` real.

**Para defender:** Retrofit vs HttpURLConnection, qué hace un interceptor, cómo se mapean los POJOs, qué es un Callback de Retrofit.

---

## FASE 17 — Conectar pantallas al backend

**Pantallas a conectar:**
- **Dashboard:** `GET /devices/me/state` en `onResume`, repetir cada 10s con un Handler.
- **Control puerta:** los 3 botones → `POST /devices/me/commands` con `{cmd: "open"|"lock"|"buzz"}`.
- **Historial:** `GET /events?from=...&to=...`, llenar RecyclerView.
- **Horarios:** `GET /schedules` para mostrar, `PUT /schedules` al modificar.
- **Perfil:** `GET /dog/me` y `PUT /dog/me`.
- **Notificaciones:** `GET /notifications`.

**Tarea:** una pantalla a la vez. Cada una con su loading state mientras espera la respuesta.

**Para defender:** por qué llamar al backend en `onResume` y no en `onCreate`, cómo manejás que la pantalla quede vieja si pasa mucho tiempo, qué pasa si el celu pierde conectividad.

---

## FASE 18 — Manejo de red: loading, error, retry, offline

**Conceptos:**
- **Loading state**: ProgressBar visible mientras esperás.
- **Error state**: mensaje "No se pudo conectar" + botón "Reintentar".
- **Empty state**: "No hay eventos hoy" cuando la lista viene vacía.
- **Pull-to-refresh** con `SwipeRefreshLayout`.
- **(Opcional avanzado) Room** para cache local — la app muestra datos viejos cuando no hay red.
- **Detección de conectividad** con `ConnectivityManager`.

**Tarea:**
1. Reorganizar cada pantalla con sealed states: `Loading`, `Success(data)`, `Error(message)`.
2. Agregar `SwipeRefreshLayout` al Dashboard y Historial.
3. En errores 401, redirigir a Login (token expirado).
4. En errores de red, mostrar Snackbar con acción "Reintentar".

**Para defender:** cómo se ven los tres estados, por qué pull-to-refresh, qué pasa con un token expirado.

---

# BLOCK F · Features avanzados

## FASE 19 — Sensores: acelerómetro

1. SensorManager, SensorEventListener (apunte cap. 6).
2. Registrar listener en `onResume`, desregistrar en `onPause`.
3. `onSensorChanged` con detección de pico (módulo del vector > threshold).
4. Feature **shake-to-call**: agitando fuerte el celu, dispara `POST /commands {cmd:"buzz"}`.

**Para defender:** acelerómetro vs giroscopio, por qué registrar/desregistrar en lifecycle, qué trae SensorEvent.

---

## FASE 20 — FCM push notifications via SNS

1. Crear proyecto Firebase → bajar `google-services.json`.
2. Servicio `PawGateMessagingService extends FirebaseMessagingService` con `onMessageReceived`, `onNewToken`.
3. En `onNewToken`, postear al backend (`POST /devices/me/push-token`).
4. SNS Platform Application apuntando a Firebase Server Key.
5. Lambda dispara SNS publish con el endpoint del usuario cuando hay evento crítico.

**Para defender:** push por token vs por topic, en qué thread corre `onMessageReceived`, qué es SNS Platform App.

---

## FASE 21 — Node-RED Dashboard observador

1. Docker en tu Mac: `docker run -d -p 1880:1880 nodered/node-red`.
2. Segundo certificate en IoT Core para Thing virtual `node-red-dashboard`.
3. Flow con `mqtt in` suscrito a `.../events`, `.../state`. UI con `ui_chart`, `ui_table`, `ui_gauge`, `ui_button`.
4. Acceder a `http://localhost:1880/ui`.

**Para defender:** qué ventaja tiene un cliente observador paralelo, por qué pub/sub permite múltiples suscriptores sin cambio en el publisher.

---

# BLOCK G · Cierre

## FASE 22 — Build release + APK firmado + tooling

**Conceptos:**
- **Build variants**: `debug` (con logs, sin firma) vs `release` (firmado, sin logs).
- **Keystore** para firmar APKs (uno solo para todo el proyecto, guardalo bien).
- **ProGuard / R8**: minifica y ofusca el código en release (reduce tamaño APK).
- **APK vs AAB**: el AAB (Android App Bundle) es lo que sube a Play Store; el APK es el instalable directo. Para entrega de cátedra, el APK alcanza.
- **Layout Inspector**, **Profiler**, **Logcat** filtros — herramientas Android Studio.

**Tarea:**
1. Build → Generate Signed Bundle / APK → APK → crear keystore nuevo, anotar password.
2. Buildar release APK. Probarlo en emulador y en celu físico (transferir APK por USB).
3. Verificar que NO hay logs de debug en la versión release (filtrar por tag en Logcat).
4. Documentar en el README cómo correr la app.

**Para defender:** qué diferencia debug/release, qué hace R8, por qué firmar es importante.

---

## FASE 23 — Defensa de parcial · Q&A de las 30 preguntas

Repaso con simulacro. Te hago las preguntas, vos respondés, te corrijo.

**Preguntas top-30 ordenadas por probabilidad:**

**Android base (cátedra):**
1. Arquitectura Android — 5 capas.
2. ¿Qué declara AndroidManifest?
3. Ciclo de vida de Activity — secuencias específicas (rotación, home, back).
4. ¿Qué es un Bundle? ¿Para qué `savedInstanceState`?
5. Intent explícito vs implícito + ejemplos.
6. `putExtra` y `getIntent`.
7. Thread + Handler vs AsyncTask vs Service.
8. Los 4 métodos de AsyncTask + threads.
9. Por qué AsyncTask deprecado.
10. Service Started vs Bound, Foreground vs Background.
11. SensorManager + SensorEventListener + acelerómetro vs giroscopio.
12. ¿Por qué registrar en `onResume` y desregistrar en `onPause`?
13. ADB — qué es, para qué.

**UI Android:**
14. ConstraintLayout vs LinearLayout.
15. RecyclerView — Adapter, ViewHolder, LayoutManager.
16. SharedPreferences — cuándo y cuándo no.
17. TextInputLayout vs EditText.

**IoT / MQTT:**
18. Topología MQTT, publish/subscribe.
19. Topics jerárquicos + wildcards `+` y `#`.
20. QoS en MQTT (0, 1, 2).
21. Por qué TLS / mTLS.

**AWS:**
22. IoT Core — Thing, certs, policy.
23. IoT Rule.
24. SNS vs SQS — cuándo cada uno.
25. Por qué Lambda / serverless.
26. API Gateway + Cognito + JWT.

**REST:**
27. REST sincrónico vs asincrónico.
28. Cómo mapea Retrofit JSON ↔ Java.

**Firebase:**
29. Push por token vs topic. En qué thread corre `onMessageReceived`.

**Sobre tu TP:**
30. Contame el flujo de "Abrir puerta" desde el botón en la app hasta el servo. ¿Y si la app se cae a la mitad?

---

## Estimación de tiempo total

| Bloque | Fases | Tiempo estimado |
|---|---|---|
| A · Fundamentos | 1-3 | 6h (✅ hecho) |
| B · UI Construction | 4-8 | 18-25h |
| C · Estado/persistencia | 9-11 | 8-10h |
| D · Cloud backend | 12-15 | 12-16h |
| E · Integración | 16-18 | 10-12h |
| F · Features avanzados | 19-21 | 8-10h |
| G · Cierre | 22-23 | 4-6h |
| **TOTAL** | 23 | **~70-90h** |

Distribuidas en ~6 semanas a razón de 12-15h/semana, llegás cómodo.

---

## Sources

- Diagrama arquitectura: `arquitectura-pawgate.svg`
- Detalle servicios AWS: `ARQUITECTURA-AWS.md`
- Drawables y íconos: `drawables/README.md`
- Apuntes cátedra: `Apunte Teorico Android.pdf`, `Thread y Sincronizacion.pdf`
- ESP32 actual: `Actividad1_Martes_M1.pdf`
