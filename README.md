# BACKEND-AGROSENSE

## Build the backend image

The Maven project is at the repository root. Run from there:

```powershell
docker compose build backend
```

Alternatively, build directly:

```powershell
docker build -t agrosense-backend .
```

## Database files

The schema, the sample data and `datasource.properties` live in the
[BASE-DE-DATOS-AGROSENSE](https://github.com/ssantivr/BASE-DE-DATOS-AGROSENSE) repository, not here.
For local runs and for the tests, clone it next to this repository as `database`
(`git clone https://github.com/ssantivr/BASE-DE-DATOS-AGROSENSE ../database`), or point `DATABASE_DIR`
at a copy of it.

## Deploy on Render

Create a **Web Service** from this repository and configure it to use Docker:

| Render setting | Value |
| --- | --- |
| Root Directory | Leave blank (the repository root) |
| Runtime | Docker |
| Dockerfile Path | `Dockerfile` |
| Health Check Path | `/api/health` |

Keep the repository root as the build context. Render builds the Dockerfile
directly; it does not use the local `compose.yaml`.

> **When this layout is merged into `main`:** the existing Render service was
> created while the project was inside `agrosense/`, so its **Dockerfile Path**
> is still `agrosense/Dockerfile`. Change it to `Dockerfile` in the service's
> **Settings → Build & Deploy** before or right after merging; until then every
> deploy from `main` fails because that file no longer exists.

### Configure the service

Add these environment variables in Render's **Environment** settings:

| Variable | Value |
| --- | --- |
| `JWT_SECRET` | A private, randomly generated secret of at least 64 bytes. For example, generate one with `openssl rand -base64 64`. |
| `SPRING_DATASOURCE_URL` | JDBC URL for your PostgreSQL database, for example `jdbc:postgresql://<host>:5432/<database>` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password |
| `CORS_ALLOWED_ORIGINS` | Your frontend's public origin, such as `https://your-frontend.example.com` |
| `GEMINI_API_KEY` | Google Gemini API key for the AI assistant. Optional: without it the assistant answers from the farm's own readings instead of the model. |

Use the internal connection details if the database is hosted on Render in the
same region. Do not commit database credentials or `JWT_SECRET` to the
repository. The service reads Render's `PORT` automatically (falling back to
`9000` for local Docker runs); do not set `PORT` yourself.

The image carries no `datasource.properties` file; it is optional.
Spring Boot can take the datasource settings from the environment variables
above instead. The PostgreSQL database must already have the application's
schema before the service starts; initialize it using your database schema
scripts before deploying. To create the demo account and sample data at
startup, also set `DEMO_DATA_ENABLED=true` and a strong `DEMO_PASSWORD`.

After the first deploy, open `https://<your-service>.onrender.com/api/health`.
The health check should return `{"status":"UP"}`.

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
`source` dice quién respondió: `model` es el modelo de lenguaje; `local` es el propio backend, que responde con esos
mismos datos (riego, alertas, temperatura, pH y estado de cada cultivo) cuando el modelo no está
configurado, falla o se queda sin cuota. Así el asistente sigue siendo útil sin llave.

### Qué modelo responde

El asistente funciona con cualquiera de estos dos tipos de proveedor; ninguna llave se escribe en el código.

| Variable | Descripción |
|---|---|
| `AI_CHAT_API_KEY` | Llave de un proveedor que hable la API de OpenAI (Groq, OpenRouter, Mistral, OpenAI…). Si está definida, responde ese proveedor y no Gemini |
| `AI_CHAT_BASE_URL` | Dirección de ese proveedor (por defecto la de Groq, `https://api.groq.com/openai/v1`) |
| `AI_CHAT_MODEL` | Modelo que responde las preguntas (por defecto `qwen/qwen3.8-27b`, elegido el 11 de octubre de 2026 entre los que Groq ofrecía: el más rápido y el que mejor seguía las instrucciones) |
| `AI_TRANSCRIPTION_MODEL` | Modelo que convierte en texto los mensajes de voz (por defecto `whisper-large-v3-turbo`). Vacío si el proveedor no ofrece transcripción: la ventana pide entonces escribir la pregunta |

Con este tipo de proveedor un mensaje de voz se resuelve en dos pasos (transcribir y responder); con
Gemini, en uno. Los nombres de modelo cambian con el tiempo: la lista vigente de un proveedor está en su
ruta `/models`. Un modelo que «razona» antes de responder gasta en eso el tamaño máximo de la respuesta
y puede dejarla cortada; con esos conviene subir `GEMINI_MAX_OUTPUT_TOKENS`.

| Variable | Descripción |
|---|---|
| `GEMINI_API_KEY` | Llave de la API de Gemini, para cuando no hay `AI_CHAT_API_KEY`. Sin ninguna de las dos el asistente responde solo con los datos de la finca |
| `GEMINI_MODEL` | Modelo (por defecto `gemini-flash-latest`, el alias del Flash vigente) |
| `GEMINI_TIMEOUT_MS`, `GEMINI_MAX_OUTPUT_TOKENS` | Tiempo de espera (20000) y tamaño máximo de la respuesta (1024), con cualquiera de los dos proveedores |
| `AI_REQUESTS_PER_MINUTE` | Preguntas por minuto y usuario (10); `0` quita el límite |
| `AI_LOCAL_ANSWERS` | `false` para rechazar las preguntas con 503 cuando el modelo no está disponible, en vez de responder con los datos de la finca (por defecto `true`) |
