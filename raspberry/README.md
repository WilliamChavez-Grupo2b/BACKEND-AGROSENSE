# Dispositivo de campo (Raspberry Pi) de AgroSense

`simulator.py` es el programa que corre en la Raspberry Pi: mide cada pocos segundos y envía las lecturas
por wifi, con HTTP, al servicio de eventos de AgroSense (`POST /events`). Sirve igual en un PC de pruebas,
donde simula los sensores.

- Cada evento lleva la hora en que se midió, así que uno que llega tarde conserva su hora.
- Si el servicio no responde o falla (sin wifi, servicio dormido, 502), reintenta con espera creciente
  (1 s, 2 s, 4 s… hasta `MAX_BACKOFF_S`) y, si sigue sin poder, guarda el evento en una **cola en disco**
  (`queue.jsonl`). En cuanto el servicio vuelve, los envía primero, del más antiguo al más nuevo.
- Si el servicio rechaza el evento por algo que reintentar no arregla (400 datos no válidos, 401 llave
  incorrecta, 409…), lo registra como error y lo descarta.
- La llave del dispositivo no se escribe en el registro.

## Antes de empezar: el dispositivo y su llave

Cada Raspberry pertenece a un cultivo y tiene su propia llave. Se registra en el backend con una cuenta
`ADMIN` o `FARMER`, que recibe la llave **una sola vez**:

```bash
curl -X POST https://<backend>/api/devices \
  -H "Authorization: Bearer <token de la cuenta>" -H "Content-Type: application/json" \
  -d '{"cropId": 1, "deviceCode": "RPI-LOTE-NORTE"}'
# {"idDevice": 2, "deviceCode": "RPI-LOTE-NORTE", ..., "deviceKey": "<guárdala>"}
```

Las lecturas se guardan en los sensores activos de ese cultivo: `soil_moisture` en el de humedad del suelo,
`ph` en el de pH, etc. Si el cultivo tiene dos sensores del mismo tipo, el evento debe decir cuál (ver
«Sensores reales»).

En local, el dispositivo de ejemplo `RPI-DEMO-01` (cultivo Café) recibe al arrancar el backend la llave de
`SEED_DEVICE_KEY`.

## En un PC de pruebas

```bash
cd raspberry
python -m venv .venv
.venv/bin/pip install -r requirements.txt          # Windows: .venv\Scripts\pip
cp simulator.example.env simulator.env              # y escribe DEVICE_KEY
.venv/bin/python simulator.py                        # una medición cada 10 s; Ctrl+C para parar
.venv/bin/python simulator.py --once                 # una sola
```

## En la Raspberry Pi

Probado con la versión de Python de Raspberry Pi OS (3.11 o posterior). Conéctala al wifi de la finca.

```bash
sudo apt update && sudo apt install -y python3-venv git
git clone <repositorio> agrosense && cd agrosense/raspberry
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp simulator.example.env simulator.env
nano simulator.env        # EVENTS_URL, DEVICE_ID y DEVICE_KEY
chmod 600 simulator.env   # solo tu usuario puede leer la llave
.venv/bin/python simulator.py --once
```

Para que arranque sola al encender y se reinicie si falla, crea `/etc/systemd/system/agrosense.service`
(cambia `pi` y la ruta por los tuyos):

```ini
[Unit]
Description=AgroSense: lecturas de sensores
After=network-online.target
Wants=network-online.target

[Service]
User=pi
WorkingDirectory=/home/pi/agrosense/raspberry
ExecStart=/home/pi/agrosense/raspberry/.venv/bin/python simulator.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now agrosense
journalctl -u agrosense -f     # ver lo que envía
```

La cola queda en `queue.jsonl` dentro de esa carpeta y sobrevive a un reinicio o a un corte de luz (se
reescribe de una vez).

## Sensores reales

`Sensors.measure()` devuelve un diccionario con las medidas, por ejemplo
`{"soil_moisture": 41.2, "air_temperature": 23.5, "ph": 6.4}`. En la Raspberry, sustitúyelo por la lectura
de tus sensores (por ejemplo, un ADS1115 por I²C para la humedad y un DS18B20 para la temperatura). Los
nombres posibles son los tipos de sensor de AgroSense en minúsculas: `soil_moisture`, `air_temperature`,
`soil_temperature`, `ph`, `conductivity`, `light`, `rain_gauge` y `relative_humidity`.

Si el cultivo tiene varios sensores del mismo tipo, cada medida indica el suyo:

```python
{"soil_moisture": {"value": 41.2, "sensor_code": "AS-001"}}
```

Sin eso, el servicio responde 400 diciendo qué sensores hay y cómo elegir.

## Configuración

En `simulator.env` (formato `CLAVE=valor`) o como variables de entorno, que tienen prioridad:

| Variable | Por defecto | Descripción |
|---|---|---|
| `EVENTS_URL` | `http://localhost:8100/events` | Webhook del servicio de eventos |
| `DEVICE_ID` | `RPI-DEMO-01` | Código del dispositivo |
| `DEVICE_KEY` | obligatoria | Llave del dispositivo |
| `INTERVAL_S` | 10 | Segundos entre mediciones |
| `READINGS` | `soil_moisture,air_temperature,ph` | Medidas simuladas |
| `QUEUE_FILE`, `MAX_QUEUE` | `queue.jsonl`, 10000 | Cola en disco; pasado el límite se descartan los más antiguos |
| `TIMEOUT_S`, `ATTEMPTS` | 10, 4 | Espera de cada envío e intentos antes de dejar el evento en la cola |
| `FIRST_BACKOFF_S`, `MAX_BACKOFF_S` | 1, 60 | Primera espera entre intentos y la más larga |
| `LOG_LEVEL` | `INFO` | Nivel del registro |

En el plan gratuito de Render el servicio de eventos y el backend se duermen tras un rato sin uso: la
primera petición puede tardar un minuto o responder 502. El simulador lo trata como un corte: reintenta y,
si hace falta, guarda en la cola.

## Pruebas

```bash
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest
```

Contra un servidor local en el papel del servicio de eventos: envío con la llave y la hora, reintentos con
espera creciente, cola en disco cuando no hay conexión y su envío en orden al volver, eventos rechazados que
se descartan, límite de la cola, rango de los sensores simulados y lectura de la configuración.
