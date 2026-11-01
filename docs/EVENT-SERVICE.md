# ¿Mantener el manejador de eventos?

Decisión abierta. No se ha borrado nada. Recomendación: **mantenerlo** para esta entrega.

## Qué hace hoy

- Recibe los eventos de los dispositivos (`POST /events`) y valida su forma antes de tocar el backend.
- Es el único canal en vivo que usa la interfaz: `events/live.ts` abre un `EventSource` contra `GET /stream`. No hay cliente
  de WebSocket en el frontend, aunque el backend publique ahí.
- Reintenta con espera creciente cuando el backend no guardó nada.
- Traduce la forma `{deviceId, datos}` a la propia.
- Separa la superficie pública: los dispositivos solo llegan a este servicio, y `/api/internal/events` exige
  `EVENTS_INTERNAL_KEY`.

Son unas 400 líneas de Python con 500 líneas de pruebas. **Hoy no está desplegado**: en producción no existe este tramo.

## Las dos opciones

| | A. Mantenerlo | B. Enviar directo al backend |
|---|---|---|
| **A favor** | Ya existe y tiene pruebas. Lecturas en vivo por SSE sin cambiar el frontend. Una ráfaga de dispositivos no toca el backend hasta validarse. Coincide con el diagrama del proyecto | Un servicio menos que desplegar, vigilar y despertar. Una sola espera de arranque en Render. Menos variables compartidas. Menos latencia |
| **En contra** | Otro servicio del plan gratuito que se duerme: **pueden tener que despertar dos seguidos**. Un punto de fallo más. Hay que desplegarlo y mantener dos secretos iguales en dos sitios. Una sola instancia, porque los canales viven en memoria | Hay que reescribir lo que funciona: ver abajo. El backend queda expuesto directamente a los dispositivos |
| **Trabajo** | Desplegarlo: 1 servicio y 5 variables | Ruta pública nueva en el backend, autenticada solo con `X-Device-Key`; validación y límite de cuerpo ahí; SSE en Java o un cliente STOMP en `live.ts`; cambiar `POST /events` en firmware y simulador; actualizar contrato, diagramas y pruebas; retirar el servicio |
| **Riesgo para la sustentación** | Bajo | Alto: reescritura tarde, sin probar con hardware |

## Por qué la opción A

La razón de peso a favor de B es la doble espera de Render, y se mitiga despertando los servicios antes de la demo
(`docs/DEMO.md`). La reescritura tocaría firmware, simulador, frontend y backend a la vez para quitar un servicio que ya
funciona y tiene pruebas. B tiene sentido después, si el proyecto continúa y un solo servicio resulta más barato de operar.

## Despliegue de la opción A

Ya estaba preparado y lo revisé: `event-service/Dockerfile` (usuario sin privilegios, `HEALTHCHECK` en `/health`, un solo
proceso), `event-service/render.yaml` y el servicio en `compose.yaml`.

| Variable | Valor |
|---|---|
| `BACKEND_URL` | `https://backend-agrosense-u5mz.onrender.com` (en `render.yaml`) |
| `EVENTS_ALLOWED_ORIGINS` | `https://agrosense-frontend-iota.vercel.app` (en `render.yaml`) |
| `EVENTS_INTERNAL_KEY` | A mano; igual que en el backend |
| `EVENTS_STREAM_SECRET` | A mano; igual que en el backend, 32 bytes o más |
| `BACKEND_TIMEOUT_MS` | `60000` (en `render.yaml`), para esperar a un backend dormido |

En Render: **New > Blueprint**, repositorio `API-AGROSENSE`, Blueprint Path `event-service/render.yaml`. Después pon
`EVENTS_PUBLIC_URL` en el frontend y la dirección nueva en `arduino_secrets.h` y `raspberry/simulator.env`.

La prueba real del tramo simulador → manejador de eventos → backend, sin relé provisional, es el trabajo `end-to-end` de
`.github/workflows/agrosense.yml`. No se ha ejecutado: aquí FastAPI no carga y ese workflow aún no ha corrido.
