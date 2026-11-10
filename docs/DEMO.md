# Plan de la demo

## ESP32 por TCP (red local PLANTICAS)

El ESP32 (`firmware/esp32-tcp/`) crea la red Wi-Fi `PLANTICAS` y espera en `192.168.4.1:8080`. El backend corre en el PC
y se conecta a él.

1. **Internet y base de datos.** Unido a `PLANTICAS`, el PC pierde internet por ese adaptador. Si la base es **Neon**, el
   PC necesita internet por otro lado: **cable de red, un segundo adaptador Wi-Fi o el teléfono por USB**. Sin eso, usa
   una base local: el PostgreSQL de `docker compose`, o el perfil `seed` del backend contra H2, como el trabajo
   `end-to-end` de la CI.
2. **Conecta el PC a la red `PLANTICAS`** desde el sistema operativo (Configuración > Red e Internet > Wi-Fi). La clave
   la tiene el equipo y no está en el repositorio. Comprueba el puerto en PowerShell:
   `Test-NetConnection 192.168.4.1 -Port 8080` debe decir `TcpTestSucceeded : True`. Esa prueba abre y cierra una
   conexión; no la dejes abierta.
3. **No dejes PuTTY ni otro cliente conectado al ESP32.** Atiende un solo cliente: si PuTTY ocupa el cupo, el backend se
   conecta pero no recibe nada, y cada 30 s lo vuelve a intentar.
4. **Levanta el backend con el receptor encendido**, fuera de Docker para no depender de la red de los contenedores:

   ```powershell
   cd backend/agrosense
   $env:ESP32_TCP_ENABLED = "true"
   $env:ESP32_DEVICE_ID = "RPI-DEMO-01"     # ESP32_TCP_HOST y ESP32_TCP_PORT ya valen 192.168.4.1 y 8080
   $env:ESP32_AGGREGATE_SECONDS = "60"     # una lectura guardada por minuto
   $env:IRRIGATION_ACTUATOR = "simulated"  # el ESP32 no tiene bomba
   $env:SPRING_PROFILES_ACTIVE = "seed"    # más JWT_SECRET, SEED_USER_PASSWORD y la conexión de la base
   ./mvnw spring-boot:run
   ```

   En el registro debe aparecer `ESP32: connected to 192.168.4.1:8080`. Si sale `unreachable`, revisa el Wi-Fi del PC;
   el backend reintenta solo cada 2 a 30 s. Si sale `nothing from 192.168.4.1:8080 in 30 s`, hay otro cliente conectado.
   Con `docker compose`, pon `ESP32_TCP_ENABLED=true` en `.env`; si el contenedor no llega al ESP32, corre el backend
   fuera de Docker como arriba.
5. **Levanta el frontend** contra ese backend y la misma base, inicia sesión con la cuenta de ejemplo y abre
   **Cultivos > Café**. Al minuto arriba dice «ESP32 conectado» y los valores de AS-001 (suelo), AS-002 (temperatura) y
   AS-007 (humedad del aire) se actualizan solos cada 30 s. Las gráficas del inicio muestran una lectura por minuto.
6. Para ver el receptor por dentro: `GET /api/devices/esp32/status` con la sesión del backend (conectado, último bloque,
   última lectura guardada, bloques descartados).
7. El riego **no** llega al ESP32: no acepta órdenes ni tiene bomba. Registrar un riego para Café solo lo guarda.

**Plan B sin ESP32:** `python tools/e2e/run_e2e.py` envía lecturas al manejador de eventos con el mismo modelo de evento
y recorre lectura, alerta, predicción y orden de riego (sección «Correr todo en local» de `README.md`).

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
| **ESP32 por TCP sin datos** | «Sin datos desde hh:mm» en el cultivo; `unreachable` o `nothing from … in 30 s` en el registro del backend | Comprueba que el PC sigue en `PLANTICAS`, cierra PuTTY u otro cliente y prueba el puerto con `Test-NetConnection`. El backend reconecta solo. Si no vuelve, plan B: `tools/e2e` |
| **Hardware** (sensor, relé, placa) | Lecturas ausentes, alerta `SENSOR_FAULT` | Plan B: corre `python tools/e2e/run_e2e.py` en el portátil. Envía eventos con el mismo contrato y recorre lectura, alerta, predicción y orden de riego (con `IRRIGATION_ACTUATOR=relay`). Para la bomba, sube el sketch con `AGRO_SIMULATED_HARDWARE` |
| **Base de datos** | Errores al iniciar sesión | Corre el frontend en local con el perfil `demo`, que usa datos de ejemplo en memoria |
| **Todo lo anterior** | | Usa las capturas y `docs/PATRONES.md` |

## Riego con relé

Para que un riego llegue a la bomba, backend y frontend deben tener `IRRIGATION_ACTUATOR=relay`. La orden tarda hasta 30 s
en llegar, y la bomba funciona como máximo 5 minutos aunque se pidan más. Prueba antes **sin carga**.

Si el riego no arranca, consulta `irrigation_dispatches`: `EXPIRED` significa que el dispositivo no envió nada en 5
minutos, `REFUSED` que lo rechazó y `DELIVERED` que llegó pero aún no hay confirmación.
