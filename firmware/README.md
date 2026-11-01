# Firmware AgroSense (Arduino UNO R4 WiFi)

Es el dispositivo final de campo. Lee el suelo y el aire cada 30 s, envía cada lectura al manejador de eventos
(`POST /events`, el mismo contrato que usa el simulador de `raspberry/`) y acciona el relé de la bomba. El simulador
en Python se conserva para probar sin placa.

## Qué necesitas

| Parte | Conexión |
|---|---|
| Sensor capacitivo de humedad de suelo | Salida a `A0` |
| DHT22 (por defecto) | Datos a `D4` |
| SHT31 (alternativa) | Conector Qwiic; descomenta `AGRO_USE_SHT31` en `agrosense.ino` |
| Módulo de relé de la bomba | Control a `D7`. Se asume activo en bajo; si no, cambia `RELAY_ACTIVE_LOW` en `src/ArduinoPorts.h` |
| Pulsador de riego manual | Entre `D2` y `GND` (usa la resistencia interna) |

Bibliotecas, desde el gestor del IDE de Arduino: **DHT sensor library** y **Adafruit SHT31 Library**. `WiFiS3` viene
con el paquete de placas *Arduino UNO R4 Boards*.

## Puesta en marcha

1. Registra el dispositivo con `POST /api/devices`; la llave se muestra una sola vez.
2. Copia `agrosense/arduino_secrets.example.h` como `agrosense/arduino_secrets.h` y complétalo. Git ignora ese archivo.
3. Calibra el suelo: anota `analogRead(A0)` con el sensor en tierra seca y en agua, y pon esos valores en
   `soilDryRaw` y `soilWetRaw` (`src/DeviceConfig.h`). Los que trae son de partida, no medidos.
4. Abre `agrosense/agrosense.ino` en el IDE y súbelo.

Con `AGRO_SIMULATED_HARDWARE` descomentado funciona sin nada conectado: el Wi-Fi y el backend son reales, los sensores
y la bomba no.

## Órdenes de riego

Con `IRRIGATION_ACTUATOR=relay` en el backend, cada orden espera allí y llega en la respuesta al siguiente `POST
/events` del dispositivo, como máximo 30 s después, dentro de `{"command": {"type": "irrigate", "source": "AI" |
"MANUAL", "minutes": 7, "confidence": 0.85}}`. Una orden sin entregar caduca a los 5 minutos.

- Una orden de la IA se rechaza en el dispositivo sin lectura válida de suelo o con el suelo al 85 % o más. Una orden
  de una persona se obedece siempre.
- Ninguna orden pasa de 120 minutos.
- El relé arranca apagado tras cualquier reinicio.
- El pulsador arranca 10 minutos de riego y, si la bomba ya funciona, la detiene.

**Límite conocido:** un envío bloquea el programa hasta 45 s si el servidor no responde, y en ese tiempo no se revisa
el temporizador, así que la bomba puede pasarse hasta ese tiempo de su duración. Para una bomba que no tolere eso hace
falta un temporizador por hardware.

El búfer guarda 16 eventos en RAM, unos 8 minutos: si se llena se pierde el más antiguo, y un corte de luz los pierde
todos.

## Pruebas

```bash
sh firmware/test/run-tests.sh
```

Compila la lógica con g++ y la ejecuta en el PC. Después comprueba que `agrosense.ino` y `src/ArduinoPorts.h` compilan
en sus tres variantes contra `test/stubs/`, que solo declaran las firmas de las bibliotecas. Eso detecta errores de
escritura y de tipos, pero no demuestra que funcione en la placa.
