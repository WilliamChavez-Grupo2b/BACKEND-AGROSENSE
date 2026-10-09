# AgroSense · Base de datos

Esquema PostgreSQL de AgroSense. `schema.sql` es la única fuente de verdad de las tablas: el backend y el
frontend no crean ni modifican tablas, solo las validan al arrancar (`ddl-auto=validate`).

## Contenido

| Archivo      | Descripción                                                                 |
|--------------|-----------------------------------------------------------------------------|
| `schema.sql` | Tablas, claves, restricciones e índices. Idempotente: se puede ejecutar varias veces. |

## Modelo

```
users ─┬─< estates ─< crops ─┬─< sensors ─< sensor_readings
       │                     ├─< alerts        (id_sensor opcional)
       │                     ├─< irrigations   (activated_by → users, opcional)
       │                     └─< ai_predictions
       └───────────────────────── irrigations.activated_by
```

- Borrar un usuario, una finca, un cultivo o un sensor borra en cascada lo que depende de él.
- Borrar un sensor deja sus alertas, sin la referencia (`ON DELETE SET NULL`); igual con el usuario que
  inició un riego.
- Restricciones `CHECK`: valores válidos de cada enumeración, coordenadas en rango y completas, mínimos
  menores que máximos, duraciones y áreas positivas.
- Índices en todas las claves foráneas y en las consultas por fecha (`sensor_readings`, `alerts`,
  `irrigations`, `ai_predictions`).

## Crear la base de datos

1. Crea la base de datos vacía (una sola vez), por ejemplo desde pgAdmin:

   ```sql
   CREATE DATABASE agrosense_db;
   ```

2. Aplica el esquema de una de estas dos formas:

   - **Desde la aplicación** (no requiere `psql`): arranca el backend con el perfil `seed`, que ejecuta
     `schema.sql` y luego inserta los datos de ejemplo.

     ```powershell
     # en backend\agrosense
     $env:DATABASE_PASSWORD = '<tu contraseña de postgres>'
     $env:SEED_USER_PASSWORD = '<contraseña para el usuario demo>'
     $env:SPRING_PROFILES_ACTIVE = 'seed'
     .\mvnw.cmd spring-boot:run
     ```

     Sin datos de ejemplo, basta con `SQL_INIT_MODE=always` en el backend o en el frontend.

   - **A mano:** ejecuta `schema.sql` en la herramienta de consultas de pgAdmin, o con
     `psql -U postgres -d agrosense_db -f schema.sql`.

## Variables de conexión

| Variable            | Por defecto                                      |
|---------------------|--------------------------------------------------|
| `DATABASE_URL`      | `jdbc:postgresql://localhost:5432/agrosense_db`  |
| `DATABASE_USERNAME` | `postgres`                                       |
| `DATABASE_PASSWORD` | sin valor por defecto (obligatoria)              |

## Verificación

Las pruebas `DatabaseSchemaTests` (backend y frontend) ejecutan `schema.sql` y hacen que Hibernate valide
todas las entidades contra él, usando H2 en modo de compatibilidad con PostgreSQL. La demo del frontend
también crea sus tablas a partir de este archivo.

Pendiente: ejecutarlo contra un servidor PostgreSQL real. H2 emula la sintaxis, pero no es PostgreSQL.
