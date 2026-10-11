# BACKEND-AGROSENSE

## Build the backend image

Run from the repository root so Docker can access both `agrosense/` and `database/`:

```powershell
docker compose build backend
```

Alternatively, build directly:

```powershell
docker build -f agrosense/Dockerfile -t agrosense-backend .
```

If your current directory is `agrosense\`, use `..` as the build context:

```powershell
docker build -f Dockerfile -t agrosense-backend ..
```

## Deploy on Render

Create a **Web Service** from this repository and configure it to use Docker:

| Render setting | Value |
| --- | --- |
| Root Directory | Leave blank (the repository root) |
| Runtime | Docker |
| Dockerfile Path | `agrosense/Dockerfile` |
| Health Check Path | `/api/health` |

Keep the repository root as the build context. The Dockerfile copies files from
both `agrosense/` and `database/`, so setting the root directory to `agrosense/`
will break the build. Render builds the Dockerfile directly; it does not use the
local `compose.yaml`.

### Configure the service

Add these environment variables in Render's **Environment** settings:

| Variable | Value |
| --- | --- |
| `JWT_SECRET` | A private, randomly generated secret of at least 64 bytes. For example, generate one with `openssl rand -base64 64`. |
| `SPRING_DATASOURCE_URL` | JDBC URL for your PostgreSQL database, for example `jdbc:postgresql://<host>:5432/<database>` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password |
| `CORS_ALLOWED_ORIGINS` | Your frontend's public origin, such as `https://your-frontend.example.com` |

Use the internal connection details if the database is hosted on Render in the
same region. Do not commit database credentials or `JWT_SECRET` to the
repository. The service reads Render's `PORT` automatically (falling back to
`9000` for local Docker runs); do not set `PORT` yourself.

The local `database/datasource.properties` file is optional in the container.
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
