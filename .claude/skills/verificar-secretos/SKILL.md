---
name: verificar-secretos
description: Comandos para buscar secretos en el árbol de trabajo y en el historial git de AgroSense, cómo reportarlos sin exponerlos y qué rotar si aparece alguno. Úsalo en auditorías de seguridad, antes de un commit sensible o antes de sincronizar con los repos de entrega.
---

# Verificar secretos

**Nunca imprimas un secreto completo.** Reporta archivo:línea o commit, los 4 primeros caracteres y la longitud. No abras
`.env`, `tools/esp32/.env.local` ni `arduino_secrets.h` (denegados); comprueba solo que git los ignora.

## 1. Que los archivos de secretos no estén versionados
```sh
git ls-files | grep -Ei '(^|/)\.env($|\.local)|arduino_secrets\.h$|\.pem$|\.p12$|id_rsa' || echo "ninguno versionado"
git check-ignore -v .env tools/esp32/.env.local firmware/esp32-tcp/planticas_proyecto/arduino_secrets.h
```

## 2. Árbol de trabajo (sin dependencias ni copias)
```sh
EXCL='--glob=!**/node_modules/** --glob=!**/target/** --glob=!agrosense-entrega/** --glob=!agrosense-export/** --glob=!temp_agrosense/**'
rg -n $EXCL -e 'sk-[A-Za-z0-9_-]{20,}' -e 'gsk_[A-Za-z0-9]{20,}' -e 'sk-ant-[A-Za-z0-9_-]{20,}' -e 'AIza[0-9A-Za-z_-]{30,}' \
   -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' -e 'postgres(ql)?://[^:/\s]+:[^@\s]+@' -e 'npg_[A-Za-z0-9]{10,}' \
   -e '(JWT_SECRET|BACKEND_JWT_SECRET|INTERNAL_API_KEY|AI_SERVICE_KEY|EVENTS_INTERNAL_KEY|EVENTS_STREAM_SECRET|SEED_DEVICE_KEY|DEMO_PASSWORD|SEED_USER_PASSWORD)\s*[:=]\s*["'"'"']?[^"'"'"'\s$<{]{8,}' \
   -e 'SECRET_(AP|WIFI)_PASS\s+"[^"C][^"]{7,}"' -r -- . | cut -c1-160
```
Valores de prueba evidentes (`e2e-...`, `test-...`, `CAMBIA_ESTA_CLAVE`) no son hallazgos si solo viven en pruebas/CI;
sí lo son si llegan a un perfil que se despliega.
Las copias de entrega se revisan aparte solo para **avisar** (no se editan).

## 3. Historial
```sh
for p in 'sk-' 'gsk_' 'sk-ant-' 'AIza' 'BEGIN RSA' 'BEGIN PRIVATE' 'npg_' '@ep-' 'JWT_SECRET=' 'SECRET_AP_PASS'; do
  echo "== $p"; git log --all --oneline -S"$p" | head
done
git log --all --diff-filter=A --name-only --format='%h' | grep -Ei '\.env$|\.env\.local$|arduino_secrets\.h$'
```
Para un commit sospechoso: `git show <hash> | grep -n '<patrón>' | cut -c1-80`.

## 4. Si aparece un secreto real
- **Rotarlo** es la corrección (reescribir el historial no sirve y exige push forzado, prohibido): dónde se genera y dónde
  se pone el nuevo.
  | Secreto | Dónde rotar | Debe coincidir con |
  |---|---|---|
  | `JWT_SECRET` (≥ 64 B) | Render backend | `BACKEND_JWT_SECRET` (Vercel frontend) |
  | `INTERNAL_API_KEY` | Render IA | `AI_SERVICE_KEY` (backend) |
  | `EVENTS_INTERNAL_KEY`, `EVENTS_STREAM_SECRET` (≥ 32 B) | backend y manejador | entre sí |
  | Contraseña Neon | consola Neon | `DATABASE_PASSWORD` backend y frontend |
  | Llaves Groq/Anthropic/Gemini | consola del proveedor | `AI_CHAT_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` en IA |
  | Llave de dispositivo | `POST /api/devices` (alta nueva) | `SECRET_DEVICE_KEY` |
  | Clave Wi-Fi `PLANTICAS` | `arduino_secrets.h` local | — |
- Quitarlo del árbol en un commit `fix(security): ...` y marcarlo `DEBO EJECUTAR YO: rotar <nombre>`.
