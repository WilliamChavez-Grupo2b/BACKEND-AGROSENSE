---
name: correr-pruebas
description: Comandos exactos para correr las pruebas de cada componente de AgroSense (backend, frontend Java y TypeScript, IA, event-service, firmware, contrato, e2e), cómo leer las cifras y qué no corre en este Windows y por qué. Úsalo antes y después de cualquier cambio y en la línea base.
---

# Correr las pruebas

Rutas relativas a la raíz del workspace. En Windows usa el Bash del harness (Git Bash) o PowerShell según el comando.

| Componente | Comando | Cifras | ¿Aquí? |
|---|---|---|---|
| Backend | `cd backend/agrosense && ./mvnw -q -B test` | `target/surefire-reports/*.txt` (Tests run / Failures / Errors / Skipped) | Sí (H2). 3 omitidas: PostgreSQL |
| Backend, una clase | `./mvnw -q -B test -Dtest=NombreTests` | idem | Sí |
| Frontend Java | `cd frontend && ./mvnw -q -B test` | `target/surefire-reports` | Sí. 2 omitidas: PostgreSQL |
| Frontend TS | `cd frontend && npm ci && npm run typecheck && npm run build && git diff --exit-code -- src/main/resources/static/js && npm test` | salida de `node --test` (`# pass`, `# fail`) | Sí (necesita Node) |
| IA, sin FastAPI | `cd IA && python -m unittest tests.test_patterns tests.test_rules -v` | `Ran N tests`, `OK` | Sí |
| IA, completa | `cd IA && pip install -r requirements-dev.txt && python -m pytest -q` | `N passed` | **No: FastAPI no carga aquí → CI** |
| Event-service | `cd event-service && python -m pytest -q` | `N passed` | **No: FastAPI → CI** |
| Contrato | `cd api && python tools/validate_spec.py && python -m unittest discover -s tests` | `Ran N tests` | Sí |
| Contrato vs backend vivo | `python api/tools/check_contract.py` (lee su cabecera para la URL) | — | Solo contra backend local H2 |
| Firmware en PC | `sh firmware/test/run-tests.sh` | «N comprobaciones, 0 fallos» | Sí si hay g++ |
| Firmware real | `sh firmware/test/compile.sh` | % flash / RAM | Solo con `arduino-cli` + core `esp32:esp32` + librerías (DHT sensor library, Adafruit Unified Sensor, Adafruit SHT31 Library, MAX6675 library) → si no, CI |
| Esquema 2 veces + migraciones | ver job `database` de la CI (`psql -v ON_ERROR_STOP=1`) | — | **No: PostgreSQL local bloqueado → CI o Neon dev** |
| Entidades vs PostgreSQL | `./mvnw test -Dtest=PostgresCompatibilityTests` con `POSTGRES_TEST_URL/USERNAME/PASSWORD` | — | Solo con Neon dev o CI |
| Demo Windows | `pwsh tools/esp32/run-demo.ps1 -Verificar` (añade `-Simulado` o `-BaseLocal`) | resumen de comprobaciones | Sí, no arranca nada con `-Verificar` |
| Pruebas del script | `tools/esp32/tests/verificar.tests.ps1` (Pester si está) | — | Sí si hay Pester |
| Recorrido completo | `python tools/e2e/run_e2e.py` | pasos OK/FALLO | Necesita IA y event-service (FastAPI) → **CI**; aquí solo si están arriba |

## Reglas
- Reporta **siempre** cifras exactas (ejecutadas, fallos, errores, omitidas) y la fecha. Sin cifra no hay «pasa».
- Referencia previa (1 nov 2026): backend 151, frontend Java 115, TS 12, IA 31, firmware PC 221. Una cifra menor tras un
  cambio exige explicación (¿se borró una prueba?).
- Lo que no corre aquí se marca `NO VERIFICADO (CI)` con el motivo; nunca se instala un sustituto para «hacerlo pasar».
- Si `mvnw` falla por fin de línea (`\r`), no lo conviertas en el repo: corre `sh mvnw ...` o usa `mvnw.cmd` en PowerShell.
