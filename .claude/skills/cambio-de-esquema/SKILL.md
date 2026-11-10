---
name: cambio-de-esquema
description: Procedimiento obligatorio para cualquier cambio de tablas, columnas, CHECK o índices en AgroSense - schema.sql, migración 00N idempotente, reset.sql, entidades duplicadas en backend y frontend, prueba en H2 y verificación en CI con PostgreSQL. Úsalo antes de tocar database/ o una entidad JPA.
---

# Cambio de esquema

`database/schema.sql` es la única fuente de verdad; backend y frontend usan `ddl-auto=validate` y **nunca** crean tablas.

## Pasos (todos, en el mismo commit)
1. **`database/schema.sql`**: el cambio, idempotente (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`,
   `DROP CONSTRAINT IF EXISTS` seguido de `ADD CONSTRAINT`, `CREATE INDEX IF NOT EXISTS`).
2. **Migración nueva** `database/migrations/00N_<tema>.sql` (N = siguiente número; hoy existen 001, 002, 003). Debe:
   - poder correrse **dos veces** sin error;
   - llevar la base de `schema.sql` anterior + migraciones previas al **mismo estado final** que el `schema.sql` nuevo;
   - antes de añadir un `CHECK` o `NOT NULL`, corregir o rechazar explícitamente las filas existentes que lo violarían
     (no borrarlas en silencio).
   - Comentario en español al inicio: qué hace, si es reversible y en qué orden va respecto al despliegue.
3. **`database/reset.sql`** si se crea o borra una tabla/secuencia.
4. **`database/seed_demo.sql`** si los datos de ejemplo violarían la restricción.
5. **Entidades en ambos lados**: `backend/agrosense/src/main/java/com/agrosense/backend/models/` y
   `frontend/src/main/java/com/agrosense/frontend/entity/` (y sus enums). Deben quedar idénticas en columnas, tipos,
   longitudes y valores de enum.
6. **Documentación**: `database/README.md` (lista de migraciones y orden) y `docs/DESPLIEGUE.md` si cambia el orden.
   *Ojo:* ambos tienen cambios sin confirmar del usuario; si hace falta tocarlos, pide permiso al orquestador antes.

## Pruebas
- H2 modo PostgreSQL: `./mvnw test` en backend **y** frontend (validan entidades contra `schema.sql`).
- Prueba de regresión que demuestre la restricción (p. ej. insertar un `role` inválido falla).
- PostgreSQL real **no corre aquí**: el job `database` de la CI aplica `schema.sql` + todas las migraciones dos veces y
  `PostgresCompatibilityTests`. Márcalo `NO VERIFICADO (CI)` y, para Neon, `DEBO EJECUTAR YO` (migración en Neon dev).

## Nunca
Correr `reset.sql` o `seed_demo.sql` contra producción; editar una migración ya aplicada en Neon (crea otra).
