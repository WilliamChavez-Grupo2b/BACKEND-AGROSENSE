# Plan de la demo

## Ver los sensores en tiempo real (resumen)

1. Conecta el ESP32 al PC por el cable USB (o une el PC a la red `PLANTICAS`) y arranca todo:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1 -Usb -BaseLocal
   ```

   `-Usb` lee la placa por el cable (COM3 o el primer adaptador CH340/CP210x); sin `-Usb` la lee por Wi-Fi. `-BaseLocal`
   usa una base H2 en este PC y no necesita internet. El resumen final dice «Receptor encendido; conectado: sí».
2. Abre `http://localhost:8080` e inicia sesión con `demo@agrosense.co` y la `DEMO_PASSWORD` de tu `.env.local`.
3. La placa envía un bloque por segundo y el backend guarda **una lectura por minuto** (el promedio de esos 60 bloques).
   La primera aparece al minuto de arrancar. Dónde verlas:

   | Página | Qué se ve | Se actualiza sola |
   |---|---|---|
   | **Monitoreo** | Estado de la conexión, bloques recibidos y descartados, última lectura guardada, regla de riego | Cada 15 s |
   | **Inicio** › Lecturas por sensor | El último valor de cada sensor activado | Cada 30 s |
   | **Cultivos** › Café | Lecturas de suelo, temperatura y aire, e indicador «ESP32 conectado» | Cada 30 s |
   | **Sensores** › Humedad del ESP32 | Lista y gráfica del día con cada lectura | Cada 30 s |
   | **Sensores** › un sensor | Su estado y su última lectura | Al recargar la página |

4. Para comprobar que es en vivo: toca el sensor de suelo o pon la mano cerca del DHT11 y espera el minuto siguiente.
   En **Monitoreo** suben los bloques recibidos cada 15 segundos aunque todavía no cambie la lectura guardada.
5. **Alertas que se cierran solas:** cuando una lectura vuelve al rango del cultivo, su alerta abierta se marca como
   atendida con la nota «Se cerró sola: la lectura volvió al rango (61 %)»; la de «dispositivo sin reportar» se cierra
   cuando la placa vuelve a enviar datos. Para mostrarlo: con el sensor de suelo fuera de la tierra aparece «Humedad baja»
   al minuto; al volver a meterlo, al minuto siguiente la alerta pasa a «Atendida» sola (Alertas › Todas). El riesgo de
   helada no se cierra solo: depende del pronóstico, no de la lectura de ahora.
6. Pregúntale al asistente (botón del chat) «¿de dónde salen los datos?» o «¿dónde se guardan?»: responde con cada
   sensor, su cultivo, su dispositivo, la hora de la última lectura y la tabla de la base donde queda.
7. Para detener todo: `tools\esp32\stop-demo.ps1`.

Si la humedad del suelo marca 0 % fijo, la sonda está fuera de la tierra o su cable al GPIO34 está suelto: el firmware
da 0 % cuando el valor crudo llega a 2900 o más (`ADC_SECO`, seco o al aire).

## Correr con el ESP32 real

Un solo comando arranca el backend y el frontend en este PC Windows, sin Docker, contra una **branch de desarrollo de
Neon** (nunca producción), con el ESP32 leído por TCP. Funciona en Windows PowerShell 5.1 y en PowerShell 7.

**Requisitos:** Java 21 o más nuevo (el frontend pide 21, el backend 17), el PC unido al Wi-Fi `PLANTICAS` y con
internet por **cable o por el celular en USB** (el Wi-Fi `PLANTICAS` no tiene internet; Neon y Maven sí lo necesitan).

### La primera vez

1. Copia `tools\esp32\.env.local.example` como `tools\esp32\.env.local` (git lo ignora) y llena cada valor `<...>`:
   host directo de la branch de desarrollo (sin `-pooler`), base, usuario y clave, `PROD_DB_HOST` (el host de
   producción, solo para bloquearlo), `JWT_SECRET` (64 bytes o más; el archivo trae el comando para generarlo) y
   `DEMO_PASSWORD`.
2. Comprueba todo sin arrancar nada:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1 -Verificar
   ```

   Revisa siete cosas y dice qué hacer con cada una que falle: el archivo sin marcadores, que la base no sea
   producción (compara el endpoint de Neon, la parte `ep-...`, con o sin `-pooler` y sin importar mayúsculas), el
   `JWT_SECRET`, Java, los puertos 9000 y 8080, la conexión a la base por el puerto 5432 y el ESP32 en
   `192.168.4.1:8080`.
3. Si la branch está vacía o viene de una base anterior, prepárala una vez:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1 -PrepararBase
   ```

   Pide escribir `SI` y aplica `schema.sql`, `migrations/001` a `003` y `seed_demo.sql` (la cuenta demo, el cultivo Café,
   el dispositivo `ESP32-PLANTICAS` y sus sensores AS-001, AS-002 y AS-007), y le pone `DEMO_PASSWORD` a la cuenta demo. Todo
   es idempotente y no borra datos. No hace falta `psql`: lo aplica el propio backend (perfil `seed`). Después sigue
   arrancando como en el día de la demo.

