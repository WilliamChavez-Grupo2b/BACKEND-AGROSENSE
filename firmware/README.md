# Firmware AgroSense (Arduino UNO R4 WiFi)

Es el dispositivo final de campo. Lee el suelo y el aire cada 30 s, envía cada lectura al manejador de eventos
(`POST /events`, el mismo contrato que usa el simulador de `raspberry/`) y acciona el relé de la bomba. El simulador en
Python se conserva para probar sin placa.

## Lista de materiales

| Parte | Notas |
|---|---|
| Arduino UNO R4 WiFi | Lógica de 5 V; 256 kB de flash y 32 kB de RAM |
| Sensor capacitivo de humedad de suelo v1.2 o similar | Alimentación de 3,3 a 5 V; salida analógica |
| DHT22, o SHT31 con conector Qwiic | Temperatura y humedad del aire. Elige uno |
| Módulo de relé de 1 canal, de 5 V, con optoacoplador | El contacto debe soportar la corriente de arranque de la bomba |
| Bomba o electroválvula | **Con fuente propia**, nunca alimentada desde la placa |
| Fuente externa de la bomba | Según la bomba. Comparte tierra con la placa solo si el relé no está aislado |
| Pulsador | Riego manual |
| Diodo de protección (1N4007) | En paralelo con una bomba o válvula de corriente continua, en inversa |

## Cableado

| Parte | Pin |
|---|---|
| Salida del sensor de suelo | `A0` |
| Datos del DHT22 (por defecto) | `D4` |
| SHT31 (alternativa) | Conector Qwiic; descomenta `AGRO_USE_SHT31` en `agrosense.ino` |
| Control del relé | `D7`. Se asume activo en bajo; si no, cambia `RELAY_ACTIVE_LOW` en `src/ArduinoPorts.h` y en `calibrate_soil.ino` |
| Pulsador | Entre `D2` y `GND` (usa la resistencia interna) |

El contacto del relé abre la línea de la fuente externa hacia la bomba: la corriente de la bomba nunca pasa por la placa.
Si la red es de 110/220 V, ese tramo debe montarlo alguien cualificado. Antes de conectar la carga, prueba el relé sin ella.

## Bibliotecas

Paquete de placas **Arduino UNO R4 Boards** (`arduino:renesas_uno`, incluye `WiFiS3`), **DHT sensor library**,
**Adafruit Unified Sensor** y **Adafruit SHT31 Library**. El firmware no usa `ArduinoHttpClient` ni `ArduinoJson`: la
primera espera bloqueando la respuesta, que es justo lo que no puede pasar aquí, y la respuesta se lee sin ellas. Se
instalan en CI solo para comprobar que conviven.

## Puesta en marcha

1. Registra el dispositivo con `POST /api/devices`; la llave se muestra una sola vez.
2. Copia `agrosense/arduino_secrets.h.example` como `agrosense/arduino_secrets.h` y complétalo. Git ignora ese archivo.
3. Calibra el suelo: sube `calibrate_soil/calibrate_soil.ino`, abre el monitor serie a 115200 y anota `raw:` con el sensor
   en tierra seca y en agua. Pon esos valores en `soilDryRaw` y `soilWetRaw` (`src/DeviceConfig.h`). **Los que trae son de
   partida, no medidos:** sin calibrar, cada porcentaje y cada decisión tomada con él son incorrectos.
4. Abre `agrosense/agrosense.ino` en el IDE y súbelo.

Con `AGRO_SIMULATED_HARDWARE` descomentado funciona sin nada conectado: el Wi-Fi y el backend son reales, los sensores y
la bomba no.

## Seguridad de la bomba

Ninguna de estas protecciones depende de la red. Los valores son constantes en `src/Irrigation.h`.

| Protección | Dónde |
|---|---|
| Máximo absoluto de **5 minutos** encendida, sea cual sea la orden | `TimedDriver`, con `MAX_PUMP_ON_MS` |
| Máximo de **30 minutos por día** | `PumpGuard`, con `MAX_PUMP_MS_PER_DAY` |
| Al menos **5 minutos** entre riegos, contados también desde el arranque | `PumpGuard`, con `MIN_PUMP_GAP_MS` |
| Apagada tras cualquier reinicio; el nivel del pin se fija antes de su dirección para evitar un pulso | `setup()` y `begin()` |
| Una interrupción a 10 Hz hace cumplir la hora de apagado aunque `loop()` esté bloqueado | `startPumpWatch` |
| Enviar no detiene el programa: la respuesta se lee poco a poco en cada vuelta de `loop()` | `HttpBackend` |

**Lo que estas protecciones no cubren:**

- `connect()` sí bloquea, hasta 8 s, dentro de `WiFiS3`, porque la placa habla con su módulo Wi-Fi por comandos AT. La
  interrupción cubre la bomba, pero el pulsador no responde durante ese tiempo.
- Si el microcontrolador se cuelga por completo, la interrupción también cae y el relé queda como estaba. No se activó el
  perro guardián porque su máximo en esta placa, 5,6 s, es menor que lo que tarda una conexión. Para una bomba que no deba
  quedar encendida en ningún caso hace falta un temporizador externo.
- Los contadores están en RAM: un reinicio borra el total del día.

Con el mínimo entre riegos contado desde el arranque, **enciende la placa 5 minutos antes de una demostración**.

## Órdenes de riego

Con `IRRIGATION_ACTUATOR=relay` en el backend, cada orden llega en la respuesta al siguiente `POST /events` del
dispositivo, como máximo 30 s después:

```json
{"command": {"id": 12, "type": "irrigate", "source": "AI", "minutes": 7, "confidence": 0.85}}
```

El dispositivo informa del resultado en su siguiente evento con `"ack": {"command_id": 12, "result": "started"}` o
`"refused"`, y el backend lo guarda en `irrigation_dispatches`. Una orden sin entregar caduca a los 5 minutos y nunca se
entrega dos veces.

- Una orden de la IA se rechaza sin lectura válida de suelo o con el suelo al 85 % o más. Una orden de una persona se
  obedece, dentro de los límites de la tabla.
- Una orden de más de 5 minutos se ejecuta 5.
- El pulsador arranca un riego y, si la bomba ya funciona, la detiene.

## Red y Render

- **HTTPS:** `WiFiSSLClient` valida el certificado con las raíces que trae el módulo Wi-Fi. Si la conexión falla, actualiza
  el firmware del módulo con el IDE (*Tools > Firmware Updater*). No se ha comprobado contra el servicio real sin placa.
- **Render dormido:** cada respuesta se espera hasta 45 s. Sin respuesta, o con 502, 503 o 504, reintenta cada 15 s durante
  3 minutos, lo que tarda en despertar, y después con espera creciente hasta 5 minutos. El manejador de eventos también
  duerme: pueden tener que despertar dos servicios.
- **Búfer:** guarda 16 eventos en RAM, unos 8 minutos. Si se llena se pierde el más antiguo, y **un corte de luz los
  pierde todos**. El monitor serie muestra cada minuto `en cola`, `perdidos` y `rechazados`.

## Pruebas

```bash
sh firmware/test/run-tests.sh   # la lógica, en el PC, con g++
sh firmware/test/compile.sh     # el sketch real, con arduino-cli y sus tres variantes
```

Compilado el 1 de noviembre de 2026 con `arduino:renesas_uno` 1.6.0: 33 % de flash (87 440 bytes) y 46 % de RAM
(15 080 bytes) con DHT22; cifras similares con SHT31 y hardware simulado.
