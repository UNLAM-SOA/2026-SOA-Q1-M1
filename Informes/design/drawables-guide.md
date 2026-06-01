# PawGate — Pack de Drawables y Iconos

## Cómo instalar todo

### 1. Drawables de formas (XML)

Copiar todos los archivos `bg_*.xml` de esta carpeta a tu proyecto Android:

```
~/AndroidStudioProjects/PawGate/app/src/main/res/drawable/
```

Desde Android Studio: arrastrar y soltar los archivos sobre la carpeta `res/drawable/` en el panel izquierdo, o copiar con Finder.

**Pre-requisito:** que `colors.xml` tenga estos nombres definidos: `primary`, `surface`, `border`, `danger`. Si los nombraste distinto en tu paleta, abrí los XML y reemplazá las referencias `@color/xxx`.

### 2. Iconos Lucide (SVG)

Corré desde Terminal:

```bash
bash ~/Downloads/.../outputs/download-lucide-icons.sh
```

(o ajustá la ruta al `download-lucide-icons.sh` que está al lado de esta carpeta)

Te queda `~/Downloads/pawgate-icons/` con ~50 SVG.

Después, en Android Studio importás cada uno:

- **File → New → Vector Asset**
- **Asset Type:** Local file (SVG, PSD)
- **Path:** `~/Downloads/pawgate-icons/dog.svg`
- **Name:** `ic_dog`
- **Override default size:** sí, ponelo 24x24dp
- Next → Finish

Repetí para cada ícono que necesites. **Convención de nombres:** `ic_` + nombre con guiones bajos (ej: `ic_door_open`, `ic_chevron_left`).

---

## Catálogo de drawables (qué hace cada uno)

| Archivo | Para qué |
|---|---|
| `bg_logo_primary.xml` | Cuadrado verde redondeado 24dp. Logo de Splash, Login, mini-logo de TopBar. |
| `bg_circle_primary.xml` | Círculo verde sólido. Avatar, indicador online, círculo del radar onboarding. |
| `bg_card.xml` | Card blanca con borde 1dp gris claro y 16dp radius. Cards del Dashboard, lista de eventos, perfil médico. |
| `bg_card_selected.xml` | Card con borde verde 2dp. Para items seleccionados (dispositivo vinculado, tab activo). |
| `bg_card_ripple.xml` | Card blanca clickeable con efecto onda al tocar. Para listas de notificaciones, eventos. |
| `bg_input.xml` | Input de formulario blanco con borde gris 1dp y 12dp radius. EditTexts en Login/Registro. |
| `bg_button_primary.xml` | Botón verde sólido con 14dp radius. Acción primaria ("Iniciar sesión", "Abrir puerta"). |
| `bg_button_primary_ripple.xml` | Versión con onda al tocar. **Usá esta por defecto** en botones primarios. |
| `bg_button_secondary.xml` | Botón blanco con borde gris. Acción secundaria (Bloquear, Llamar a Toby, Cancelar). |
| `bg_button_danger.xml` | Botón rojo sólido. Confirmar bloqueo, cerrar sesión. |
| `bg_topbar_button.xml` | Botón cuadrado-redondeado 44dp para back, more, bell. Mismo estilo que botón secundario pero más chico. |
| `bg_icon_square.xml` | Contenedor verde claro 32-36dp para íconos en listas (timeline, médico). |
| `bg_pill_status.xml` | Pill verde claro para etiquetas "EN VIVO", "Online", "Al día". |
| `bg_pill_danger.xml` | Pill rojo claro para etiquetas "BLOCK", "Error", "Offline". |
| `bg_pill_warning.xml` | Pill naranja claro para etiquetas "En 3 días", "Batería baja". |

---

## Mapa de íconos Lucide → uso en PawGate

