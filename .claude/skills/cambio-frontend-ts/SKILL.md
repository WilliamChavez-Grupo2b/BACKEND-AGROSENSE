---
name: cambio-frontend-ts
description: Reglas para cambiar scripts o plantillas del frontend AgroSense - CSP estricta, sin innerHTML ni th:utext, colores solo en tokens.css, editar src/main/ts, npm run build y comprobar que static/js queda sincronizado. Úsalo antes de tocar frontend/src/main/ts, templates o CSS.
---

# Cambio en el frontend (TypeScript, plantillas, CSS)

## Reglas
- Edita **solo** `frontend/src/main/ts/**`. `src/main/resources/static/js/**` es salida de `tsc`: no se edita a mano,
  pero **se versiona** junto con el `.ts`.
- **CSP**: nada de `<script>` en línea, `style="..."`, `onclick=`, `javascript:` ni `eval`/`new Function`. Los scripts se
  cargan como módulos desde `/js/...`.
- **DOM seguro**: `textContent`, `createElement`, `append`, `setAttribute` con valores validados. Prohibidos `innerHTML`,
  `outerHTML`, `insertAdjacentHTML`, `document.write` y `th:utext`.
- **Colores** solo como variables en `static/css/tokens.css` (temas `premium` y `mono`); `styles.css`, `ai-chat.css` y
  los `.ts` usan `var(--...)`. En `mono` los estados se distinguen por icono/texto/borde, no por color.
- **Riego**: el ESP32 real no tiene bomba. Los textos dicen «registrado», nunca «encendido» ni «iniciado» salvo con
  `IRRIGATION_ACTUATOR=relay`.
- `fetch` mutantes llevan el token CSRF de la página.

## Pasos
1. `cd frontend && npm ci` (si falta `node_modules`).
2. Edita el `.ts`. Añade o ajusta la prueba en `src/test/ts/*.test.mjs` (skill `prueba-regresion`).
3. `npm run typecheck && npm run build`.
4. `npm test`.
5. `git status --short -- src/main/resources/static/js` debe mostrar **solo** los `.js` que corresponden a tus `.ts`.
   Después del commit, `npm run build && git diff --exit-code -- src/main/resources/static/js` debe salir limpio (es lo
   que comprueba la CI).
6. `./mvnw -q -B test` si tocaste plantillas, controladores o `SecurityConfig`.
7. `git add` con las rutas del `.ts`, el `.js` generado, la prueba y la plantilla; nunca la carpeta entera.
