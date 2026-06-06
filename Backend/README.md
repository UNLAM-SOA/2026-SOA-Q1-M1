# PawGate · Backend serverless

Backend AWS para PawGate. Compone:
- **Lambdas** (`lambdas/`) — funciones serverless en Python.
- **IoT Rules** (`iot-rules/`) — reglas SQL-like que filtran MQTT y disparan acciones.
- **IAM Roles** (`iam-roles/`) — trust policies y permission policies.

## Fase 15.B — `eventIngest` deployment

### Pre-requisitos
- AWS CLI configurado con credenciales de `fede-dev` (no root).
- Región default: `us-east-1`.
- Account ID: `075138626693`.
- DDB table `pawgate_events` creada (Fase 15.A).

### 1) Crear el IAM role del Lambda

```bash
cd ~/Desktop/Sirius/Sistemas-Operativos-Avanzados

# Crear el rol con trust policy (lambda.amazonaws.com puede asumir)
aws iam create-role \
  --role-name pawgate-event-ingest-role \
  --assume-role-policy-document file://Backend/iam-roles/lambda-event-ingest-trust.json

# Adjuntar la policy custom (DDB PutItem + CloudWatch Logs)
aws iam put-role-policy \
  --role-name pawgate-event-ingest-role \
  --policy-name pawgate-event-ingest-policy \
  --policy-document file://Backend/iam-roles/lambda-event-ingest-policy.json

# Verificar el ARN del role (lo necesitas para el siguiente paso)
aws iam get-role --role-name pawgate-event-ingest-role --query 'Role.Arn' --output text
# Esperado: arn:aws:iam::075138626693:role/pawgate-event-ingest-role
```

### 2) Empaquetar y subir el Lambda

```bash
cd Backend/lambdas/eventIngest

# Comprimir el codigo (boto3 ya esta incluido en el runtime de Lambda Python, no hay que packagearlo)
zip eventIngest.zip lambda_function.py

# Crear la Lambda
aws lambda create-function \
  --function-name pawgate-event-ingest \
  --runtime python3.12 \
  --role arn:aws:iam::075138626693:role/pawgate-event-ingest-role \
  --handler lambda_function.lambda_handler \
  --timeout 10 \
  --memory-size 256 \
  --environment "Variables={EVENTS_TABLE=pawgate_events,TTL_DAYS=90}" \
  --zip-file fileb://eventIngest.zip \
  --region us-east-1

cd ../../..
```

Si despues actualizas el codigo, en vez de `create-function`:

```bash
cd Backend/lambdas/eventIngest
zip -r eventIngest.zip lambda_function.py
aws lambda update-function-code \
  --function-name pawgate-event-ingest \
  --zip-file fileb://eventIngest.zip
cd ../../..
```

### 3) Permitir que IoT Core invoque la Lambda

AWS exige que la Lambda tenga una **resource-based policy** que permita a iot.amazonaws.com invocarla. Sin esto, la IoT Rule falla silenciosamente con AccessDenied:

```bash
aws lambda add-permission \
  --function-name pawgate-event-ingest \
  --statement-id allow-iot \
  --action lambda:InvokeFunction \
  --principal iot.amazonaws.com \
  --source-arn "arn:aws:iot:us-east-1:075138626693:rule/pawgate_events_rule"
```

### 4) Crear el IoT Rule

```bash
aws iot create-topic-rule \
  --rule-name pawgate_events_rule \
  --topic-rule-payload file://Backend/iot-rules/pawgate-events-rule.json
```

Verificar que se creo:

```bash
aws iot list-topic-rules --query 'rules[?ruleName==`pawgate_events_rule`]'
```

### 5) Test end-to-end

**Terminal 1**: corré el simulador (para que esté listening cmds):
```bash
python Embebido/simulator/device_simulator.py
```

