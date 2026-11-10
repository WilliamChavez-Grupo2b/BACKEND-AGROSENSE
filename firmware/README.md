# Firmware AgroSense por HTTP (ESP32), alternativa no usada

> **El dispositivo real no corre este firmware.** El ESP32 instalado corre `esp32-tcp/` (servidor TCP en su propia red
> `PLANTICAS`), y el backend lo lee por TCP: ver `esp32-tcp/README.md` y la decisión 40 de `docs/ARQUITECTURA.md`. Este
> firmware, cliente HTTP con relé y con los patrones de diseño del dispositivo, se conserva como alternativa: no está
> cargado en la placa ni probado en ella.

Es el dispositivo de campo: una placa **ESP32 Dev Module** con el core `esp32` de Espressif (2.x o 3.x). Lee el suelo y
el aire cada 30 s, envía cada lectura al manejador de eventos (`POST /events` con `X-Device-Key`) y acciona el relé de la
bomba con las órdenes que llegan en la respuesta. Para probar el sistema sin placa está `tools/e2e/run_e2e.py`, que habla
el mismo contrato.

## Lista de materiales

| Parte | Notas |
|---|---|
| ESP32 Dev Module (ESP32-WROOM-32) | Lógica de **3,3 V**; Wi-Fi de 2,4 GHz solamente |
| Sensor capacitivo de humedad de suelo v1.2 o similar | Aliméntalo a **3,3 V** para que su salida no pase de 3,3 V |
| DHT22, o SHT31 por I2C | Temperatura y humedad del aire. Elige uno |
| Módulo de relé de 1 canal con optoacoplador | Ver la advertencia del relé de 5 V. El contacto debe soportar el arranque de la bomba |
| Bomba o electroválvula | **Con fuente propia**, nunca alimentada desde la placa |
| Fuente externa de la bomba | Según la bomba |
| Pulsador (opcional) | Riego manual |
| Diodo de protección (1N4007) | En paralelo con una bomba o válvula de corriente continua, en inversa |
| Resistencia de 10 kΩ (recomendada) | Pull-up del DHT22 si su módulo no la trae |

## Cableado

| Parte | Pin del ESP32 | Notas |
|---|---|---|
| Salida analógica del sensor de suelo | `GPIO34` | ADC1, solo entrada. VCC a 3,3 V, GND a GND |
| Datos del DHT22 (por defecto) | `GPIO4` | VCC a 3,3 V; pull-up de 10 kΩ a 3,3 V si el módulo no la trae |
| SHT31 (alternativa) | `GPIO21` (SDA), `GPIO22` (SCL) | Descomenta `AGRO_USE_SHT31` en `agrosense.ino` |
| Entrada del relé (`IN`) | `GPIO26` | Activo en bajo. Si la bomba funciona cuando debería estar apagada, cambia `RELAY_ACTIVE_LOW` en `src/ArduinoPorts.h` y en `calibrate_soil.ino` |
| Pulsador (opcional) | Entre `GPIO27` y `GND` | Usa el pull-up interno; sin pulsador el pin queda en reposo |
| GND | `GND` | **Común** a la placa, los sensores, el módulo de relé y la fuente de la bomba |

Los pines están en `src/ArduinoPorts.h` (`PIN_SOIL`, `PIN_DHT`, `PIN_RELAY`, `PIN_BUTTON`). Si tu cableado es distinto,
cámbialos ahí y en `calibrate_soil/calibrate_soil.ino`, respetando las advertencias.

El contacto del relé (COM y NO) abre la línea de la fuente externa hacia la bomba: la corriente de la bomba nunca pasa
por la placa. Si la red es de 110/220 V, ese tramo debe montarlo alguien cualificado. Antes de conectar la carga, prueba
el relé sin ella.

### Advertencias

