---
name: demo-esp32
description: Cómo usar tools/esp32/run-demo.ps1 (-Verificar, -PrepararBase, -Simulado, -SinEsp32, -BaseLocal, -NoAbrir) y stop-demo.ps1 para la demo con el ESP32 real o simulado en Windows, y dónde está el plan de pruebas con la placa (docs/PRUEBAS-ESP32.md). Úsalo para preparar o verificar la demo.
---

# Demo con el ESP32

Script: `tools/esp32/run-demo.ps1` (PowerShell 5.1 y 7, sin Docker). Lee `tools/esp32/.env.local` (fuera de git; **no lo
abras**: está denegado). Arranca backend y luego frontend contra una **branch de desarrollo de Neon**. Se niega a usar la
base de producción comparando el endpoint de Neon (`ep-...`, con o sin `-pooler`). Detener: `tools/esp32/stop-demo.ps1`.

| Opción | Qué hace | Cuándo |
|---|---|---|
| `-Verificar` | Solo comprobaciones previas (archivo, base no-producción, secreto, Java, puertos, internet, ESP32); no arranca nada | Siempre primero; es lo que pueden correr los agentes |
| `-PrepararBase` | Aplica `schema.sql`, migraciones 001–003 y `seed_demo.sql` en la base, y la contraseña demo. Pide escribir `SI` | Una vez por branch de Neon. **Solo el usuario** (interactivo) |
| `-Simulado` | ESP32 falso en Python (`esp32_simulado.py`, `--host 127.0.0.1 --port 18080`) y `ESP32_TCP_ALLOW_LOOPBACK` solo en el proceso del backend; el resumen dice DATOS SIMULADOS | Plan B sin placa |
| `-SinEsp32` | `ESP32_TCP_ENABLED=false` | Plan B sin TCP |
| `-BaseLocal` | H2 en archivo en `tools/esp32/.run/h2` (ignorado por git), se llena sola | Sin Neon ni internet |
| `-NoAbrir` | No abre el navegador | Ejecuciones automáticas |
| `-EnvFile <ruta>` | Otro archivo de variables | Pruebas del script |

## Con la placa real
1. Compila y carga `firmware/esp32-tcp/planticas_proyecto` en el Arduino IDE con su `arduino_secrets.h` (clave ≥ 12, distinta
   del SSID). 2. Une el PC a la red `PLANTICAS` desde Windows. 3. `run-demo.ps1 -Verificar` y después `run-demo.ps1`.
4. Estado: `GET /api/devices/esp32/status`; la vista del cultivo se actualiza cada 30 s.
Plan completo (monitor serie, pruebas manuales, qué anotar): `docs/PRUEBAS-ESP32.md`.

## Para agentes
- Puedes correr `-Verificar`, `-Verificar -Simulado` y `-Verificar -BaseLocal`. Arrancar de verdad (`-Simulado -NoAbrir
  -BaseLocal`) solo si el orquestador lo pide; detén siempre con `stop-demo.ps1`.
- Nunca `-PrepararBase` (interactivo, toca una base remota). Nunca contra producción.
- Lo que requiere la placa (lecturas reales, -8.5 °C y -6 % con referencias, GPIO5 en arranque) es `DEBO EJECUTAR YO`.