**Terminal 2**: publicá un cmd y verificá que el evento llega a DDB:
```bash
# Disparar cmd/open
aws iot-data publish \
  --topic pawgate/pawgate-001/cmd/open \
  --payload '{"source":"test-15B"}' \
  --cli-binary-format raw-in-base64-out

# Esperar ~3s para que el simulador procese el ciclo y publique events/door
sleep 3

# Scanear la tabla de eventos para ver los nuevos rows
aws dynamodb scan --table-name pawgate_events --region us-east-1 \
  --query 'Items[*].{device:device_id.S, ts:ts_event.S, event_type:event_type.S}' \
  --output table
```

Deberías ver al menos 2 rows nuevos:
- `pawgate-001 | <ts>#door#opened`
- `pawgate-001 | <ts>#door#closed`

Más los sensor events que el simulador emite cada 5s.

### 6) Debuggear si algo falla

**Ver los logs de la Lambda** (los `print` y `logger.info` van acá):
```bash
aws logs tail /aws/lambda/pawgate-event-ingest --since 5m --follow
```

**Ver si el IoT Rule se está disparando**:
```bash
# Activar logs detallados de IoT (ya lo hicimos en Fase 13)
aws logs tail AWSIotLogsV2 --since 5m --follow | grep RuleExecution
```

**Smoke test directo de la Lambda** (sin pasar por IoT Rule):
```bash
aws lambda invoke \
  --function-name pawgate-event-ingest \
  --payload '{"topic":"pawgate/pawgate-001/events/door","type":"opened","ts":1780347796470}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/lambda-out.json

cat /tmp/lambda-out.json
# Esperado: {"statusCode": 200, "device_id": "pawgate-001", "ts_event": "1780347796470#door#opened"}
```

## Arquitectura

```
[device/simulator]
   │ MQTT publish
   ▼
pawgate/pawgate-001/events/door
   │
   ▼
[AWS IoT Core broker]
   │ matches IoT Rule SQL filter
   ▼
[IoT Rule: pawgate_events_rule]
   │ SELECT *, topic(), timestamp() FROM 'pawgate/+/events/+'
   │ action: invoke Lambda
   ▼
[Lambda: pawgate-event-ingest]
   │ parse topic -> device_id, event_kind
   │ build sort_key -> "<ts>#<kind>#<type>"
   │ compute ttl_epoch -> now + 90 days
   ▼
[DynamoDB: pawgate_events]
   │ PutItem (device_id, ts_event, ...)
   ▼
event persisted ✅
```

## Costos (Free Tier)

| Componente | Uso/mes esperado | Free Tier | % usado |
|---|---|---|---|
| Lambda invocaciones | ~100K (eventos + sensors + telemetry) | 1M | 10% |
| Lambda compute | ~5K GB-segundos | 400K | 1.3% |
| IoT Rule executions | mismo que above | gratis | - |
| DDB writes | ~100K WCU | ~65M | <1% |
| CloudWatch Logs | ~50 MB | 5 GB | 1% |

**Total: $0/mes en free tier permanente.**

---

## Rework: Horarios = ventanas de desbloqueo + state machine del lock

El backend evolucionó para tratar a los horarios como **ventanas en que la puerta queda desbloqueada**. Fuera de cualquier horario activo, la puerta se bloquea sola. El `scheduleExecutor` mantiene una máquina de estados del lock por device en una tabla DDB nueva.

### Modelo de schedule (DDB `pawgate_schedules`)

| Campo | Tipo | Nota |
|---|---|---|
| device_id (PK) | string | |
| schedule_id (SK) | string | UUID generado por la API |
| nombre | string | min 3 caracteres |
| hora_inicio | string `"HH:MM"` | minutos siempre `00` o `30` |
| hora_fin | string `"HH:MM"` | idem |
| dias | list<string> | de `{L,M,X,J,V,S,D}` |
| activo | bool | |
| created_at, updated_at | ISO 8601 | |

### Tabla nueva: `pawgate_device_state`

PK = `device_id`. Item:
```
{ device_id, lock_state, updated_at }
lock_state ∈ {AUTO_BLOCKED, AUTO_UNBLOCKED, MANUAL_UNBLOCKED, MANUAL_BLOCKED}
```

