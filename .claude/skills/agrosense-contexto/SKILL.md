---
name: agrosense-contexto
description: Cómo orientarse en el monorepo AgroSense - mapa de carpetas, fuente de verdad, qué está cargado en la placa y qué carpetas están prohibidas. Úsalo al empezar cualquier tarea en este repositorio.
---

# Orientarse en AgroSense

1. Lee `CLAUDE.md` (reglas) y `docs/CONTEXTO-IA.md` (contexto). Si un documento contradice el código, **manda el código**
   y la contradicción se reporta.
2. Comprueba dónde estás: `git branch --show-current` y `git status --short`. El trabajo de auditoría va en
   `fix/auditoria-agentes`. Hay cambios sin confirmar del usuario que no se tocan: `database/README.md`,
   `frontend/src/test/java/com/agrosense/frontend/PostgresCompatibilityTests.java`, `.../WebFlowTests.java`,
   `tools/esp32/run-demo.ps1` y `docs/DESPLIEGUE.md` (sin seguimiento).

## Mapa

| Ruta | Qué es | ¿Corre en producción? |
|---|---|---|
| `backend/agrosense/src/main/java/com/agrosense/backend/` | API, ingesta, alertas, riego. Patrones en `pattern/` | Sí (Render) |
| `.../pattern/structural/adapter/esp32/` | Lector TCP del ESP32 | Solo con `ESP32_TCP_ENABLED=true` (nunca en Render) |
| `frontend/src/main/ts/` → `static/js/` | Scripts del navegador (salida versionada) | Sí (Vercel) |
| `frontend/src/main/java/com/agrosense/frontend/` | Thymeleaf, `BackendClient`, 9 clases con JPA directo | Sí |
| `IA/agrosense_ia/` | FastAPI: reglas de riego + chat/voz | Sí (Render) |
| `event-service/agrosense_events/` | Webhook + SSE | **No desplegado** |
| `firmware/esp32-tcp/planticas_proyecto/` | **Sketch real cargado en la placa** | Sí (placa) |
| `firmware/agrosense/` | Firmware HTTP alternativo con relé | **No cargado** |
| `database/schema.sql` | Única fuente de verdad del esquema | — |
| `database/migrations/001..003` | Para la base existente (Neon) | Pendiente en Neon |
| `api/openapi.json` | Contrato del backend | — |
| `tools/e2e/run_e2e.py`, `tools/esp32/` | Recorrido sin hardware; demo en Windows | — |

## Prohibido
- Editar `agrosense-entrega/`, `agrosense-export/`, `temp_agrosense/` (copias de entrega; `.claude/settings.json` lo
  bloquea).
- Leer o escribir `.env`, `tools/esp32/.env.local`, `arduino_secrets.h`.
- `git push`, tocar `main`, sincronizar con repos de entrega sin «sincroniza».
- Instalar o simular FastAPI o PostgreSQL local: están bloqueados en este Windows (decisión 17). Usa H2, la CI o Neon dev.

## Banderas que cambian qué corre
`ESP32_TCP_ENABLED`, `ESP32_TCP_ALLOW_LOOPBACK` (solo `-Simulado`), `IRRIGATION_ACTUATOR` (`simulated`/`relay`),
`IRRIGATION_AI_ORDERS`, `AI_CHAT_VIA_SERVICE`, `MQTT_ENABLED`, `SENSOR_CALIBRATION`, `READINGS_RETENTION_DAYS`.
