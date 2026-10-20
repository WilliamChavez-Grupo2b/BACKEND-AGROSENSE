# Dispositivo de campo (Raspberry Pi) de AgroSense

`simulator.py` es el programa del dispositivo. En una Raspberry Pi lee los sensores reales, un **DHT22**
(temperatura y humedad del aire) y un **sensor capacitivo de humedad del suelo** a través de un conversor
**MCP3008**; en cualquier otro equipo los simula. Cada 30 s envía la lectura por wifi, con HTTP, al servicio
de eventos de AgroSense (`POST /events`).

- **Horario fijo, sin envíos solapados.** Mide a los 0, 30, 60… segundos. Si un ciclo se alarga (el servicio
  tarda en responder), la medición siguiente se salta, no se envían dos a la vez. Un bloqueo de archivo
  impide que corran dos copias del programa sobre la misma cola.
- **Nunca se envía un valor vacío.** El DHT22 falla a menudo una lectura: se repite hasta `DHT_ATTEMPTS`
  veces, con 2 s entre una y otra, y se descartan los valores imposibles. Lo que no se pudo leer se deja
  fuera del evento; si no se leyó nada, no se envía nada.
- **Cola en SQLite (`queue.sqlite3`).** Cada lectura se guarda primero en la cola y luego se envía, de la
  más antigua a la más nueva; sale de la cola cuando el servicio la guardó (200) o la rechazó por algo que
  reintentar no arregla (400, 401, 409…). Sin wifi, con el servicio dormido o con un 502, se queda y se
  reintenta con espera creciente (1 s, 2 s, 4 s… hasta `MAX_BACKOFF_S`). Sobrevive a un reinicio o un corte
  de luz. Si existe la cola anterior (`queue.jsonl`), sus eventos pasan a la nueva al arrancar.
- **Espera de 45 s por respuesta** (`TIMEOUT_S`): más de lo que puede tardar el servicio de eventos, que
  espera hasta 30 s al backend y lo reintenta 3 veces si no guardó nada.
- La llave del dispositivo no se escribe en el registro.

| Archivo | Para qué |
|---|---|
| `simulator.py` | El programa: configuración, ciclo, cola y envío |
| `hardware.py` | Lectura del DHT22 y del MCP3008, y detección del hardware |
| `calibrate_soil.py` | Calibración del sensor de suelo (seco y en agua) |
| `agrosense.service` | Unidad de systemd para que arranque sola |
| `simulator.example.env` | Plantilla de la configuración (`simulator.env`) |

## Antes de empezar: el dispositivo y su llave

Cada Raspberry pertenece a un cultivo y tiene su propia llave. Se registra en el backend con una cuenta
`ADMIN` o `FARMER`, que recibe la llave **una sola vez**:

```bash
curl -X POST https://<backend>/api/devices \
  -H "Authorization: Bearer <token de la cuenta>" -H "Content-Type: application/json" \
  -d '{"cropId": 1, "deviceCode": "RPI-LOTE-NORTE"}'
# {"idDevice": 2, "deviceCode": "RPI-LOTE-NORTE", ..., "deviceKey": "<guárdala>"}
```

Cada medida se guarda en el sensor activo de ese tipo del cultivo, así que el cultivo necesita uno de
humedad del suelo (`SOIL_MOISTURE`), uno de temperatura del aire (`AIR_TEMPERATURE`) y uno de humedad
relativa (`RELATIVE_HUMIDITY`). Si falta alguno, el servicio rechaza el evento (400) diciendo cuál. Si el
cultivo tiene dos del mismo tipo, el evento debe decir cuál (ver «Varios sensores del mismo tipo»).

En local, el dispositivo de ejemplo `RPI-DEMO-01` está en el cultivo Café, que tiene los tres (`AS-001`,
`AS-002` y `AS-007`), y recibe al arrancar el backend la llave de `SEED_DEVICE_KEY`. `run-local.ps1` la deja
en `%LOCALAPPDATA%\agrosense-local\device-key.txt`.

## 1. Probar en simulación (cualquier PC)

Fuera de una Raspberry Pi, `SENSORS=auto` (el valor por defecto) simula los sensores.

```bash
cd raspberry
python -m venv .venv
.venv/bin/pip install -r requirements.txt          # Windows: .venv\Scripts\pip; en un PC solo instala requests
cp simulator.example.env simulator.env              # y escribe DEVICE_KEY
.venv/bin/python simulator.py --once                 # una sola medición
.venv/bin/python simulator.py                        # una cada 30 s; Ctrl+C para parar
```