- **3,3 V.** Ningún pin del ESP32 admite 5 V. Un sensor alimentado a 5 V puede entregar más de 3,3 V y dañar la placa.
- **Solo ADC1 para el suelo** (`GPIO32` a `GPIO39`). Los pines de ADC2 (`GPIO0`, `2`, `4`, `12` a `15`, `25` a `27`) no
  leen mientras el Wi-Fi está activo. El DHT22 en `GPIO4` y el relé en `GPIO26` son digitales, así que no les afecta.
- **Pines prohibidos.** `GPIO0`, `2`, `5`, `12` y `15` fijan el modo de arranque: algo conectado ahí puede impedir que la
  placa arranque o que se pueda programar. `GPIO6` a `GPIO11` son la memoria flash: no los uses nunca.
- **Relé de 5 V.** Muchos módulos de 5 V activos en bajo no se apagan con los 3,3 V del ESP32: el LED del optoacoplador
  sigue viendo tensión y el relé queda cerrado. Usa un módulo que acepte 3,3 V en `IN`, o uno con optoacoplador y puente
  `JD-VCC`: quita el puente, alimenta `VCC` con 3,3 V del ESP32 y `JD-VCC` con 5 V aparte. **Comprueba con un
  multímetro** que el relé se abre con `GPIO26` en alto.
- **Bomba con fuente propia y GND común.** La bomba nunca se alimenta del pin de 5 V ni de 3,3 V de la placa. La tierra
  de su fuente se une a la del ESP32, salvo que el módulo de relé esté totalmente aislado (puente `JD-VCC` quitado y fuente
  separada); en ese caso se une solo la tierra del lado del relé.
- **Al arrancar.** Antes de `setup()` el `GPIO26` es una entrada sin conectar; con un módulo con optoacoplador eso deja el
  relé abierto. El firmware fija el nivel de apagado antes de convertir el pin en salida (`configureRelayPinOff()`).
  **Mide con el multímetro** que el relé no se cierra durante el arranque ni al pulsar `EN`.

## Bibliotecas

- Core **esp32** de Espressif. En el IDE: *Archivo > Preferencias > Gestor de URLs adicionales de tarjetas* con
  `https://espressif.github.io/arduino-esp32/package_esp32_index.json`, y después instala «esp32» en el gestor de
  tarjetas. Placa: **ESP32 Dev Module** (`esp32:esp32:esp32`). Trae `WiFi`, `HTTPClient` y `WiFiClientSecure`.
- **DHT sensor library** y **Adafruit Unified Sensor**, de Adafruit.
- **Adafruit SHT31 Library**, solo si usas el SHT31 (el sketch la incluye siempre, así que instálala igual).

No hace falta `ArduinoJson`: el evento se escribe con `ReadingJsonBuilder` y la orden de la respuesta se lee con
`parseCommand`, sin memoria dinámica.

## Puesta en marcha

1. Registra el dispositivo con `POST /api/devices`. La llave se muestra una sola vez.
2. Copia `agrosense/arduino_secrets.example.h` como `agrosense/arduino_secrets.h` y complétalo: red de 2,4 GHz, código
   y llave del dispositivo y dirección del manejador de eventos. Git ignora ese archivo.
3. Calibra el suelo (sección siguiente).
4. Abre `agrosense/agrosense.ino` en el IDE, elige **ESP32 Dev Module** y el puerto, y súbelo.
5. Abre el monitor serie a 115200. Cada minuto muestra `en cola`, `perdidos` y `rechazados`.

Con `AGRO_SIMULATED_HARDWARE` descomentado funciona sin nada conectado: el Wi-Fi y el backend son reales, los sensores y
la bomba no. Sus valores de suelo son inventados para el banco y no calibran nada.

## Calibrar el sensor de suelo

El firmware trae `AGRO_SOIL_DRY_RAW` y `AGRO_SOIL_WET_RAW` en 0. **Dos valores iguales significan «sin calibrar»**: el
dispositivo no envía la humedad del suelo, lo avisa en el monitor serie, y una orden de riego de la IA se rechaza porque
no hay lectura válida. No inventes estos números: cada sensor, cada cable y cada suelo dan cuentas distintas.