| Ícono Lucide (SVG) | Nombre Android sugerido | Dónde |
|---|---|---|
| `dog.svg` | `ic_dog` | Logo de Splash, Login, Perfil Toby |
| `door-open.svg` | `ic_door_open` | Botón Abrir, BottomNav Puerta |
| `lock.svg` | `ic_lock` | Bloquear, password input |
| `megaphone.svg` | `ic_megaphone` | Llamar al perro |
| `bell.svg` | `ic_bell` | Notificaciones (top-right) |
| `house.svg` | `ic_house` | BottomNav Inicio |
| `chart-column.svg` | `ic_chart_column` | BottomNav Historial |
| `settings.svg` | `ic_settings` | BottomNav Ajustes |
| `chevron-left.svg` | `ic_chevron_left` | Back button en TopBar |
| `chevron-right.svg` | `ic_chevron_right` | Indicador en filas de lista |
| `ellipsis.svg` | `ic_ellipsis` | Botón "más" (tres puntos) |
| `plus.svg` | `ic_plus` | Agregar (en TopBar) |
| `x.svg` | `ic_x` | Cerrar |
| `mail.svg` | `ic_mail` | Input email en Login |
| `user.svg` | `ic_user` | Input nombre en Registro |
| `shield.svg` | `ic_shield` | Confirmar password |
| `wifi.svg` | `ic_wifi` | Status WiFi |
| `battery-full.svg` | `ic_battery_full` | Batería full |
| `battery-medium.svg` | `ic_battery_medium` | Batería 50% |
| `battery-low.svg` | `ic_battery_low` | Alerta batería baja |
| `signal.svg` | `ic_signal` | Señal celular en status bar |
| `radio.svg` | `ic_radio` | Sensor de ultrasonido activo |
| `cpu.svg` | `ic_cpu` | ESP32 detectado |
| `log-in.svg` | `ic_log_in` | Evento "Toby entró" |
| `log-out.svg` | `ic_log_out` | Evento "Toby salió" |
| `arrow-right.svg` | `ic_arrow_right` | Flechas en flows |
| `arrow-up-right.svg` | `ic_arrow_up_right` | Indicador en cards |
| `check.svg` | `ic_check` | Confirmación |
| `check-check.svg` | `ic_check_check` | Marcar todo como leído |
| `shield-check.svg` | `ic_shield_check` | "Todo bajo control" |
| `shield-alert.svg` | `ic_shield_alert` | Alerta de intento sin RFID |
| `timer.svg` | `ic_timer` | "Última actividad: hace 8m" |
| `funnel.svg` | `ic_funnel` | Filtro en Historial |
| `cake.svg` | `ic_cake` | Edad de Toby |
| `scale.svg` | `ic_scale` | Peso de Toby |
| `syringe.svg` | `ic_syringe` | Vacunas |
| `stethoscope.svg` | `ic_stethoscope` | Control médico |
| `pill.svg` | `ic_pill` | Antiparasitario |
| `moon.svg` | `ic_moon` | Modo nocturno |
| `moon-star.svg` | `ic_moon_star` | Preset nocturno |
| `sun.svg` | `ic_sun` | Preset día |
| `sunrise.svg` | `ic_sunrise` | Preset mañana |
| `pencil.svg` | `ic_pencil` | Botón editar |
| `ruler.svg` | `ic_ruler` | Calibrar ultrasonido |
| `settings-2.svg` | `ic_settings_2` | Ángulo del servo |
| `life-buoy.svg` | `ic_life_buoy` | Ayuda y soporte |
| `corner-up-left.svg` | `ic_corner_up_left` | Volver / atrás visual |
| `globe.svg` | `ic_globe` | IP local, Google |

---

## Ejemplo de uso en un XML

### Logo de Splash

```xml
<FrameLayout
    android:layout_width="120dp"
    android:layout_height="120dp"
    android:background="@drawable/bg_logo_primary">
    <ImageView
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:layout_gravity="center"
        android:src="@drawable/ic_dog"
        app:tint="@android:color/white" />
</FrameLayout>
```

### Botón primario con onda

```xml
<TextView
    android:layout_width="match_parent"
    android:layout_height="56dp"
    android:background="@drawable/bg_button_primary_ripple"
    android:clickable="true"
    android:focusable="true"
    android:gravity="center"
    android:text="@string/login_button"
    android:textColor="@color/text_on_primary"
    android:textSize="16sp"
    android:textStyle="bold" />
```

### Input con icono adentro

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="56dp"
    android:background="@drawable/bg_input"
    android:gravity="center_vertical"
    android:orientation="horizontal"
    android:paddingHorizontal="16dp">
    <ImageView
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:src="@drawable/ic_mail"
        app:tint="@color/text_muted" />
    <EditText
        android:layout_width="0dp"
        android:layout_height="match_parent"
        android:layout_marginStart="12dp"
        android:layout_weight="1"
        android:background="@null"
        android:hint="@string/login_email_hint"
        android:inputType="textEmailAddress"
        android:textColor="@color/text_primary"
        android:textColorHint="@color/text_muted" />
</LinearLayout>
```

### Card de status con pill verde

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_card"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_pill_status"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingHorizontal="10dp"
        android:paddingVertical="4dp">
        <View
            android:layout_width="6dp"
            android:layout_height="6dp"
            android:background="@drawable/bg_circle_primary" />
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="6dp"
            android:text="EN VIVO"
            android:textColor="@color/primary"
            android:textSize="10sp"
            android:textStyle="bold" />
    </LinearLayout>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:text="Puerta · Cerrada"
        android:textColor="@color/text_primary"
        android:textSize="20sp"
        android:textStyle="bold" />

</LinearLayout>
```

---

## Tip de defensa

Si te preguntan en el parcial **"¿qué es un drawable?"**:

> "Es cualquier recurso visual que se puede dibujar en pantalla: imágenes raster (PNG/JPG en `drawable-xxxhdpi`), íconos vectoriales (`<vector>`), formas XML (`<shape>`), gradientes, selectores y efectos como ripple. Viven en `res/drawable/` y se referencian con `@drawable/nombre` desde XML o `R.drawable.nombre` desde Java."

Si te preguntan **"¿qué ventaja tiene un shape XML sobre un PNG?"**:

> "Pesa kilobytes en lugar de megabytes, escala sin pixelarse a cualquier densidad de pantalla, y respeta el theme (puedo bindear colores a variables). Para fondos planos con bordes redondeados, gradientes o stroke, siempre es preferible a una imagen rasterizada."
