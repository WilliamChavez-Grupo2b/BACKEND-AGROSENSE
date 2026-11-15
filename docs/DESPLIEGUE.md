# Despliegue de `feature/arquitectura-servicios` en Render

Pasos en orden para llevar a producción las ramas `feature/arquitectura-servicios` de los cuatro
repositorios. Ningún valor secreto aparece aquí: cada uno se genera en el momento y se escribe solo en el
panel de Render o de Vercel. Para generar un secreto:

```bash
python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Servicios actuales: backend `BACKEND-AGROSENSE` (`https://backend-agrosense-u5mz.onrender.com`), IA
`ia-agrosense` (`https://ia-agrosense.onrender.com`), base de datos en Neon y frontend en Vercel
(`https://agrosense-frontend-iota.vercel.app`). Render despliega solo el backend y la IA, cada vez que cambia
el `main` de su repositorio, así que **fusionar la pull request equivale a desplegar**.

## 0. Antes de empezar

- [ ] Las cuatro pull requests están revisadas, pero **ninguna se ha fusionado todavía**.
- [ ] La PR del frontend va sobre `feat/assistant-local-answers`: fusiona primero esa rama o cambia la base
      de la PR a `main`, porque sus dos commits ya forman parte de la entrega.
- [ ] Ten a mano la dirección de conexión de Neon (rama de producción, host directo, sin pooler).
- [ ] Despierta los servicios gratuitos unos minutos antes (cada uno tarda de 1 a 3 minutos en arrancar):
      `curl -s --max-time 180 https://backend-agrosense-u5mz.onrender.com/api/health` y
      `curl -s --max-time 180 https://ia-agrosense.onrender.com/health`.

## 1. Base de datos: migración de `devices` (antes de tocar el backend)

El backend nuevo valida sus tablas al arrancar y **no arranca sin `devices`**. La migración es idempotente.

1. Fusiona la PR de `BASE-DE-DATOS-AGROSENSE` (solo archivos SQL y README; no despliega nada).
2. Ejecuta la migración contra la base de producción:

   ```bash
   psql "postgresql://<usuario>:<contraseña>@<host>/<base>?sslmode=require" -f migrations/001_devices.sql
   ```

3. Comprueba que existe la tabla:

   ```sql
   SELECT column_name FROM information_schema.columns WHERE table_name = 'devices' ORDER BY ordinal_position;
   ```

   Deben aparecer `id_device`, `id_crop`, `device_code`, `key_hash`, `active`, `last_event_at` y `created_at`.

4. No ejecutes `seed_demo.sql` ni `reset.sql` en producción. El dispositivo demo (`ESP32-PLANTICAS`) solo se crea
   con los datos de ejemplo, y su `key_hash` `DISABLED` no sirve como llave.

## 2. IA (`ia-agrosense`): variables y despliegue

Va antes que el backend: la versión nueva sigue aceptando el `AI_SERVICE_TOKEN` actual, así que el backend
que hoy está en producción sigue funcionando mientras tanto.

1. En Render → `ia-agrosense` → Environment, añade:

   | Variable | Valor |
   |---|---|
   | `INTERNAL_API_KEY` | secreto nuevo; luego irá también al backend como `AI_SERVICE_KEY` |
   | `AI_SERVICE_TOKEN` | **sin cambios**: el valor que ya tiene (se acepta durante la migración) |
   | `AI_CHAT_API_KEY` | la llave del proveedor compatible con OpenAI (Groq) que hoy usa el backend |
   | `AI_CHAT_BASE_URL` | `https://api.groq.com/openai/v1` (es el valor por defecto) |
   | `AI_CHAT_MODEL` | `qwen/qwen3.8-27b` u otra lista separada por comas; el siguiente responde si uno se queda sin cuota |
   | `AI_TRANSCRIPTION_MODEL` | `whisper-large-v3-turbo` |
   | `AI_TIMEOUT_MS` | `20000` |
   | `AI_MAX_OUTPUT_TOKENS` | `2048` |

   Opcionales: `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`, `ANTHROPIC_EFFORT`, `GEMINI_API_KEY` y `GEMINI_MODEL`.
   Responde el primer proveedor que tenga llave, en este orden: compatible con OpenAI, Claude, Gemini.

2. Fusiona la PR de `IA-AGROSENSE`. Render construye el `Dockerfile`, que ahora instala `requirements.txt`.
3. Comprueba: `GET https://ia-agrosense.onrender.com/health` responde 200. Las predicciones del backend
   actual siguen funcionando, porque usan el token anterior.

## 3. Backend (`BACKEND-AGROSENSE`): variables y despliegue

