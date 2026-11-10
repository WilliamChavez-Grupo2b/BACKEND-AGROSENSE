---
description: Flujo completo de auditoría, corrección y mejora de AgroSense con el equipo de subagentes (Fases 0 a 6).
---

Eres el orquestador. Los subagentes no lanzan subagentes: tú los lanzas, consolidas y decides. Respeta `CLAUDE.md`.
Usa los skills `agrosense-contexto`, `correr-pruebas` e `informe-hallazgos`. $ARGUMENTS

## Fase 0 · Línea base
1. `git branch --show-current` = `fix/auditoria-agentes` (créala con `git switch -c` si no existe; nunca descartes los
   cambios sin confirmar). `git status --short`, `git log --oneline -15`.
2. Corre todas las suites posibles (skill `correr-pruebas`) y anota cifras exactas. Referencia previa: backend 151,
   frontend Java 115, TS 12, IA 31, firmware PC 221. Lista lo que NO corre aquí y por qué.

## Fase 1 · Auditoría en paralelo
3. En **un solo mensaje** lanza: `auditor-backend`, `auditor-frontend`, `auditor-ia-eventos`, `auditor-firmware`,
   `auditor-datos-contrato`, `auditor-seguridad`, `ingeniero-ci-despliegue`, `qa-e2e-caos` (modo diagnóstico) y
   `verificador-patrones`. Pásales el commit base y la ruta del scratchpad.
4. Verifica tú al menos la evidencia de cada crítico y alto (abre el archivo:línea). Descarta lo que no tenga evidencia.
5. Consolida en `docs/AUDITORIA.md` (tabla única, sin duplicados, por severidad) y haz commit `docs: add audit report`.

## Fase 2 · Correcciones (máximo 3 rondas)
6. Por cada hallazgo crítico, luego alto, luego medio: `corrector` con un ID → `revisor` con el hash.
   RECHAZADO → devuelve al corrector con el motivo (máximo 2 intentos por hallazgo; después, «no corregido: motivo»).
   Los bajos se agrupan en commits pequeños por parte.
7. Tras cada ronda, re-audita solo las partes tocadas. Si tras 3 rondas queda algo crítico o alto, **repórtalo** en vez de
   seguir iterando. Actualiza `docs/AUDITORIA.md` con commit y resultado.

## Fase 3 · Caos
8. `qa-e2e-caos` ejecuta sus escenarios; cada degradación mala → hallazgo `QA-` → corrector → revisor.

## Fase 4 · Mejoras de bajo riesgo (sección 9 de CONTEXTO-IA.md), solo si se prueban aquí
9. `eventId` único por evento; listado y desactivación de dispositivos; `CHECK` en `users.role`,
   `sensor_readings.quality` y `ai_predictions.prediction_type` (skill `cambio-de-esquema`); health check `/api/health`
   para Render (configuración y documentación). Cada una: corrector + revisor, con prueba.
10. Desplegar, migrar los 9 servicios con JPA directo y pruebas con la placa: **solo se documentan**.

## Fase 5 · Documentación
11. `redactor-sustentacion` con las cifras reales: alinea docs y genera `docs/SUSTENTACION.md`.

## Fase 6 · Re-verificación
12. Todas las suites otra vez; tabla Fase 0 vs Fase 6. Ninguna prueba que pasaba puede quedar rota; si una cifra baja,
    explícalo.

## Entrega (formato obligatorio, skill `informe-hallazgos`)
1. Tabla de hallazgos con commit o motivo. 2. Pruebas de regresión nuevas y cifras antes/después. 3. VERIFICADO / NO
VERIFICADO / DEBO EJECUTAR YO (migraciones 001-003 en Neon dev; pytest IA y event-service en CI; compilación y pruebas
con la placa; -8.5 °C y -6 % con referencias; despliegue). 4. Riesgos abiertos para la demo y contingencia. 5. Estado
honesto de los patrones.

Prohibido en todo el flujo: `git push`, tocar `main`, sincronizar sin «sincroniza», `git add -A`, editar las carpetas de
entrega, simular FastAPI/PostgreSQL local, debilitar pruebas, declarar probado lo no ejecutado.
