# Pruebas del ESP32 (dispositivo real, `firmware/esp32-tcp/`)

Estado al 10 de noviembre de 2026. Qué está probado automáticamente, qué se revisó sin placa y qué falta probar con el
ESP32. La decisión de arquitectura está en `docs/ARQUITECTURA.md` (decisión 40) y los pasos de la demo en `docs/DEMO.md`.

Para arrancar el sistema completo con el ESP32 real en un PC Windows: `tools\esp32\run-demo.ps1`, sección «Correr con el
ESP32 real» de `docs/DEMO.md`.

## 1. Pruebas automáticas del backend

`cd backend/agrosense && ./mvnw test`. Las del ESP32 no necesitan placa: usan un servidor TCP falso.

| Prueba | Qué comprueba |
|---|---|
| `Esp32BlockParserTests` (10) | El bloque real; `nan` e `inf`; valores fuera de rango; un bloque cortado por el siguiente encabezado o por la conexión; basura dentro y fuera de un bloque; una línea de más de 256 caracteres; números con coma y con signo; mayúsculas y tildes; que `ESP32_TCP_HOST` solo admita una IPv4 privada |
| `Esp32ReadingAggregatorTests` (3) | 60 bloques en 60 s dan una sola lectura con los promedios correctos; la ventana siguiente; el vaciado al cortarse la conexión |
| `Esp32TcpIntegrationTests` (1) | Un `ServerSocket` falso envía el bloque cada 100 ms: se guardan lecturas promediadas, se genera la alerta de humedad baja, **la compensación de `SENSOR_CALIBRATION` se aplica una sola vez** (23.40 °C crudos se guardan como 14.90, no como 6.40; 58 % como 52) y el cliente se reconecta tras cerrar el servidor |
| `Esp32OffTests` (3) | El backend arranca y responde con el receptor encendido y el ESP32 ausente; rechaza una dirección pública o un nombre; una orden de riego para el cultivo del ESP32 solo se registra |

## 1b. Pruebas del script de la demo (`tools/esp32/`)

`tools\esp32\tests\verificar.tests.ps1` corre `run-demo.ps1 -Verificar` con archivos de prueba, sin arrancar nada ni
usar internet. Pasa en Windows PowerShell 5.1 y en PowerShell 7 (8 casos): bloquea la base de producción con el mismo host,
con `-pooler` en uno solo de los dos (en cualquiera de los dos sentidos), con otras mayúsculas y pegada como dirección con
esquema y puerto; deja pasar otra branch; y rechaza `ESP32_TCP_ALLOW_LOOPBACK` en el archivo de variables.

```powershell
powershell -ExecutionPolicy Bypass -File tools\esp32\tests\verificar.tests.ps1
pwsh -ExecutionPolicy Bypass -File tools\esp32\tests\verificar.tests.ps1
```

## 2. Revisión del sketch sin placa

En este equipo no hay `arduino-cli`. El sketch se revisó compilándolo en el PC con g++ y cabeceras falsas que imitan la
API del core esp32 (`WiFi`, `WiFiServer`, `WiFiClient`, `DHT`, `MAX6675`, FreeRTOS), en C++11 (core 2.x) y C++17 (core 3.x),
con y sin `CALIBRAR_SUELO`: sin errores ni avisos. Con el marcador `CAMBIA_ESTA_CLAVE` sin cambiar, la compilación se
detiene con `Pon tu clave en SECRET_AP_PASS`. Esto revisa tipos, includes y firmas, **no** sustituye compilar con el
core real: hazlo en el IDE (sección 3). La CI (`firmware/test/compile.sh`) lo compila con `esp32:esp32:esp32`, pero aún no
ha corrido.

Firma de `softAP` en el core esp32: `softAP(ssid, passphrase, channel, ssid_hidden, max_connection, ...)`, igual en 2.x y
3.x (3.x añade al final `auth_mode` y `cipher` con valor por defecto). `WiFi.softAP(nombre, clave, 1, 0, 1)` es válido en
los dos.

## 3. Compilar en el Arduino IDE