1. Sube `calibrate_soil/calibrate_soil.ino`. Mantiene el relé apagado todo el tiempo.
2. Abre el monitor serie a 115200. Cada medio segundo muestra `raw:` con un valor de 0 a 4095 (12 bits, atenuación de
   11 dB).
3. Clava el sensor **en tierra seca** del propio cultivo hasta la línea que marca su profundidad. Espera a que el valor se
   estabilice y anótalo: es `AGRO_SOIL_DRY_RAW`.
4. Mételo **en agua**, sin mojar la electrónica de arriba, y anota el valor estable: es `AGRO_SOIL_WET_RAW`. En un sensor
   capacitivo suele ser menor que el seco; el firmware acepta cualquiera de los dos sentidos.
5. Repite dos o tres veces y usa el promedio. Si ves `0` o `4095`, el sensor está desconectado o recibe más de 3,3 V.
6. Escribe los dos números en `arduino_secrets.h` y sube de nuevo `agrosense.ino`.

Es distinto de `SENSOR_CALIBRATION` del backend, que corrige un tipo de sensor entero contra una referencia.

## Seguridad de la bomba

Ninguna de estas protecciones depende de la red. Los valores son constantes en `src/Irrigation.h`.

| Protección | Dónde |
|---|---|
| Máximo absoluto de **5 minutos** encendida, sea cual sea la orden (`MAX_PUMP_ON_MS`). El backend rechaza más minutos de una persona y recorta los de la IA: mantén `IRRIGATION_MAX_RUN_MINUTES` igual a esta constante | `TimedDriver` |
| Máximo de **30 minutos por día** | `PumpGuard`, con `MAX_PUMP_MS_PER_DAY` |
| Al menos **5 minutos** entre riegos, contados también desde el arranque | `PumpGuard`, con `MIN_PUMP_GAP_MS` |
| **Apagada al reiniciar:** el nivel de apagado se escribe en el registro antes de que el pin sea salida, y `begin()` la apaga otra vez | `configureRelayPinOff()`, `setup()` y `begin()` |
| **Apagada si se pierde la red** con la bomba funcionando: una orden remota no sigue si nadie puede detenerla desde lejos. Un riego empezado con el pulsador sin red sigue hasta su hora de apagado | `AgroSenseDevice::tick()` |
| Un temporizador propio (`esp_timer`, 10 veces por segundo) hace cumplir la hora de apagado aunque `loop()` esté bloqueado | `startPumpWatch` |
| La petición HTTP corre en su propia tarea: `loop()`, el pulsador y la bomba no esperan a la red. Si la petición pasa de 120 s, la placa apaga la bomba y se reinicia | `HttpBackend` |
| **Perro guardián** (`esp_task_wdt`, 10 s) sobre `loop()`. Funciona con el core 2.x y el 3.x (`ESP_ARDUINO_VERSION_MAJOR`). Si `loop()` se cuelga, la placa se reinicia y la bomba vuelve apagada | `startWatchdog`, `feedWatchdog` |

**Lo que estas protecciones no cubren:**

- Un fallo eléctrico, como un relé pegado, queda fuera de todo esto. Para una bomba que no deba quedar encendida en
  ningún caso, **pon un temporizador por hardware en serie con el relé**, por ejemplo un relé temporizado de retardo a la
  desconexión ajustado a 6 minutos.
- Los contadores están en RAM: un reinicio borra el total del día.
- Nada de esto se ha probado todavía en la placa. Provoca un cuelgue y un corte de Wi-Fi con el relé sin carga y comprueba
  que se abre.

Con el mínimo entre riegos contado desde el arranque, **enciende la placa 5 minutos antes de una demostración**.

## Órdenes de riego

Con `IRRIGATION_ACTUATOR=relay` en el backend, cada orden llega en la respuesta al siguiente `POST /events` del
dispositivo, como máximo 30 s después:

