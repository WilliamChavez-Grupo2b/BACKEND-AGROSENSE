# AgroSense · Base de datos

Todo lo relacionado con la base de datos de AgroSense está en esta carpeta: el esquema, los datos de
ejemplo, el script de reinicio y la configuración de conexión. El backend y el frontend no crean ni
modifican tablas ni contienen datos de ejemplo: leen estos archivos y solo validan las tablas al arrancar
(`ddl-auto=validate`).

## Contenido

| Archivo         | Descripción                                                                          |
|-----------------|--------------------------------------------------------------------------------------|
| `schema.sql`    | Tablas, claves, restricciones e índices. Fuente de verdad del esquema.               |
| `seed_demo.sql` | Datos de ejemplo: 1 usuario, 2 fincas, 3 cultivos, 6 sensores, 120 lecturas, 4 alertas, 4 riegos y 2 predicciones. |
| `reset.sql`     | **Destructivo.** Borra todas las tablas y sus datos. Solo para desarrollo.           |
| `.env.example`  | Variables de conexión que leen el backend y el frontend.                             |

`schema.sql` y `seed_demo.sql` son idempotentes: se pueden ejecutar varias veces sin duplicar nada.

## Modelo

```
users ─┬─< estates ─< crops ─┬─< sensors ─< sensor_readings
       │                     ├─< alerts        (id_sensor opcional)
       │                     ├─< irrigations   (activated_by → users, opcional)
       │                     └─< ai_predictions
       └───────────────────────── irrigations.activated_by
```

| Tabla             | Contenido                                                              |
|-------------------|------------------------------------------------------------------------|
| `users`           | Cuentas. `email` único; `password_hash` con BCrypt.                    |
| `estates`         | Fincas o parcelas de un usuario, con coordenadas opcionales.           |
| `crops`           | Cultivos de una finca y sus rangos aceptables de humedad, temperatura y pH. |
| `sensors`         | Sensores instalados en un cultivo. `sensor_code` único.                |
| `sensor_readings` | Lecturas de cada sensor.                                               |
| `alerts`          | Alertas de un cultivo, con severidad y estado de atención.             |
| `irrigations`     | Riegos automáticos y manuales.                                         |
| `ai_predictions`  | Predicciones del servicio de IA (solo las usa el backend).             |

- Borrar un usuario, una finca, un cultivo o un sensor borra en cascada lo que depende de él.
- Borrar un sensor conserva sus alertas sin la referencia (`ON DELETE SET NULL`); igual con el usuario que
  inició un riego.
- Restricciones `CHECK`: valores válidos de cada enumeración, coordenadas en rango y completas, mínimos
  menores que máximos, duraciones y áreas positivas.
- Índices en todas las claves foráneas y en las consultas por fecha.

## El usuario de ejemplo y su contraseña

`seed_demo.sql` crea `demo@agrosense.co` con un hash que no corresponde a ninguna contraseña, así que
nadie puede entrar con esa cuenta solo por ejecutar el script. La contraseña la pone la aplicación:

- **Backend, perfil `seed`:** usa `SEED_USER_PASSWORD` (obligatoria).
- **Frontend, perfil `demo`:** usa `DEMO_PASSWORD` (por defecto `agrosense`, sobre una base en memoria).

Así este archivo no guarda ninguna contraseña ni un hash utilizable.

## Crear la base de datos en PostgreSQL

1. Crea la base de datos vacía (una sola vez):

   ```sql
   CREATE DATABASE agrosense_db;
   ```

2. Crea las tablas y carga los datos de una de estas formas:

   - **Desde la aplicación** (no requiere `psql`). El perfil `seed` del backend ejecuta `schema.sql`,
     luego `seed_demo.sql`, y asigna la contraseña del usuario de ejemplo:

     ```powershell
     # en backend\agrosense
     $env:DATABASE_PASSWORD = '<tu contraseña de postgres>'
     $env:SEED_USER_PASSWORD = '<contraseña para demo@agrosense.co>'
     $env:SPRING_PROFILES_ACTIVE = 'seed'
     .\mvnw.cmd spring-boot:run
     ```

     Para crear solo las tablas, sin datos de ejemplo, usa `SQL_INIT_MODE=always` sin el perfil `seed`.

   - **A mano:** ejecuta `schema.sql` y, si quieres datos de ejemplo, `seed_demo.sql` en la herramienta
     de consultas de pgAdmin, o con `psql -U postgres -d agrosense_db -f schema.sql`.

3. Para empezar de cero en desarrollo: `reset.sql` y de nuevo `schema.sql`.

Las aplicaciones buscan estos archivos con rutas relativas a su carpeta (`../database` desde `frontend`,
`../../database` desde `backend/agrosense`). Si las ejecutas desde otro lugar, indica las rutas con
`SCHEMA_LOCATION` y `SEED_LOCATION`.

## Variables de conexión

| Variable            | Por defecto                                      |
|---------------------|--------------------------------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://localhost:5432/agrosense_db`  |
| `DATABASE_USERNAME` | `postgres`                                       |
| `DATABASE_PASSWORD` | sin valor por defecto (obligatoria)              |
| `SQL_INIT_MODE`     | `never`; con `always` se aplica `schema.sql` al arrancar |

## Verificación

- `DatabaseSchemaTests` (backend y frontend) ejecuta `schema.sql` y hace que Hibernate valide todas las
  entidades contra él; también comprueba que las restricciones rechazan datos inválidos.
- `DataSeederTests` (backend) arranca con el perfil `seed`, cuenta las filas cargadas por `seed_demo.sql`
  y vuelve a ejecutar los dos scripts para confirmar que no duplican nada.
- La demo del frontend y sus 35 pruebas web usan estos mismos dos archivos.

Estas pruebas corren sobre H2 en modo de compatibilidad con PostgreSQL. **Falta ejecutarlo contra un
servidor PostgreSQL real.**

## Estado del PostgreSQL local de este equipo

El servicio PostgreSQL 18 aparece en ejecución, pero rechaza todas las conexiones con el mensaje «no se
pudo lanzar el nuevo proceso para la conexión». El registro de Integridad de código de Windows (eventos
3033 y 3077) indica que la directiva de Control de aplicaciones impide a `postgres.exe` lanzar el proceso
que atiende cada conexión. Mientras siga así, ni las aplicaciones ni pgAdmin pueden conectarse a ese
servidor. Alternativas: un PostgreSQL en la nube o revisar esa directiva de Windows.