1. *Archivo > Preferencias > Gestor de URLs adicionales de tarjetas*:
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`. En el gestor de tarjetas instala **esp32** de
   Espressif (2.x o 3.x).
2. En el gestor de bibliotecas instala **DHT sensor library** y **Adafruit Unified Sensor** (Adafruit) y **MAX6675
   library** (Adafruit).
3. Copia `firmware/esp32-tcp/planticas_proyecto/arduino_secrets.example.h` como `arduino_secrets.h` en la misma carpeta y
   pon la clave (12 caracteres o más, distinta de `PLANTICAS`).
4. Abre `firmware/esp32-tcp/planticas_proyecto/planticas_proyecto.ino`. Placa: **ESP32 Dev Module**. Puerto: el COM de la
   placa.
5. **Verificar** (✓). Errores posibles y su causa:
   - `Pon tu clave en SECRET_AP_PASS`, `minimo 12 caracteres` o `debe ser distinta del nombre de la red`: revisa
     `arduino_secrets.h`.
   - `arduino_secrets.h: No such file or directory`: falta el paso 3.
   - `max6675.h: No such file or directory` o `DHT.h`: falta una biblioteca del paso 2.
6. **Subir** (→). Si no entra en modo de programación, mantén **BOOT** pulsado al empezar la subida. Si falla siempre,
   desconecta el `SO` del MAX6675 de GPIO5 (pin de arranque) mientras subes.

## 4. Qué debe verse en el monitor serie (115200 baudios)

Al arrancar:

```
Red WiFi iniciada
Nombre: PLANTICAS
IP del ESP32: 192.168.4.1
Servidor TCP iniciado en puerto: 8080
```

Después, unas dos veces por segundo (el `loop` tiene `delay(500)` y ya no espera 1 s a la termocupla). Los números
son de ejemplo:

```
------------------------------------------
Temperatura exterior: 32.75
Humedad relativa: 66.00
Humedad de la tierra: 41.00
```

- Los valores son **crudos**: la temperatura sale unos 8.5 °C más alta y la humedad del aire unos 6 % más alta que antes,
  porque las compensaciones ahora las aplica el backend (`SENSOR_CALIBRATION`).
- La humedad de la tierra ya no queda limitada a 24 %. Con la calibración del 10/11/2026 (`ADC_SECO=2900`,
  `ADC_HUMEDO=1300`), al aire debe dar 0 % y en agua 100 %.
- `nan` en temperatura: termocupla o MAX6675 desconectados. `nan` en humedad: DHT11 desconectado.
- `ERROR al iniciar WiFi`: la red no arrancó (por ejemplo, una clave de menos de 8 caracteres, que no debería pasar porque
  el sketch exige 12).
- Cuando el backend (o PuTTY) se conecta: `Cliente TCP conectado`.

Con `#define CALIBRAR_SUELO` descomentado aparece además, cada segundo:

```
CALIBRAR_SUELO adc=2875
```

## 5. Pruebas con la placa (pendientes)

| # | Prueba | Cómo | Resultado esperado |
|---|---|---|---|
| 1 | Arranque y red | Encender la placa | El monitor muestra la red y el puerto; la red `PLANTICAS` aparece en el PC |
| 2 | Una sola estación | Unir el PC y después un teléfono | El teléfono no puede unirse mientras el PC esté dentro |
| 3 | Bloque por TCP | PuTTY (Raw, 192.168.4.1, 8080) unos segundos y cerrarlo | Un bloque `===== PLANTICAS =====` por segundo, con línea vacía al final |
| 4 | Backend | `ESP32_TCP_ENABLED=true`, sin PuTTY | `ESP32: connected to 192.168.4.1:8080`; al minuto, una lectura por sensor en Café y «ESP32 conectado» |
| 5 | Compensación una sola vez | Comparar el monitor serie con lo guardado | Temperatura guardada = la del monitor − 8.5; humedad = la del monitor − 6 |
| 6 | Calibración del suelo | `CALIBRAR_SUELO`, al aire y en un vaso con agua | **Hecha el 10/11/2026:** `ADC_SECO=2900`, `ADC_HUMEDO=1300`, ya en `SENSORTIERRA.h`. Falta comprobar con el sketch normal que da 0 % al aire y 100 % en el agua |
| 7 | Sensor desconectado | Quitar el DHT11 | `nan` en el monitor; en AgroSense deja de llegar la humedad del aire; las otras dos siguen |
| 8 | Reconexión | Reiniciar el ESP32 (`EN`) con el backend en marcha | El backend reconecta solo en menos de 30 s |
| 9 | Cliente ocupado | Dejar PuTTY conectado y arrancar el backend | El backend registra `nothing from … in 30 s` y vuelve cuando se cierra PuTTY |
| 10 | Referencias | Termómetro e higrómetro de referencia junto a los sensores | Confirmar o corregir -8.5 °C y -6 % en `SENSOR_CALIBRATION` |
