# Diseño de arquitectura:

- 2 matrices de transición porque tengo 2 máquinas de estados
- Estructura de datos para los sensores:

```c
	struct stSensor
	{
	  int  pin;
	  int  estado;
	  long valor_actual;
	  long valor_previo;
	};
	stSensor sensores[MAX_CANT_SENSORES];
```

## Función Setup:

### Tasks para la luz
- Task __luz_deteccion__: Detección de eventos (Lectura de sensores) de solo el sensor de luz -> Evento a cola Eventos_Luz
- Task __luz_controlador__: Lógica de control / FMS: Recibe eventos en la cola Eventos_Luz , evalúa estado actual, decide transición, genera acción y la coloca en la cola Acciones_Luz
- Task __luz_accion__: Control de actuador: Recibe la Acción en la cola Acciones_Luz y activa/apaga el Led de ser necesario
### Tasks para la puerta
- Task __puerta_deteccion__: Detección de eventos (Lectura de sensores) del sensor RFID, y del sensor de proximidad (ECHO) -> Evento a cola Eventos_Puerta
- Task __puerta_controlador__: Lógica de control / FMS: Recibe eventos en la cola Eventos_Puerta, evalúa estado actual, decide transición, genera acción y la coloca en la cola Acciones_Puerta
- Task __puerta_accion__: Control de los actuadores: Recibe la acción en la cola Acciones_Puerta y cambia el ángulo del servomotor, a la vez de emitir un sonido en el buzzer.


>[!tip] Las tareas deben crearse una vez sola y tener un while(1) para no crear millones de tareas en el bucle principal. En un principio le pondremos a todas las tareas prioridad 1

>[!warning] Se debe usar una suspensión de tareas no bloqueante para pasar a la ejecución de la siguiente.  Ej. vTaskDelay(100);

# Plan de implementación:
Comenzaremos solo con las tasks y la tabla de transiciones de la Luz.

Inicialmente había contemplado estas dos transiciones, pero sería más optimo si las eliminamos y simplemente no emitimos eventos si no hay cambios.
- Luz_apagada transiciona a si misma sin encolar acciones si se detecta el evento dia_detectado.
- Luz_encendida transiciona a si misma sin encolar acciones si se detecta el evento noche_detectada.

## Luz
### Estados: 
Luz apagada -> El led está apagado porque anteriormente se detectó que es de día.
Luz encendida -> El led está encendido porque anteriormente se detectó que es de noche.
### Transiciones y acciones:
- Comenzamos en el estado luz_apagada
- Luz_apagada transiciona a luz_encendida si se detecta el evento EV_NOCHE_DETECTADA, además encola la accion/orden ACC_ENCENDER_LUZ
- Luz_encendida transiciona a luz_apagada si se detecta el evento EV_DIA_DETECTADO, además encola la acción/orden ACC_APAGAR_LUZ
### luz_accion:
- Si se recibe la orden/acción ACC_ENCENDER_LUZ, se debe ejecutar esa función donde el led se pone en encendido.
- Si se recibe la orden/accion ACC_APAGAR_LUZ, se debe ejecutar esa función donde el led se pone en apagado.