1. Confirma que el paso 1 está hecho (la tabla `devices` existe).
2. En Render → `BACKEND-AGROSENSE` → Environment, añade:

   | Variable | Valor |
   |---|---|
   | `AI_SERVICE_KEY` | igual a `INTERNAL_API_KEY` de la IA |
   | `AI_SERVICE_TIMEOUT_MS` | `60000` (ya está; la IA gratuita puede estar dormida) |
   | `AI_SERVICE_MAX_ATTEMPTS` | `2` (ya está) |
   | `AI_CHAT_VIA_SERVICE` | `false` en este despliegue; pásalo a `true` en el paso 6 |
   | `EVENTS_INTERNAL_KEY` | secreto nuevo; el manejador de eventos usará el mismo valor |
   | `EVENTS_STREAM_SECRET` | secreto nuevo **de al menos 32 bytes** (con menos, el backend no arranca); el manejador de eventos usará el mismo valor |

   No pongas `SEED_DEVICE_KEY` ni `SEED_USER_PASSWORD` en producción. Las variables actuales (`DATABASE_*`,
   `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `AI_SERVICE_URL`, `AI_SERVICE_TOKEN`, `AI_CHAT_API_KEY`,
   `AI_CHAT_MODEL`, `LOGIN_MAX_ATTEMPTS_PER_ADDRESS`) se mantienen.
3. En Settings → Health Check Path pon `/api/health` (hoy no tiene ninguna).
4. Fusiona la PR del backend. Render la despliega unos 4 minutos después, sin cortar el servicio.
5. Comprueba:
   - `GET /api/health` responde `{"status":"UP"}`.
   - Con un usuario autenticado, `GET /api/events/token` responde 200 con un token.
   - `POST /api/devices` (con un cultivo propio) responde 201 y entrega la llave **una sola vez**. Guárdala
     para el ESP32.
   - En los registros del arranque no aparece `EVENTS_INTERNAL_KEY is empty`.

## 4. Manejador de eventos (servicio nuevo)

**Pendiente de decidir:** `event-service/` no tiene repositorio propio todavía y Render solo despliega desde
un repositorio. Crea uno (por ejemplo `EVENTOS-AGROSENSE`) con el contenido de `event-service/` en la raíz.

1. Render → New → Blueprint → ese repositorio. `render.yaml` crea `eventos-agrosense` (Docker, plan
   gratuito, `/health`, un solo proceso: el canal en vivo vive en memoria).
2. Variables:

   | Variable | Valor |
   |---|---|
   | `BACKEND_URL` | `https://backend-agrosense-u5mz.onrender.com` |
   | `EVENTS_INTERNAL_KEY` | igual que en el backend |
   | `EVENTS_STREAM_SECRET` | igual que en el backend |
   | `EVENTS_ALLOWED_ORIGINS` | `https://agrosense-frontend-iota.vercel.app` |
   | `BACKEND_TIMEOUT_MS` | `60000` |

3. Comprueba: `GET https://<eventos>.onrender.com/health` responde 200, y un `POST /events` sin
   `X-Device-Key` responde 401.

## 5. Frontend (Vercel, no Render)

1. En el proyecto `agrosense-frontend`, añade `EVENTS_PUBLIC_URL` con la dirección pública del manejador de
   eventos. `BACKEND_URL` y `BACKEND_JWT_SECRET` se mantienen.
2. Fusiona la PR del frontend (`main` está protegido: solo entra por pull request) y despliega con
   `vercel deploy --prod` desde la raíz del workspace, como hasta ahora.
3. Comprueba: `GET /health` responde 200 sin sesión; después de iniciar sesión, la página de sensores
   muestra el indicador de datos en vivo.

## 6. Pasar el chat por la IA (opcional, al final)

1. En el backend, `AI_CHAT_VIA_SERVICE=true` y vuelve a desplegar (Manual Deploy).
2. Pregunta algo en el chat: la respuesta debe llegar con `source: model`. Si llega con `source: local`, la
   IA no respondió a tiempo o no tiene llave de proveedor: revisa los registros de `ia-agrosense`.
3. Para volver atrás basta con `AI_CHAT_VIA_SERVICE=false` y desplegar de nuevo; el backend vuelve a sus
   propios proveedores.

## 7. ESP32

En `firmware/agrosense/arduino_secrets.h` (copia de `arduino_secrets.example.h`, fuera del repositorio):
`SECRET_EVENTS_HOST` con el host del manejador de eventos (`<eventos>.onrender.com`), `SECRET_EVENTS_PORT` 443,
`SECRET_EVENTS_TLS` `true`, `SECRET_DEVICE_ID` con el código registrado en el paso 3 y `SECRET_DEVICE_KEY` con la llave
que devolvió `POST /api/devices`. Opcional: `SECRET_EVENTS_CA_CERT` con el certificado raíz del host para que HTTPS
compruebe el servidor. Después sube `agrosense.ino` a la placa; no hace falta ningún despliegue. Calibra antes el suelo
(`firmware/README.md`).

Para comprobar el tramo sin placa: `python tools/e2e/run_e2e.py` con `EVENTS_URL` y `BACKEND_URL` de producción no es
adecuado, porque registra un riego; úsalo solo contra una base de desarrollo.

## Vuelta atrás

- **Backend:** Render → Deploys → *Rollback* al despliegue anterior. La tabla `devices` puede quedarse: la
  versión anterior no la usa.
- **IA:** *Rollback* en Render; el backend la sigue llamando con `AI_SERVICE_TOKEN`.
- **Manejador de eventos:** basta con suspender el servicio; sin él solo faltan los datos en vivo.
