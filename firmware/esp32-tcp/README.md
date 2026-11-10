# ESP32 PLANTICAS (dispositivo real, servidor TCP)

**Este es el programa que corre en el dispositivo de campo.** El ESP32 crea su propia red Wi-Fi (modo AP), abre un
servidor TCP en `192.168.4.1:8080` y escribe un bloque de texto con sus lecturas cada segundo. El backend se conecta a él
con `ESP32_TCP_ENABLED=true` (`backend/agrosense`, paquete `pattern/structural/adapter/esp32/`).

`firmware/agrosense/` es otra cosa: un firmware alternativo que envía por HTTP al manejador de eventos y maneja un relé.
**No está cargado en la placa ni se usa hoy.**

El sketch está tal como lo escribió el equipo. El único cambio es que el nombre y la clave de la red salen de
`arduino_secrets.h`, que no va al repositorio. Las compensaciones (-8.5 °C, -6 % de humedad, -76 % de suelo) y
`ADC_SECO`/`ADC_HUMEDO` están intactas; ver «Hallazgos».

## Archivos

| Archivo | Qué hace |
|---|---|
| `planticas_proyecto/planticas_proyecto.ino` | Red AP, servidor TCP, tarea que envía el bloque cada segundo y `loop()` que lee los sensores |
| `planticas_proyecto/TEMPERATURA.h` | Termocupla MAX6675: promedio de 500 lecturas, menos 8.5 °C |
| `planticas_proyecto/HUMEDAD.h` | DHT11: humedad relativa menos 6 % |
| `planticas_proyecto/SENSORTIERRA.h` | Sensor capacitivo de suelo: porcentaje entre `ADC_SECO` (3000) y `ADC_HUMEDO` (1400), menos 76 |
| `planticas_proyecto/arduino_secrets.example.h` | Plantilla de `arduino_secrets.h` |

## Pines

| Parte | Pin del ESP32 | Nota |
|---|---|---|
| MAX6675 `SO` (datos) | `GPIO5` | **Pin de arranque** (strapping): ver advertencias |
| MAX6675 `CS` | `GPIO17` | |
| MAX6675 `SCK` | `GPIO16` | En módulos con PSRAM (WROVER) GPIO16 y 17 están ocupados; en un ESP32 Dev Module (WROOM) están libres |
| DHT11 (datos) | `GPIO4` | El comentario de `HUMEDAD.h` dice DHT22, pero el código usa `DHT11` |
| Sensor de suelo (analógico) | `GPIO34` | ADC1, solo entrada; sigue leyendo con el Wi-Fi activo |

Todo a 3,3 V y con GND común.

### Advertencias

- **GPIO5 es un pin de arranque.** El ESP32 lo lee al encender para fijar el tiempo de la interfaz SDIO. El `SO` del
  MAX6675 es una salida del módulo, y si al arrancar fija GPIO5 en un nivel distinto del esperado puede haber arranques
  raros o fallos al subir el programa. Si pasa, desconecta `SO` para programar, o mueve el `SO` a un pin libre que no sea
  de arranque, como `GPIO19`, y cambia `SO_PIN`.
- No uses `GPIO0`, `2`, `12` y `15` (arranque) ni `GPIO6` a `11` (memoria flash).
- El DHT11 mide de 20 a 90 % de humedad con ±5 %. Si se desconecta da `nan`.

## Bibliotecas

- Core **esp32** de Espressif (`esp32:esp32:esp32`, placa «ESP32 Dev Module»). Trae `WiFi`.
- **DHT sensor library** y **Adafruit Unified Sensor**, de Adafruit.
- **MAX6675 library**, de Adafruit (`max6675.h`).

## Puesta en marcha

1. Copia `planticas_proyecto/arduino_secrets.example.h` como `planticas_proyecto/arduino_secrets.h` y pon la clave de la
   red (de 8 a 63 caracteres). Git ignora ese archivo.
2. Abre `planticas_proyecto/planticas_proyecto.ino` en el IDE, elige **ESP32 Dev Module** y súbelo.
3. En el monitor serie (115200) aparece `Red WiFi iniciada`, la IP `192.168.4.1` y `Servidor TCP iniciado en puerto: 8080`.

