# BACKEND-AGROSENSE
## Cuentas

| Método y ruta | Respuesta |
|---|---|
| `POST /api/auth/login` `{email, password}` | 200 con el token · 401 credenciales incorrectas · 429 demasiados intentos (`Retry-After`) |
| `POST /api/auth/register` `{name, lastName, email, password}` | 201 con la cuenta · 400 con `fields` por campo · 409 si el correo ya existe |
| `GET /api/auth/check-user?email=` | 200 `{exists, available}` (`/api/auth/email-available` es el nombre anterior) |

Nombre y apellido: de 2 a 18 caracteres; contraseña: de 8 a 32. Los espacios cuentan.
Los orígenes del navegador permitidos se indican en `CORS_ALLOWED_ORIGINS`, separados por comas.

## Dispositivos y eventos en vivo

Las Raspberry Pi envían sus eventos al servicio de eventos (`event-service/` del workspace), que los reenvía
aquí. MQTT queda apagado por defecto (`MQTT_ENABLED=false`) y su código se conserva.

| Método y ruta | Quién la llama | Respuesta |
|---|---|---|
| `POST /api/devices` `{cropId, deviceCode}` | Un usuario `ADMIN` o `FARMER`, sobre un cultivo suyo | 201 con el dispositivo y `deviceKey`, la única vez que se muestra · 404 cultivo ajeno · 409 código repetido |
| `POST /api/internal/events` `{device_id, type, ts, data}` con `X-Internal-Key` y `X-Device-Key` | Solo el servicio de eventos; fuera del token de usuarios | 200 `{ownerEmail, event}` · 401 sin la llave interna, o dispositivo o llave incorrectos · 400 evento no válido, con el campo a corregir |
| `GET /api/events/token` | Un usuario con su token | 200 `{token, expiresInSeconds: 300}` para abrir su canal en vivo · 503 sin `EVENTS_STREAM_SECRET` |

- `type: "reading"`: `data` lleva las medidas, por ejemplo `{"soil_moisture": 31.5, "air_temperature": 24, "ph": 6.4}`
  (los nombres son los tipos de sensor en minúsculas). Cada una se guarda en el sensor activo de ese tipo del
  cultivo del dispositivo, con la misma lógica que REST y MQTT: validación, alertas por rango y aviso por
  WebSocket. Si el cultivo tiene varios sensores activos de ese tipo, el evento debe decir cuál:
  `{"soil_moisture": {"value": 31.5, "sensor_code": "AS-001"}}`; si no, responde 400 explicándolo. Un evento
  con una medida rechazada no guarda ninguna.
- `type: "alert"`: `data` es `{alert_type, message, severity?, value?}` y se guarda en el cultivo, con una
  alerta abierta como máximo por cultivo y tipo.
- `ts` es opcional (ISO-8601 o segundos desde 1970); más de 5 minutos en el futuro se rechaza.
- La llave del dispositivo se compara con su SHA-256 guardado, en tiempo constante.

| Variable | Descripción |
|---|---|
| `EVENTS_INTERNAL_KEY` | Llave que el servicio de eventos envía en `X-Internal-Key`. Sin ella, la ruta interna rechaza todo |
| `EVENTS_STREAM_SECRET` | Secreto de al menos 32 bytes, compartido solo con el servicio de eventos, que firma los tokens del canal en vivo (HS256, 5 minutos) |
| `SEED_DEVICE_KEY` | Llave que recibe al arrancar el dispositivo de ejemplo `RPI-DEMO-01`. Solo para bases locales y de prueba |

## Asistente de IA

Código en `src/main/java/com/agrosense/backend/ai` (`controller`, `service`, `dto`). Responde preguntas
abiertas sobre la finca de quien pregunta, sobre agricultura en general y sobre cómo funciona AgroSense, y
redirige con amabilidad las que no tienen relación con nada de eso. Las dos rutas exigen el token.

