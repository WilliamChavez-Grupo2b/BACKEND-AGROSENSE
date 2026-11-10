---
name: informe-hallazgos
description: Formato único de los hallazgos de auditoría de AgroSense (ID, severidad, archivo:línea, evidencia, reproducción, corrección, prueba de regresión) y del informe final (docs/AUDITORIA.md y la entrega con VERIFICADO / NO VERIFICADO / DEBO EJECUTAR YO). Úsalo al reportar o consolidar hallazgos.
---

# Informe de hallazgos

## Un hallazgo
| Campo | Contenido |
|---|---|
| **ID** | Prefijo de la parte + número: `BE-`, `FE-`, `IA-`, `EV-`, `FW-`, `DB-`, `API-`, `SEC-`, `PAT-`, `CI-`, `QA-` |
| **Severidad** | `crítico` (pérdida/corrupción de datos, acceso a datos ajenos, secreto expuesto, riesgo físico) · `alto` (caída o bloqueo de un servicio, fallo de seguridad explotable con condiciones, contrato roto) · `medio` (comportamiento incorrecto con salida, fuga acotada, doc que engaña en la sustentación) · `bajo` (robustez, estilo con efecto, doc menor) |
| **Estado** | `CONFIRMADO` (reproducido o evidente en el código) o `PLAUSIBLE` (falta ejecutar algo; di qué) |
| **Archivo:línea** | Ruta relativa a la raíz y línea |
| **Evidencia** | Extracto corto del código o salida de un comando. **Sin evidencia no hay hallazgo** |
| **Cómo reproducir** | Pasos o comando exactos |
| **Corrección propuesta** | Mínima |
| **Prueba de regresión** | Archivo y nombre de la prueba que fallaría hoy y pasaría tras el arreglo |

Formato compacto para tablas: `ID | sev | estado | archivo:línea | problema | evidencia | reproducir | corrección | prueba`.
Nunca incluyas secretos (4 caracteres y longitud como mucho).

## docs/AUDITORIA.md (consolidado por el orquestador)
1. Fecha, rama, commit base, auditores que corrieron y qué no pudo ejecutarse.
2. **Tabla única** ordenada crítico → alto → medio → bajo, sin duplicados (si dos auditores reportan lo mismo, un ID y
   «también: X-NN»), con columnas extra `commit` y `resultado` (corregido / no corregido: motivo / no reproducido).
3. Hallazgos descartados y por qué.

## Entrega final
1. Tabla de hallazgos: ID, severidad, archivo, problema, corrección y commit, o motivo por el que no se corrigió.
2. Pruebas de regresión nuevas y cifras antes/después por componente.
3. **VERIFICADO** (ejecutado aquí, con cifra) / **NO VERIFICADO** (con motivo: CI, FastAPI, PostgreSQL, placa) / **DEBO
   EJECUTAR YO**, en este orden: migraciones 001-003 en Neon dev; pytest de IA y event-service en CI; compilación y
   pruebas del ESP32 con la placa; verificar -8.5 °C y -6 % con referencias; despliegue.
4. Riesgos abiertos para la demo y plan de contingencia.
5. Estado honesto de los patrones: cuáles corren en producción y cuáles solo en el firmware alternativo.