### El día de la demo

```powershell
powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1
```

Hace las comprobaciones, arranca el backend y espera a que `/api/health` responda (hasta 3 minutos), arranca el frontend
apuntando al backend, espera hasta 30 s el mensaje `ESP32: connected to 192.168.4.1:8080` y muestra el estado del
receptor (conectado, bloques recibidos y descartados, última lectura). Abre el navegador en `http://localhost:8080`.
Entra con `demo@agrosense.co` y tu `DEMO_PASSWORD`, y abre **Cultivos > Café**: la primera lectura aparece al minuto.
Para ver solo la humedad del ESP32 y la planta que mide, abre **Sensores > Humedad del ESP32**: la lista se actualiza
cada 30 s ([SENSOR-ESP32.md](SENSOR-ESP32.md)).

- **No dejes PuTTY conectado al ESP32**: atiende un solo cliente.
- Los registros quedan en `tools\esp32\.run\backend.log` y `frontend.log` (git los ignora). Ningún secreto se escribe en
  la consola; el script avisa si alguno aparece en un registro.
- Si una aplicación no arranca, el script muestra las últimas 30 líneas de su registro y una pista (base, puerto,
  secreto, Java o esquema sin preparar).
- `-NoAbrir` no abre el navegador.
- Para detener todo: `powershell -ExecutionPolicy Bypass -File tools\esp32\stop-demo.ps1`. Solo detiene lo que arrancó
  `run-demo.ps1` (guarda sus PID en `tools\esp32\.run\`), no otros programas Java.

### Plan A bis: la placa por el cable USB

Si el PC no logra unirse a la red PLANTICAS pero el ESP32 está conectado por USB, usa los datos reales por el cable:

```powershell
powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1 -Usb -BaseLocal
```

`tools\esp32\esp32_usb.py` lee el puerto serie (el primer adaptador CH340 o CP210x, o `ESP32_SERIAL_PORT=COMn` en el
archivo de variables) y le entrega al backend el mismo bloque que enviaría el Wi-Fi, en `127.0.0.1`. Necesita
`python -m pip install pyserial`. Cierra PuTTY y el monitor serie de Arduino: el puerto lo usa un solo programa. Si la placa
deja de medir, el puente deja de enviar y el backend muestra que no hay datos.

### Plan B sin la placa: datos simulados

```powershell
powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1 -Simulado
```

Arranca un **ESP32 falso** (`tools\esp32\esp32_simulado.py`, solo necesita Python 3) en `127.0.0.1:18080`, con el mismo
formato de bloque, un solo cliente a la vez y valores crudos que cambian despacio. Cada 10 minutos la humedad del suelo
baja del 40 % y aparece la alerta de humedad baja. El backend lo lee igual que a la placa: promedio de 60 s, calibración,
alertas. No necesita la red `PLANTICAS` ni el manejador de eventos. El resumen final y el registro dicen **DATOS
SIMULADOS**: dilo también en la sustentación. `ESP32_TCP_ALLOW_LOOPBACK` (el backend rechaza `127.0.0.1` sin ella) se
activa solo en este modo y solo en el proceso del backend; en `.env.local` está prohibida. `stop-demo.ps1` también
detiene el ESP32 falso.

### Plan C: sin ningún ESP32

```powershell
powershell -ExecutionPolicy Bypass -File tools\esp32\run-demo.ps1 -SinEsp32
```

Arranca con `ESP32_TCP_ENABLED=false`: se ven los datos ya guardados. `tools/e2e/run_e2e.py` también envía lecturas, pero
necesita el manejador de eventos (`event-service/`), que este script no arranca.

Lo que sigue explica el mismo recorrido a mano.

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
   $env:ESP32_DEVICE_ID = "ESP32-PLANTICAS"     # ESP32_TCP_HOST y ESP32_TCP_PORT ya valen 192.168.4.1 y 8080
   $env:ESP32_AGGREGATE_SECONDS = "60"     # una lectura guardada por minuto
   $env:IRRIGATION_ACTUATOR = "simulated"  # el ESP32 no tiene bomba
   $env:SENSOR_CALIBRATION = "AIR_TEMPERATURE=-8.5:1,RELATIVE_HUMIDITY=-6:1"  # por verificar
   $env:SPRING_PROFILES_ACTIVE = "seed"    # más JWT_SECRET, SEED_USER_PASSWORD y la conexión de la base
   ./mvnw spring-boot:run
   ```

   `SENSOR_CALIBRATION` aplica en el backend las compensaciones que antes restaba el sketch: el ESP32 envía el valor
   crudo, así que se restan **una sola vez**. Esos -8.5 °C y -6 % **están por verificar** con un termómetro y un
   higrómetro de referencia; sin la variable, se guarda el valor crudo.

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