| Método y ruta | Respuesta |
|---|---|
| `POST /api/ai/chat` `{message, history: [{role: "user" o "assistant", text}]}` | 200 `{reply, source}` · 400 pregunta vacía o conversación no válida · 429 demasiadas consultas del usuario · 503 modelo no disponible con `AI_LOCAL_ANSWERS=false` |
| `POST /api/ai/voice` formulario con `audio` (WebM, Ogg, MP4, WAV, MP3; hasta 3 MB) o `transcript` | 200 `{reply, transcript, source}` · 400 sin audio · 413 audio demasiado largo · 415 formato no admitido · 503 grabación sin un modelo disponible que la escuche |

`source` dice quién respondió: `model` es el modelo de lenguaje; `local` es el propio backend, que responde
con los datos de la finca (riego, alertas, temperatura, pH y estado de cada cultivo) cuando el modelo no está
configurado, falla o se queda sin cuota. Así el asistente sigue siendo útil sin llave. El 429 se reserva
para el límite de preguntas por minuto del usuario; un proveedor sin cuota nunca se traduce en 429.

### Qué recibe el modelo

- **Instrucciones**: `src/main/resources/ai/prompts/system-es.txt` (con el servicio de IA, las de su
  `prompts/system-es.txt`). Se editan ahí, sin tocar el código; incluyen
  lo que el asistente puede decir sobre la aplicación (menú, registro de sensores, alertas y sus colores, riego).
- **Datos de la finca**, solo del usuario autenticado y en texto compacto: fecha y hora actual; totales; por
  cultivo (hasta 8), variedad, etapa, fecha de siembra, finca y ubicación, últimas lecturas de humedad,
  temperatura y pH con el rango del cultivo; hasta 5 alertas abiertas y las 3 últimas atendidas, con tipo,
  severidad, valor y fecha; y los 5 últimos riegos. Lo que no está registrado se omite; el texto se corta en
  una línea completa a partir de 4000 caracteres.
- **Conversación**: los últimos 8 mensajes del historial (la petición puede traer hasta 12) y la pregunta.

### A través del servicio de IA

Con `AI_CHAT_VIA_SERVICE=true` y `AI_SERVICE_URL` definida, el backend no llama a ningún proveedor: envía la
pregunta, los últimos 8 mensajes y la descripción de la finca (armada aquí, con los datos del usuario
autenticado) al servicio de IA (`IA-AGROSENSE`, `POST /chat` y `POST /voice`), que responde con su propio
proveedor. Los contratos de `/api/ai/chat` y `/api/ai/voice`, el token y el límite por usuario no cambian.

- Si el servicio falla, no responde, se queda sin cuota o no tiene proveedor (503), la pregunta escrita se
  responde en modo local (`source: local`), o con 503 si `AI_LOCAL_ANSWERS=false`. Una negativa del modelo
  (422) llega tal cual.
- Cada llamada lleva `X-Internal-Key` con `AI_SERVICE_KEY` y, si está definido, el `AI_SERVICE_TOKEN` que ya
  usaban las predicciones.
- `AI_CHAT_VIA_SERVICE` es `false` por defecto: `AI_SERVICE_URL` ya activa las predicciones de riego, y el chat
  no debe cambiar de proveedor sin decidirlo.
- El `.env.example` de la raíz del workspace trae `AI_CHAT_VIA_SERVICE=true` porque es el archivo de
  `docker compose`, que siempre levanta el servicio de IA junto al backend. Fuera de compose (Render,
  `mvnw spring-boot:run`) esa copia no se lee y el backend sigue con `false` mientras no se defina la variable.

| Variable | Descripción |
|---|---|
| `AI_CHAT_VIA_SERVICE` | `true` para que el asistente pase por el servicio de IA (por defecto `false`) |
| `AI_SERVICE_URL` | Dirección del servicio de IA; también activa las predicciones de riego |
| `AI_SERVICE_KEY` | Llave interna del servicio (`INTERNAL_API_KEY` allí), enviada como `X-Internal-Key` |
| `AI_SERVICE_TOKEN` | Token anterior (`Authorization: Bearer`), aceptado por el servicio durante la migración |
| `AI_SERVICE_TIMEOUT_MS` | Cuánto se espera al servicio (30000), para chat y predicciones. En el plan gratuito de Render el servicio se duerme y la primera petición tarda más |

### Qué modelo responde sin el servicio (obsoleto)

> Esta ruta queda como respaldo para un backend sin servicio de IA y se retirará cuando el servicio sea la
> única. Los proveedores ya viven también en `IA-AGROSENSE`; los cambios nuevos se hacen allí.

