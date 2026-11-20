# Publicar la versión nueva en producción

Producción corre hoy la versión del 11 de octubre. Esta guía lleva a producción el trabajo de
`fix/auditoria-agentes` (23 patrones, página Monitoreo, asistente que dice de dónde sale cada dato, Inicio en vivo).
Ningún secreto aparece aquí: los que hacen falta ya están en Render, en Vercel y en Neon.

| Pieza | Dónde corre | De dónde se publica |
|---|---|---|
| Base de datos | Neon (PostgreSQL) | A mano: migraciones `database/migrations/001` a `005` |
| Frontend | Vercel, proyecto `agrosense-frontend` | `vercel deploy --prod` desde la raíz de este workspace |
| Backend | Render, `BACKEND-AGROSENSE` | El `main` del repositorio `WilliamChavez-Grupo2b/BACKEND-AGROSENSE` |
| IA | Render, `ia-agrosense` | Sin cambios: sigue igual |

**El orden importa:** base de datos, después frontend, al final backend. El frontend y el backend nuevos revisan
sus tablas al arrancar y no arrancan sin las columnas nuevas. Un frontend viejo, a su vez, falla al leer los tipos
de alerta que crea el backend nuevo.

## 1. Base de datos (Neon)

1. Abre Neon › tu proyecto › **SQL Editor**, en la rama de **producción**.
2. Pega y ejecuta, en orden, `001_devices.sql`, `002_alert_types.sql`, `003_reliability.sql`,
   `004_esp32_air_humidity.sql` y `005_air_humidity_alerts.sql` de `database/migrations/`.
   En este PC están las cinco juntas en `tools/esp32/.run/produccion-migraciones-001-005.sql` (fuera de git).
   Son idempotentes: si una ya estaba aplicada, no pasa nada.
3. La consulta final del archivo debe mostrar `air_humidity_min` y `air_humidity_max`, y un número de dispositivos.
4. **Nunca** ejecutes `seed_demo.sql` ni `reset.sql` en producción.

## 2. Frontend (Vercel)

El proyecto ya tiene sus variables: la conexión a Neon (`NEON_PG*`, puesta por la integración de Neon),
`BACKEND_URL` y `BACKEND_JWT_SECRET`. No hay que copiar contraseñas.

```powershell
vercel deploy --prod
```

Desde la raíz del workspace, en la rama `fix/auditoria-agentes`. Usa `Dockerfile.vercel`. Comprueba:
`https://agrosense-frontend-iota.vercel.app/login` carga y, con sesión, aparece **Monitoreo** en el menú.

## 3. Backend (Render)

El `main` de producción tenía el backend en la raíz. La versión nueva trae el workspace completo y su imagen se
construye desde la raíz porque necesita `database/`.

1. Render › `BACKEND-AGROSENSE` › **Settings** › Build & Deploy:
   - **Dockerfile Path:** `backend/agrosense/Dockerfile`
   - **Docker Build Context Directory:** `.` (la raíz)
   - **Health Check Path:** `/api/health`
2. **Environment:** las variables actuales se mantienen (`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`,
   `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `AI_SERVICE_URL`, `AI_SERVICE_TOKEN`). No pongas `ESP32_TCP_ENABLED`: en
   Render el receptor del ESP32 va apagado. `EVENTS_STREAM_SECRET` es opcional; si la pones, debe tener 32 bytes o más.
3. Abre y fusiona la pull request de la rama `publicar/produccion` hacia `main`:
   https://github.com/WilliamChavez-Grupo2b/BACKEND-AGROSENSE/compare/main...publicar/produccion
   Esa rama es `fix/auditoria-agentes` con el `main` de producción como antecesor; su contenido es el mismo.
   Render despliega solo, unos minutos después.
4. Comprueba: `https://backend-agrosense-u5mz.onrender.com/api/health` responde `{"status":"UP"}` (la primera vez
   puede tardar 1 o 2 minutos en despertar).

## 4. Prueba final

1. Inicia sesión en el frontend con tu cuenta.
2. Abre **Monitoreo**: en producción dice «Receptor apagado», porque el ESP32 solo se lee en la demo local.
3. Pregúntale al asistente «¿de dónde salen los datos?».

## Si algo sale mal

- **Frontend:** `vercel rollback` vuelve al despliegue anterior.
- **Backend:** Render › `BACKEND-AGROSENSE` › Events › el despliegue anterior › **Rollback**.
- **Base de datos:** las migraciones solo añaden tablas, columnas y tipos de alerta. La versión anterior las ignora y
  sigue funcionando.