Lo que debe aparecer:

```
INFO Not a Raspberry Pi: simulated sensors (soil_moisture, air_temperature, relative_humidity)
INFO Device RPI-DEMO-01 sending to http://localhost:8100/events every 30.0 s
INFO Sent: {'soil_moisture': 54.1, 'air_temperature': 22.3, 'relative_humidity': 70.4}
```

Para ver la cola: para el servicio de eventos, deja correr el programa (`Event kept in the queue (3
waiting)`) y vuelve a arrancarlo (`Sent 3 of the 3 events that were waiting in the queue`).

## 2. Cableado

Todo va a **3,3 V**. Los GPIO de la Raspberry no admiten 5 V: un DHT22 o un MCP3008 alimentados a 5 V
devolverían 5 V a sus patillas y las pueden dañar.

```
 Raspberry Pi (patillas del conector de 40)        Sensores
 ────────────────────────────────────────────      ─────────────────────────────────
  1  3,3 V ─────┬────────────┬──────────────────── DHT22 VCC (patilla 1)
                │            └──[10 kΩ]──┐
  7  GPIO4  ────┼────────────────────────┴──────── DHT22 DATA (patilla 2)
  9  GND    ────┼───────────────────────────────── DHT22 GND (patilla 4)    (patilla 3: sin conectar)
                │
 17  3,3 V ─────┼──┬──┬──────────────────────────── MCP3008 VDD (16) y VREF (15)
                │  │  └──────────────────────────── Sensor de suelo VCC
 19  GPIO10 MOSI ──┼─────────────────────────────── MCP3008 DIN (11)
 21  GPIO9  MISO ──┼─────────────────────────────── MCP3008 DOUT (12)
 23  GPIO11 SCLK ──┼─────────────────────────────── MCP3008 CLK (13)
 24  GPIO8  CE0  ──┼─────────────────────────────── MCP3008 CS/SHDN (10)
 25  GND   ────────┴──┬──────────────────────────── MCP3008 AGND (14) y DGND (9)
                      └──────────────────────────── Sensor de suelo GND
                                                     Sensor de suelo AOUT ── MCP3008 CH0 (1)
```

| Componente | Patilla | Raspberry Pi | Notas |
|---|---|---|---|
| DHT22 | VCC | 1 (3,3 V) | Admite de 3,3 a 5,5 V; a 3,3 V la línea de datos es segura para el GPIO |
| DHT22 | DATA | 7 (GPIO4) | Resistencia de 10 kΩ entre DATA y 3,3 V. Los módulos de 3 patillas ya la llevan |
| DHT22 | GND | 9 (GND) | |
| MCP3008 | VDD (16), VREF (15) | 17 (3,3 V) | VREF fija el máximo: 3,3 V = 1023. Un condensador de 100 nF entre VDD y GND, cerca del chip, reduce el ruido |
| MCP3008 | AGND (14), DGND (9) | 25 (GND) | |
| MCP3008 | CLK (13) | 23 (GPIO11, SCLK) | SPI0 |
| MCP3008 | DOUT (12) | 21 (GPIO9, MISO) | |
| MCP3008 | DIN (11) | 19 (GPIO10, MOSI) | |
| MCP3008 | CS/SHDN (10) | 24 (GPIO8, CE0) | `ADC_SPI_DEVICE=0` |
| Sensor de suelo | VCC / GND | 3,3 V / GND | Capacitivo (v1.2 o v2.0). Los resistivos se corroen en semanas |
| Sensor de suelo | AOUT | MCP3008 CH0 (1) | `ADC_CHANNEL=0`; si usas otro canal, cámbialo |

- El sensor capacitivo da **más tensión cuanto más seco**; la calibración lo tiene en cuenta en cualquier
  sentido.
- Mantén el cable del DHT22 corto (menos de 2 m a 3,3 V) y el sensor a la sombra y ventilado: al sol mide
  la temperatura de su carcasa.
- Mete en la tierra solo la parte del sensor de suelo por debajo de su línea; la electrónica de arriba no
  debe mojarse.

## 3. En la Raspberry Pi