Sin `AI_CHAT_VIA_SERVICE`, responde el primer proveedor que tenga llave, en este orden:

1. `AI_CHAT_API_KEY`: un proveedor que hable la API de OpenAI (Groq, OpenRouter, Mistral, OpenAI…).
2. `ANTHROPIC_API_KEY`: Claude, de Anthropic.
3. `GEMINI_API_KEY`: Google Gemini.
4. Ninguna llave: respuestas `local` con los datos de la finca (o 503 con `AI_LOCAL_ANSWERS=false`).

Si el proveedor elegido falla, se agota su cuota o tarda demasiado, la pregunta se responde en modo local; no
se prueba el siguiente proveedor de la lista. Ninguna llave se escribe en el código ni en archivos del repositorio.

| Variable | Descripción |
|---|---|
| `AI_CHAT_API_KEY` | Llave de un proveedor compatible con la API de OpenAI |
| `AI_CHAT_BASE_URL` | Dirección de ese proveedor (por defecto la de Groq, `https://api.groq.com/openai/v1`) |
| `AI_CHAT_MODEL` | Modelo que responde (por defecto `qwen/qwen3.8-27b`, elegido el 11 de octubre de 2026 entre los que Groq ofrecía). Admite varios separados por comas: si uno se queda sin cuota o deja de existir, responde el siguiente |
| `AI_TRANSCRIPTION_MODEL` | Modelo que convierte en texto los mensajes de voz (por defecto `whisper-large-v3-turbo`). Vacío si el proveedor no ofrece transcripción: la ventana pide entonces escribir la pregunta |
| `ANTHROPIC_API_KEY` | Llave de la API de Anthropic, para cuando no hay `AI_CHAT_API_KEY` |
| `ANTHROPIC_MODEL` | Modelo de Claude (por defecto `claude-haiku-5-5`; `claude-sonnet-5-5` responde mejor, a mayor precio) |
| `ANTHROPIC_EFFORT` | Cuánto piensa el modelo antes de responder: `low` (por defecto), `medium` o `high`. Vacío para un modelo que no admite este ajuste |
| `GEMINI_API_KEY` | Llave de la API de Gemini, para cuando no hay ninguna de las anteriores |
| `GEMINI_MODEL` | Modelo (por defecto `gemini-flash-latest`, el alias del Flash vigente) |
| `GEMINI_TIMEOUT_MS`, `GEMINI_MAX_OUTPUT_TOKENS` | Tiempo de espera (20000) y tamaño máximo de la respuesta (2048), con cualquiera de los proveedores |
| `AI_REQUESTS_PER_MINUTE` | Preguntas por minuto y usuario (10); `0` quita el límite |
| `AI_LOCAL_ANSWERS` | `false` para responder 503 cuando el modelo no está configurado o no está disponible, en vez de responder con los datos de la finca (por defecto `true`) |

Mensajes de voz: el proveedor compatible con OpenAI los resuelve en dos pasos (transcribir y responder) y
Gemini en uno. Claude no transcribe audio: si el navegador ya convirtió la voz en texto, responde a ese texto;
si llega solo la grabación, se pide escribir la pregunta (503). Los nombres de modelo cambian con el tiempo:
la lista vigente de un proveedor compatible con OpenAI está en su ruta `/models`. Un modelo que «razona» antes
de responder gasta en eso parte del tamaño máximo de la respuesta y puede dejarla cortada; con esos conviene
subir `GEMINI_MAX_OUTPUT_TOKENS`.

Las 15 preguntas de prueba del asistente y sus resultados están en [`docs/PRUEBAS-IA.md`](docs/PRUEBAS-IA.md).

## Producción

Render, `https://backend-agrosense-u5mz.onrender.com` (`/api/health` responde `{"status":"UP"}`). Variables
del servicio: `DATABASE_URL` en formato JDBC (`jdbc:postgresql://…`), `DATABASE_USERNAME`,
`DATABASE_PASSWORD`, `JWT_SECRET` (al menos 64 bytes; sin él la aplicación no arranca),
`CORS_ALLOWED_ORIGINS` y `GEMINI_API_KEY`.