Crear la tabla:
```bash
aws dynamodb create-table \
  --table-name pawgate_device_state \
  --attribute-definitions AttributeName=device_id,AttributeType=S \
  --key-schema AttributeName=device_id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region us-east-1
```

### State machine (scheduleExecutor cron 30min)

| Estado actual | In horario? | Próximo estado | Acción |
|---|---|---|---|
| AUTO_BLOCKED | sí | AUTO_UNBLOCKED | publish `cmd/unblock` |
| AUTO_BLOCKED | no | AUTO_BLOCKED | no-op |
| AUTO_UNBLOCKED | sí | AUTO_UNBLOCKED | no-op |
| AUTO_UNBLOCKED | no | AUTO_BLOCKED | publish `cmd/block` |
| MANUAL_UNBLOCKED | sí | AUTO_UNBLOCKED | no-op (override consumido) |
| MANUAL_UNBLOCKED | no | MANUAL_UNBLOCKED | no-op (override sigue activo) |
| MANUAL_BLOCKED | sí | MANUAL_BLOCKED | no-op (override sigue activo) |
| MANUAL_BLOCKED | no | AUTO_BLOCKED | no-op (override consumido) |

### Endpoints nuevos en `apiHandler`

| Método | Path | Descripción |
|---|---|---|
| GET    | `/devices/{id}/schedules` | listar |
| POST   | `/devices/{id}/schedules` | crear (body: nombre, hora_inicio, hora_fin, dias, activo) |
| PUT    | `/devices/{id}/schedules/{schedule_id}` | editar |
| DELETE | `/devices/{id}/schedules/{schedule_id}` | eliminar |
| GET    | `/devices/{id}/state` | `{lock_state, updated_at, currently_in_horario}` |
| POST   | `/devices/{id}/state/override-unblock` | manual override → `MANUAL_UNBLOCKED` + publish unblock |
| POST   | `/devices/{id}/state/override-block` | manual override → `MANUAL_BLOCKED` + publish block |

Hay que registrar estos paths en API Gateway con el Cognito Authorizer. El path `/devices/{id}/schedules/{schedule_id}` requiere dos path parameters; en la consola de API GW usás `{id}` y `{schedule_id}`.

### Re-deploy de las lambdas

Cuando cambies el código:
```bash
# apiHandler
cd Backend/lambdas/apiHandler
zip apiHandler.zip lambda_function.py
aws lambda update-function-code \
  --function-name pawgate-api-handler \
  --zip-file fileb://apiHandler.zip
cd ../../..

# scheduleExecutor
cd Backend/lambdas/scheduleExecutor
zip scheduleExecutor.zip lambda_function.py
aws lambda update-function-code \
  --function-name pawgate-schedule-executor \
  --zip-file fileb://scheduleExecutor.zip
cd ../../..
```

Si las env vars cambiaron (ahora apiHandler necesita `DEVICE_STATE_TABLE` y scheduleExecutor también):
```bash
aws lambda update-function-configuration \
  --function-name pawgate-api-handler \
  --environment "Variables={USER_POOL_ID=us-east-1_dJscv8ddq,APP_CLIENT_ID=s4bfmo11tfu81k5f4q7pm6ri8,EVENTS_TABLE=pawgate_events,SCHEDULES_TABLE=pawgate_schedules,DEVICE_STATE_TABLE=pawgate_device_state}"

aws lambda update-function-configuration \
  --function-name pawgate-schedule-executor \
  --environment "Variables={SCHEDULES_TABLE=pawgate_schedules,DEVICE_STATE_TABLE=pawgate_device_state}"
```

Si actualizaste las IAM policies (JSONs en `iam-roles/`):
```bash
aws iam put-role-policy \
  --role-name pawgate-api-handler-role \
  --policy-name pawgate-api-handler-policy \
  --policy-document file://Backend/iam-roles/lambda-api-handler-policy.json

aws iam put-role-policy \
  --role-name pawgate-schedule-executor-role \
  --policy-name pawgate-schedule-executor-policy \
  --policy-document file://Backend/iam-roles/lambda-schedule-executor-policy.json
```