## Formato de salida exacto

Cada segundo, a cada cliente conectado, con `\r\n` al final de cada línea (`println`) y los números con dos decimales y
punto (`Print` de un `float` o `double`):

```
===== PLANTICAS =====
Temperatura exterior: 24.50 C
Humedad relativa: 60.00 %
Humedad del suelo: 31.00 %

```

- La última línea va vacía: cierra el bloque.
- Un sensor que falla sale como `nan` (DHT11 o termocupla desconectados).
- El monitor serie muestra otra cosa (`------`, `Humedad de la tierra: ...`): no es el formato TCP.

El backend lee este bloque con `Esp32BlockParser`, promedia 60 s (`ESP32_AGGREGATE_SECONDS`) y guarda una lectura por
minuto.

## Limitaciones

- **Un solo cliente a la vez.** Mientras haya uno conectado, los demás no reciben nada. **No dejes PuTTY ni otro programa
  conectado** cuando el backend tenga que leer: el backend esperará 30 s sin datos y volverá a intentarlo.
- **No acepta órdenes.** No lee nada de lo que le envía el cliente. No tiene relé ni bomba: el riego que se registre en
  AgroSense para su cultivo solo se guarda.
- **Sin autenticación.** Cualquiera unido a la red puede leer los datos u ocupar el único cupo de cliente.
- **Sin hora.** Su red no tiene internet: el backend pone la hora de cada lectura.

## Hallazgos (pendientes de decidir con el equipo; no se cambió nada)

1. **`SENSORTIERRA.h` resta 76 después de calcular el porcentaje.** Con el sensor entre `ADC_SECO` y `ADC_HUMEDO`, el
   resultado queda entre 0 y 24 %; solo pasa de 24 % si el ADC baja de 1400 (más mojado que la calibración). En la
   práctica la humedad del suelo casi nunca supera 24 %, y con el rango del cultivo de ejemplo (40 a 80 %) se dispararían
   alertas de humedad baja todo el tiempo. **Propuesta:** quitar el `- 76.0` y calibrar `ADC_SECO` (sensor al aire o en
   tierra seca) y `ADC_HUMEDO` (sensor en agua) con este sensor, leyendo `analogRead(34)`.
2. **Compensaciones fijas** de -8.5 °C (termocupla) y -6 % (DHT11). Falta saber de dónde salen (¿una medida contra un
   termómetro e higrómetro de referencia?). **Propuesta:** si salen de una medida, moverlas al backend con
   `SENSOR_CALIBRATION=AIR_TEMPERATURE=-8.5:1,RELATIVE_HUMIDITY=-6:1` y dejar que el ESP32 envíe el valor crudo. Así se
   corrigen sin volver a programar la placa. No hay que hacer las dos cosas: se restaría dos veces.
3. **La clave del Wi-Fi era igual al nombre de la red y estaba en el código.** Ya salió a `arduino_secrets.h`; falta
   elegir una clave propia y fuerte.
4. **El servidor TCP no tiene autenticación.** Mitigaciones simples, sin implementar:
   - una clave Wi-Fi fuerte (12 o más caracteres) y `WiFi.softAP(nombre, clave, canal, ocultar, 1)` para admitir una sola
     estación;
   - un límite de tiempo por cliente en el ESP32: cerrar al que lleve, por ejemplo, 10 minutos o no haya leído nada, para
     que un PuTTY olvidado no deje al backend sin datos;
   - un token en la primera línea: el cliente envía `TOKEN <secreto>\n` y el ESP32 cierra la conexión si no coincide.
     El backend lo enviaría al conectar (hoy no escribe nada en el socket).
5. **GPIO5 es un pin de arranque** (ver advertencias).
6. Otras observaciones menores: `leerTemperaturaPromedio()` hace 500 lecturas en 1 s, pero el MAX6675 solo convierte
   cada unos 220 ms, así que repite el mismo valor; y `loop()` lee los sensores cada 1,5 s, mientras que se envía cada
   1 s, así que algunos bloques repiten valores. Ninguna de las dos falsea el promedio del backend.
