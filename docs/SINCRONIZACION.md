# Qué va a cada repositorio de la entrega

La tabla sale de `SYNC-ENTREGA.md`, que sigue siendo un archivo local (excluido en `.git/info/exclude`) porque guarda
rutas de este equipo, una identidad de commits y el registro de cada sincronización. Aquí queda solo lo que un compañero
necesita saber.

| Workspace (monorepo, un solo `.git`) | Entrega | Rama base en la entrega |
|---|---|---|
| `frontend/` | `frontend` | `feature/sin-comentarios` |
| `backend/agrosense/` (código), `backend/README.md`, `backend/docs/` | `backend` (raíz) | `feature/sin-comentarios` |
| `IA/` | `ia` | `feature/arquitectura-servicios` |
| `event-service/` y `raspberry/` | `event-service`, con `raspberry/` dentro | `feature/arquitectura-servicios` |
| `database/` | `database` | `feature/arquitectura-servicios` |
| `api/` | `api` | `feature/sin-comentarios` |

Sin destino en la entrega todavía:

| Carpeta | Situación |
|---|---|
| `firmware/` | Necesita repositorio propio (propuesta: `FIRMWARE-AGROSENSE`). Hasta entonces solo existe en el workspace |
| `tools/`, `.github/` | Solo funcionan con el workspace completo |
