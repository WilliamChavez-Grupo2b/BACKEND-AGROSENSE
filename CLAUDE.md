# AgroSense: guía para Claude Code

Proyecto universitario de **Patrones de Software**: monitoreo de cultivos. Un ESP32 (`firmware/esp32-tcp/`) mide suelo,
temperatura y humedad y sirve bloques de texto por TCP (`192.168.4.1:8080`); el backend los promedia (60 s), guarda en
PostgreSQL, genera alertas y pide a la IA una recomendación de riego; el frontend lo muestra. El ESP32 real **no tiene
bomba**: el riego solo se registra. Contexto completo: `docs/CONTEXTO-IA.md` (si contradice el código, manda el código).

## Mapa de carpetas

| Carpeta | Qué es | Pruebas |
|---|---|---|
| `backend/agrosense/` | API Spring Boot (Java 17), lector TCP del ESP32, alertas, riego | `./mvnw test` |
| `frontend/` | Thymeleaf + TypeScript (`src/main/ts` → `static/js`) | `./mvnw test`, `npm run build && npm test` |
| `IA/` | FastAPI: reglas de riego, chat, voz | `pytest` (CI); `python -m unittest tests.test_patterns tests.test_rules` |
| `event-service/` | FastAPI: webhook `/events` y SSE `/stream` | `pytest` (CI) |
| `firmware/esp32-tcp/` | Sketch real del ESP32 | `sh firmware/test/compile.sh` (CI o arduino-cli) |
| `firmware/agrosense/` | Firmware HTTP alternativo, **no cargado** | `sh firmware/test/run-tests.sh` |
| `database/` | `schema.sql` (fuente de verdad), `migrations/001..003`, `reset.sql`, `seed_demo.sql` | H2 / CI |
| `api/` | `openapi.json`, `tools/validate_spec.py`, `tools/check_contract.py` | `python api/tools/validate_spec.py` |
| `tools/e2e/`, `tools/esp32/` | Recorrido sin hardware; `run-demo.ps1` (Windows) | `run_e2e.py`, `run-demo.ps1 -Verificar` |
| `.github/workflows/agrosense.yml` | CI con PostgreSQL real | — |

Equipo de agentes en `.claude/agents/`, skills en `.claude/skills/`, flujo en `/auditoria-completa`.

## Reglas (obligatorias)

1. El workspace es la fuente de verdad. **No edites** `agrosense-entrega/`, `agrosense-export/` ni `temp_agrosense/`.
2. Sin `git push`, sin tocar `main`, sin sincronizar con repos de entrega salvo que el usuario escriba «sincroniza».
3. Hay cambios sin confirmar del usuario: no los descartes ni los mezcles. Trabaja en `fix/auditoria-agentes` y usa
   `git add <rutas explícitas>`; **nunca** `git add -A` ni `git add .`.
4. Esquema: todo cambio va en `database/schema.sql` **y** en una migración nueva idempotente `database/migrations/00N_*.sql`,
   y en `reset.sql` si aplica. Las entidades están duplicadas en backend y frontend: cámbialas en ambos.
5. Frontend: edita los `.ts` en `src/main/ts`, corre `npm run build` y versiona `static/js`. Sin estilos ni scripts en
   línea, sin `innerHTML` ni `th:utext`, colores solo en `tokens.css`.
6. Secretos: nunca en archivos, logs ni URLs. La bomba no se ha probado con hardware real: su seguridad vive en el
   firmware, no en la red.
7. No eludas restricciones del equipo: FastAPI y PostgreSQL local están bloqueados en este Windows. Eso corre en CI,
   Neon o H2. Dilo; no lo simules.
8. Cada bug corregido lleva una prueba de regresión que **falla antes y pasa después**. Prohibido borrar o debilitar
   pruebas para que pasen.
9. Commits en inglés con prefijo convencional (`fix(backend): ...`); documentación en español.
10. Nunca declares «probado» lo que no ejecutaste. Separa **VERIFICADO / NO VERIFICADO / DEBO EJECUTAR YO**.
