# Plan de la demo

## Antes de empezar

**15 minutos antes**, despierta los servicios. El plan gratuito de Render duerme cada uno tras unos 15 minutos sin uso.

| Servicio | Salud | Público | Espera esperada si duerme |
|---|---|---|---|
| Backend | `GET /api/health` | Sí | 1 a 3 minutos (80 s medidos el 11 de octubre) |
| Servicio de IA | `GET /health` | Sí | 1 a 3 minutos |
| Manejador de eventos | `GET /health` | Sí | 1 a 3 minutos. Es un servicio aparte y **aún no está desplegado** |
| Frontend | `GET /health` | Sí | Vercel no duerme igual, pero su primera petición puede tardar |

Despiértalos a la vez: la primera petición inicia el arranque. Cuenta hasta 3 minutos antes de darlo por fallido, que es
el `--max-time 180` de abajo. Cada línea debe mostrar `200`; `000` significa que no respondió a tiempo. Repite entonces, y
si sigue igual, pasa a la tabla «Si algo falla». Despertado el servicio, responde en menos de un segundo.

```bash
for url in https://backend-agrosense-u5mz.onrender.com/api/health \
           https://ia-agrosense.onrender.com/health \
           https://<manejador-de-eventos>.onrender.com/health; do
  curl -s -o /dev/null -w "%{http_code} $url\n" --max-time 180 "$url" &
done
wait
```

Para mantenerlos despiertos durante la demo, deja esto en una terminal:

```bash
while true; do
  curl -s -o /dev/null --max-time 60 https://backend-agrosense-u5mz.onrender.com/api/health
  curl -s -o /dev/null --max-time 60 https://<manejador-de-eventos>.onrender.com/health
  sleep 300
done
```

Úsalo solo durante la demo: el plan gratuito limita las horas al mes.

**Enciende la placa 5 minutos antes**: el mínimo entre riegos cuenta también desde el arranque.

Ten listo también:

- Capturas de la página de inicio, una alerta, la gráfica y una recomendación, por si falla la red.
- El recorrido sin hardware listo en un portátil: `tools/e2e/run_e2e.py` con `BACKEND_URL`, `EVENTS_URL`, `API_PASSWORD`
  y `DEVICE_KEY` del dispositivo de ejemplo.
- La cuenta de ejemplo y un punto de acceso del teléfono.

## Si algo falla

| Falla | Cómo se nota | Qué hacer |
|---|---|---|
| **Wi-Fi del lugar** | El ESP32 no envía; `en cola` sube en el monitor serie | Usa el punto de acceso del teléfono **en 2,4 GHz**, configurado de antemano en `arduino_secrets.h`. El ESP32 guarda 16 eventos y los envía al volver |
| **Render dormido** | La interfaz muestra «Despertando el servidor» | Espera 1 a 3 minutos y explica el diseño: el dispositivo reintenta cada 15 s y no pierde lecturas mientras tanto |
| **Manejador de eventos dormido o caído** | El dispositivo no recibe respuesta | Lo mismo. Si no vuelve, la interfaz sigue mostrando lo guardado, sin datos en vivo |
| **IA caída** | Recomendaciones con la etiqueta «Respaldo local»; asistente con la nota de que no respondió el modelo | Es un caso a favor: enseña que el sistema sigue dando una recomendación y dice de dónde sale |
| **Hardware** (sensor, relé, placa) | Lecturas ausentes, alerta `SENSOR_FAULT` | Plan B: corre `python tools/e2e/run_e2e.py` en el portátil. Envía eventos con el mismo contrato y recorre lectura, alerta, predicción y orden de riego (con `IRRIGATION_ACTUATOR=relay`). Para la bomba, sube el sketch con `AGRO_SIMULATED_HARDWARE` |
| **Base de datos** | Errores al iniciar sesión | Corre el frontend en local con el perfil `demo`, que usa datos de ejemplo en memoria |
| **Todo lo anterior** | | Usa las capturas y `docs/PATRONES.md` |

## Riego con relé

Para que un riego llegue a la bomba, backend y frontend deben tener `IRRIGATION_ACTUATOR=relay`. La orden tarda hasta 30 s
en llegar, y la bomba funciona como máximo 5 minutos aunque se pidan más. Prueba antes **sin carga**.

Si el riego no arranca, consulta `irrigation_dispatches`: `EXPIRED` significa que el dispositivo no envió nada en 5
minutos, `REFUSED` que lo rechazó y `DELIVERED` que llegó pero aún no hay confirmación.