Escrito para una **Raspberry Pi 4 con Raspberry Pi OS de 64 bits (Bookworm, Python 3.11)**. En una Pi 5 es
igual: `rpi-lgpio` y `python3-lgpio` sirven para las dos. Conéctala al wifi de la finca.

```bash
sudo apt update
sudo apt install -y python3-venv python3-dev python3-lgpio libgpiod2
sudo raspi-config nonint do_spi 0        # activa SPI (o raspi-config → Interface Options → SPI)
sudo reboot
ls /dev/spidev0.0                        # debe existir tras reiniciar
```

Copia la carpeta `raspberry/` a la Raspberry, por ejemplo desde el PC con
`scp -r raspberry pi@<ip-de-la-raspberry>:~/agrosense/`, y en la Raspberry:

```bash
cd ~/agrosense/raspberry
python3 -m venv --system-site-packages .venv    # para ver el python3-lgpio del sistema
.venv/bin/pip install -r requirements.txt       # requests, adafruit-blinka, el DHT22, spidev y rpi-lgpio
cp simulator.example.env simulator.env
nano simulator.env        # EVENTS_URL, DEVICE_ID y DEVICE_KEY
chmod 600 simulator.env   # solo tu usuario puede leer la llave
```

El usuario necesita los grupos `gpio` y `spi` (el usuario `pi` ya los tiene; si no:
`sudo usermod -aG gpio,spi $USER` y vuelve a entrar).

### Calibrar el sensor de suelo

```bash
.venv/bin/python calibrate_soil.py
```

Pide el sensor seco al aire y después metido en un vaso de agua, toma 20 lecturas de cada uno e imprime:

```
SOIL_DRY_RAW=812
SOIL_WET_RAW=398
```

Copia esas dos líneas en `simulator.env`. Para comprobarlo ya en la tierra:
`.venv/bin/python calibrate_soil.py --watch` muestra la lectura y su porcentaje cada segundo. Si seco y
mojado se diferencian en menos de 100, el script lo dice: revisa el cableado.

### Probar con los sensores

```bash
.venv/bin/python simulator.py --once
```

Lo que debe aparecer:

```
INFO Raspberry Pi 4 Model B Rev 1.5: DHT22 on GPIO4, MCP3008 on SPI0 CE0 channel 0 (dry 812, wet 398)
INFO Sent: {'soil_moisture': 47.3, 'air_temperature': 23.8, 'relative_humidity': 64.1}
```

En una Raspberry Pi el programa **nunca simula por su cuenta**: si falta una biblioteca, SPI está apagado o
no hay calibración, se detiene y dice qué falta, en vez de enviar valores inventados como si fueran de la
finca. Para probar sin sensores en la Raspberry, pon `SENSORS=simulated`.

| Mensaje | Qué hacer |
|---|---|
| `faltan la biblioteca adafruit-blinka…` | `.venv/bin/pip install -r requirements.txt` dentro de `.venv` |
| `faltan /dev/spidev0.0 (activa SPI…)` | `sudo raspi-config nonint do_spi 0` y reinicia |
| `No module named 'lgpio'` | `sudo apt install python3-lgpio` y crea `.venv` con `--system-site-packages` |
| `Faltan SOIL_DRY_RAW y SOIL_WET_RAW` | `calibrate_soil.py` |
| `DHT22 read failed (…)` alguna vez | Normal: se reintenta. Si es siempre: DATA en GPIO4 (patilla 7), la resistencia de 10 kΩ y los 3,3 V |
| `The soil sensor reads 0` o `1023` | AOUT no llega a CH0, o el sensor está alimentado a otra tensión que VREF |
| `Event rejected (400): … no tiene un sensor activo de relative_humidity` | El cultivo no tiene sensor de humedad relativa: créalo en la aplicación |

## 4. Arranque automático (systemd)

`agrosense.service` arranca el programa al encender, cuando hay red, y lo reinicia si se cae. Supone el
usuario `pi` y la carpeta `/home/pi/agrosense/raspberry`; si no son los tuyos, cambia `User=`,
`WorkingDirectory=` y `ExecStart=` antes de copiarlo.

```bash
sudo cp agrosense.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now agrosense
systemctl status agrosense
journalctl -u agrosense -f     # ver lo que mide y envía
```

