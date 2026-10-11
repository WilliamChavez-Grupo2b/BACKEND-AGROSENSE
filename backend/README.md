# BACKEND-AGROSENSE
## Cuentas

| Método y ruta | Respuesta |
|---|---|
| `POST /api/auth/login` `{email, password}` | 200 con el token · 401 credenciales incorrectas · 429 demasiados intentos (`Retry-After`) |
| `POST /api/auth/register` `{name, lastName, email, password}` | 201 con la cuenta · 400 con `fields` por campo · 409 si el correo ya existe |
| `GET /api/auth/check-user?email=` | 200 `{exists, available}` (`/api/auth/email-available` es el nombre anterior) |

Nombre y apellido: de 2 a 18 caracteres; contraseña: de 8 a 32. Los espacios cuentan.
Los orígenes del navegador permitidos se indican en `CORS_ALLOWED_ORIGINS`, separados por comas.

## Asistente de IA

Código en `src/main/java/com/agrosense/backend/ai` (`controller`, `service`, `dto`). Responde con Google
Gemini a partir de las cifras de la finca de quien pregunta. Las dos rutas exigen el token.

| Método y ruta | Respuesta |
|---|---|
| `POST /api/ai/chat` `{message, history: [{role: "user" o "assistant", text}]}` | 200 `{reply, source}` · 400 pregunta vacía o conversación no válida · 429 demasiadas consultas |
| `POST /api/ai/voice` formulario con `audio` (WebM, Ogg, MP4, WAV, MP3; hasta 3 MB) o `transcript` | 200 `{reply, transcript, source}` · 400 sin audio · 413 audio demasiado largo · 415 formato no admitido · 503 grabación sin texto y sin modelo que la escuche |

Cada pregunta viaja con las últimas lecturas de cada cultivo del usuario y los límites de ese cultivo.
`source` dice quién respondió: `model` es Gemini; `local` es el propio backend, que responde con esos
mismos datos (riego, alertas, temperatura, pH y estado de cada cultivo) cuando Gemini no está
configurado, falla o se queda sin cuota. Así el asistente sigue siendo útil sin llave.

| Variable | Descripción |
|---|---|
| `GEMINI_API_KEY` | Llave de la API de Gemini. Sin valor por defecto; sin ella el asistente responde solo con los datos de la finca |
| `GEMINI_MODEL` | Modelo (por defecto `gemini-flash-latest`, el alias del Flash vigente) |
| `GEMINI_TIMEOUT_MS`, `GEMINI_MAX_OUTPUT_TOKENS` | Tiempo de espera (20000) y tamaño máximo de la respuesta (1024) |
| `AI_REQUESTS_PER_MINUTE` | Preguntas por minuto y usuario (10); `0` quita el límite |
| `AI_LOCAL_ANSWERS` | `false` para rechazar las preguntas con 503 cuando el modelo no está disponible, en vez de responder con los datos de la finca (por defecto `true`) |

## Producción

Render, `https://backend-agrosense-u5mz.onrender.com` (`/api/health` responde `{"status":"UP"}`). Variables
del servicio: `DATABASE_URL` en formato JDBC (`jdbc:postgresql://…`), `DATABASE_USERNAME`,
`DATABASE_PASSWORD`, `JWT_SECRET` (al menos 64 bytes; sin él la aplicación no arranca),
`CORS_ALLOWED_ORIGINS` y `GEMINI_API_KEY`.