```json
{"command": {"id": 12, "type": "irrigate", "source": "AI", "minutes": 7, "confidence": 0.85}}
```

El dispositivo informa en sus eventos siguientes: `"ack": {"command_id": 12, "result": "started"}`, y al terminar
`"result": "finished", "ran_seconds": 300`, o `"refused"`. El backend lo guarda en `irrigation_dispatches` y **corrige el
riego en el historial** con la duración real y el agua proporcional. Una orden rechazada o caducada queda sin agua. Si la
placa se reinicia durante un riego, ese `finished` no llega y el historial conserva lo pedido.

- Una orden de la IA se rechaza sin lectura válida de suelo o con el suelo al 85 % o más. Una orden de una persona se
  obedece, dentro de los límites de la tabla.
- El pulsador arranca un riego y, si la bomba ya funciona, la detiene.

## El evento

```json
{"device_id": "ESP32-01", "type": "reading", "ts": "2026-10-31T21:26:40-05:00",
 "data": {"soil_moisture": 31.5, "air_temperature": 24.1, "relative_humidity": 61.0}}
```

- `ts` sale de NTP (`configTime`, `pool.ntp.org` y `time.google.com`) y se escribe en la zona del campo, **-05:00**
  (`utcOffsetMinutes` en `src/DeviceConfig.h`). Hasta que NTP responde, el evento va sin `ts` y el backend pone la hora de
  llegada; esos eventos sí podrían repetirse en un reintento.
- **El firmware no mide pH** y no envía ese campo.
- Una medida que falla, o el suelo sin calibrar, simplemente no aparece en `data`.

## Red y Render

- **HTTPS:** `HTTPClient` con `WiFiClientSecure`. Con `SECRET_EVENTS_CA_CERT` vacío, la conexión va cifrada pero **no
  comprueba el certificado del servidor** (`setInsecure()`), y el monitor serie lo avisa. Para comprobarlo, copia en
  `SECRET_EVENTS_CA_CERT` el certificado raíz del manejador de eventos en formato PEM. Para verlo:
  `openssl s_client -connect <host>:443 -showcerts` y toma el último certificado de la cadena. Si Render cambia de
  autoridad, hay que actualizarlo.
- **Render dormido:** cada respuesta se espera hasta 45 s. Sin respuesta, o con 502, 503 o 504, reintenta cada 15 s durante
  3 minutos, lo que tarda en despertar, y después con espera creciente hasta 5 minutos. El manejador de eventos también
  duerme: pueden tener que despertar dos servicios.
- **Wi-Fi:** `WifiManager` reintenta con espera creciente (5 s, 10 s… hasta 60 s); la reconexión automática del core está
  apagada para que no compitan.
- **Búfer:** guarda 16 eventos en RAM, unos 8 minutos. Si se llena se pierde el más antiguo, y **un corte de luz los
  pierde todos**.

## Pruebas

```bash
sh firmware/test/run-tests.sh   # la lógica, en el PC, con g++
sh firmware/test/compile.sh     # el sketch real para esp32:esp32:esp32, en sus tres variantes, y calibrate_soil
```

`compile.sh` usa `arduino-cli`; `ARDUINO_CLI` elige otro binario y `ARDUINO_CONFIG` le pasa un `--config-file`.

## Pendiente con la placa real

- Medir con el multímetro que `GPIO26` deja el relé abierto desde el arranque, al pulsar `EN` y al subir un sketch.
- Calibrar el suelo y comprobar porcentajes en tierra seca, húmeda y encharcada.
- Probar el relé y la bomba **primero sin carga**: una orden manual, el máximo de 5 minutos, un corte de Wi-Fi durante un
  riego y un reinicio durante un riego.
- Comprobar HTTPS contra el manejador de eventos desplegado, con y sin certificado raíz.
- Provocar un cuelgue de `loop()` para ver actuar el perro guardián.