Un error de configuración (sin `DEVICE_KEY`, sin calibrar, sin una biblioteca) termina con el código 2, que
el servicio **no** reinicia (`RestartPreventExitStatus=2`): `journalctl -u agrosense` dice qué falta.
Corrígelo y `sudo systemctl restart agrosense`. `calibrate_soil.py` se puede usar con el servicio en marcha;
`simulator.py` a mano no: para antes el servicio (`sudo systemctl stop agrosense`), porque el bloqueo de la
cola no deja correr dos copias.

**La hora.** La Raspberry no tiene reloj propio: tras encender sin internet, su hora es la del último
apagado hasta que se sincroniza. Cada lectura lleva la hora en que se midió, así que una medida tomada
antes de sincronizar queda con una hora antigua. Si importa, activa
`sudo systemctl enable systemd-time-wait-sync` y el servicio esperará a la hora correcta (`After=time-sync.target`).

## Varios sensores del mismo tipo

`measure()` devuelve un diccionario con las medidas, por ejemplo
`{"soil_moisture": 41.2, "air_temperature": 23.5, "relative_humidity": 64.1}`. Los nombres posibles son los
tipos de sensor de AgroSense en minúsculas: `soil_moisture`, `air_temperature`, `soil_temperature`, `ph`,
`conductivity`, `light`, `rain_gauge` y `relative_humidity`. Si el cultivo tiene varios sensores del mismo
tipo, cada medida indica el suyo: `{"soil_moisture": {"value": 41.2, "sensor_code": "AS-001"}}`. Sin eso, el
servicio responde 400 diciendo qué sensores hay.

## Configuración

En `simulator.env` (formato `CLAVE=valor`) o como variables de entorno, que tienen prioridad:

| Variable | Por defecto | Descripción |
|---|---|---|
| `EVENTS_URL` | `http://localhost:8100/events` | Webhook del servicio de eventos (también vale `/webhook/eventos`) |
| `DEVICE_ID` | `RPI-DEMO-01` | Código del dispositivo |
| `DEVICE_KEY` | obligatoria | Llave del dispositivo |
| `INTERVAL_S` | 30 | Segundos entre mediciones, con horario fijo |
| `SENSORS` | `auto` | `auto`: reales en una Raspberry Pi y simulados en otro equipo; `hardware`; `simulated` |
| `READINGS` | `soil_moisture,air_temperature,relative_humidity` | Medidas simuladas (también `ph`) |
| `DHT_GPIO`, `DHT_ATTEMPTS` | 4, 3 | Pin de datos del DHT22 (número GPIO) y lecturas antes de darlo por fallido |
| `ADC_SPI_BUS`, `ADC_SPI_DEVICE`, `ADC_CHANNEL` | 0, 0, 0 | SPI0, CE0 y canal del sensor de suelo en el MCP3008 |
| `SOIL_DRY_RAW`, `SOIL_WET_RAW` | obligatorias con sensores reales | Lectura del sensor seco y en agua (`calibrate_soil.py`) |
| `QUEUE_FILE`, `MAX_QUEUE` | `queue.sqlite3`, 10000 | Cola; pasado el límite se descartan los más antiguos |
| `TIMEOUT_S`, `ATTEMPTS` | 45, 4 | Espera de cada envío y fallos seguidos antes de dejar la cola para el ciclo siguiente |
| `FIRST_BACKOFF_S`, `MAX_BACKOFF_S` | 1, 60 | Primera espera entre intentos y la más larga |
| `LOG_LEVEL` | `INFO` | Nivel del registro |

En el plan gratuito de Render el servicio de eventos y el backend se duermen tras un rato sin uso: la
primera petición puede tardar un minuto o responder 502. El programa lo trata como un corte: la lectura
espera en la cola.

## Pruebas

```bash
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
```

Sin hardware: un servidor local hace de servicio de eventos, y el DHT22 y el SPI son objetos falsos. Cubren
el envío con la llave y la hora, reintentos con espera creciente, la cola SQLite (orden, límite, reinicio,
filas dañadas, paso desde `queue.jsonl`), eventos rechazados que se descartan, que no se envía nada vacío y
que una medida fallida queda fuera, el horario fijo y la medición saltada cuando un ciclo se alarga, el
bloqueo contra dos copias, la configuración y sus errores (código 2), la elección entre sensores reales y
simulados, los reintentos y valores imposibles del DHT22, la trama SPI y la mediana del MCP3008 y la
calibración. **La lectura con sensores reales no está probada en una Raspberry**: este código se escribió
y probó en un PC.
