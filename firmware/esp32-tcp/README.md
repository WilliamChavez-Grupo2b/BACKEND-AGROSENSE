# ESP32 PLANTICAS (dispositivo real, servidor TCP)

**Este es el programa que corre en el dispositivo de campo.** El ESP32 crea su propia red Wi-Fi (modo AP), abre un
servidor TCP en `192.168.4.1:8080` y escribe un bloque de texto con sus lecturas cada segundo. El backend se conecta a él
con `ESP32_TCP_ENABLED=true` (`backend/agrosense`, paquete `pattern/structural/adapter/esp32/`).

`firmware/agrosense/` es otra cosa: un firmware alternativo que envía por HTTP al manejador de eventos y maneja un relé.
**No está cargado en la placa ni se usa hoy.**

El sketch parte del que escribió el equipo. Desde el principio, el nombre y la clave de la red salen de
`arduino_secrets.h`, que no va al repositorio. Los cambios posteriores, acordados con el equipo, están en
«Hallazgos».

## Archivos

| Archivo | Qué hace |
|---|---|
| `planticas_proyecto/planticas_proyecto.ino` | Red AP, servidor TCP, tarea que envía el bloque cada segundo y `loop()` que lee los sensores |
| `planticas_proyecto/TEMPERATURA.h` | Termocupla MAX6675, valor crudo (la compensación la aplica el backend) |
| `planticas_proyecto/HUMEDAD.h` | DHT11: humedad relativa, valor crudo (la compensación la aplica el backend) |
| `planticas_proyecto/SENSORTIERRA.h` | Sensor capacitivo de suelo: porcentaje entre `ADC_SECO` y `ADC_HUMEDO` (**pendiente de calibrar**), limitado a 0–100 %. Con `CALIBRAR_SUELO`, imprime el valor crudo del ADC |
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

1. Copia `planticas_proyecto/arduino_secrets.example.h` como `planticas_proyecto/arduino_secrets.h` y cambia el marcador
   `CAMBIA_ESTA_CLAVE` por la clave de la red: **mínimo 12 caracteres** (máximo 63) y **distinta del nombre de la red**.
   Git ignora ese archivo. El sketch no compila con el marcador, con una clave más corta o igual al nombre.
2. Abre `planticas_proyecto/planticas_proyecto.ino` en el IDE, elige **ESP32 Dev Module** y súbelo.
3. En el monitor serie (115200) aparece `Red WiFi iniciada`, la IP `192.168.4.1` y `Servidor TCP iniciado en puerto: 8080`.

## Calibrar el sensor de suelo

`ADC_SECO` (3000) y `ADC_HUMEDO` (1400) son valores de partida, **no medidos con este sensor** (`PENDIENTE: calibrar` en
`SENSORTIERRA.h`). Hasta medirlos, el porcentaje de humedad del suelo no es fiable.

1. En `planticas_proyecto.ino`, descomenta `#define CALIBRAR_SUELO` (primera línea de código) y sube el sketch.
2. Abre el monitor serie a 115200. Cada segundo aparece, entre las lecturas normales, una línea como
   `CALIBRAR_SUELO adc=2875`. Es el valor crudo del ADC, de 0 a 4095.
3. **`ADC_SECO`:** deja el sensor **al aire**, seco y limpio. Espera a que el número se estabilice (unos 30 s) y anota el
   valor típico.
4. **`ADC_HUMEDO`:** mete el sensor **en un vaso con agua** hasta la línea que marca su profundidad, sin mojar la
   electrónica de arriba. Espera a que se estabilice y anota el valor. En un sensor capacitivo suele ser menor que el seco.
5. Repite las dos medidas dos o tres veces y usa el promedio. Si ves `0` o `4095`, revisa el cable o la alimentación.
6. Escribe los dos números en `ADC_SECO` y `ADC_HUMEDO`, borra el comentario `PENDIENTE: calibrar`, vuelve a comentar
   `#define CALIBRAR_SUELO` y sube el sketch otra vez.

Con el sensor al aire el porcentaje debe dar 0 % y en el agua 100 %; en tierra, algo intermedio. El TCP sigue
funcionando durante la calibración; las líneas `CALIBRAR_SUELO` solo salen por el monitor serie.

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
- **Una sola estación en la red.** `WiFi.softAP(nombre, clave, 1, 0, 1)`: canal 1, red visible, como máximo un equipo
  unido. Mientras el PC del backend esté en `PLANTICAS`, ningún otro equipo puede unirse; para usar PuTTY desde otro
  equipo, saca antes al PC de la red.
- **Sin autenticación en el TCP.** Quien esté unido a la red puede leer los datos u ocupar el único cupo de cliente.
- **Sin hora.** Su red no tiene internet: el backend pone la hora de cada lectura.

## Hallazgos (pendientes de decidir con el equipo; no se cambió nada)

1. **Resuelto:** `SENSORTIERRA.h` restaba 76 después de calcular el porcentaje, lo que dejaba la humedad del suelo casi
   siempre en 24 % o menos. Se quitó; falta calibrar `ADC_SECO` y `ADC_HUMEDO` (sección «Calibrar el sensor de suelo»).
2. **Resuelto:** las compensaciones de -8.5 °C (termocupla) y -6 % (DHT11) salieron del sketch, que ahora envía el valor
   crudo, y las aplica el backend con `SENSOR_CALIBRATION=AIR_TEMPERATURE=-8.5:1,RELATIVE_HUMIDITY=-6:1` (`.env.example`,
   `compose.yaml`). Se aplican una sola vez. **Están por verificar con un termómetro y un higrómetro de referencia**: si la
   medida da otra cosa, se cambia la variable sin volver a programar la placa.
3. **Resuelto:** la clave del Wi-Fi era igual al nombre de la red y estaba en el código. Está en `arduino_secrets.h`, fuera
   del repositorio; el ejemplo trae un marcador y el sketch exige al compilar 12 caracteres o más y una clave distinta
   del nombre. La clave la pone el equipo.
4. **En parte resuelto:** el servidor TCP no tiene autenticación. Ahora la red admite una sola estación
   (`WiFi.softAP(nombre, clave, 1, 0, 1)`) y la clave es propia y de 12 o más caracteres. Por decisión del equipo **no** se
   cierra al cliente por tiempo. El token en la primera línea queda como mejora futura (`docs/ARQUITECTURA.md`, decisión
   40 y sección «Seguridad»).
5. **GPIO5 es un pin de arranque** (ver advertencias).
6. Otras observaciones menores: `leerTemperaturaPromedio()` hace 500 lecturas en 1 s, pero el MAX6675 solo convierte
   cada unos 220 ms, así que repite el mismo valor; y `loop()` lee los sensores cada 1,5 s, mientras que se envía cada
   1 s, así que algunos bloques repiten valores. Ninguna de las dos falsea el promedio del backend.
