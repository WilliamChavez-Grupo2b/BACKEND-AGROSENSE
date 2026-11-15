# Humedad del ESP32 en la página Sensores

La página **Sensores** termina con la sección **«Sensor ESP32 conectado»**. El botón **Humedad del ESP32** de la barra
lleva directo a ella. Muestra solo lo que el ESP32 real mide del agua y todo lo que el sistema sabe de la planta que mide.

## Qué muestra

| Bloque | Contenido |
|---|---|
| La planta | Cultivo, variedad, etapa, fecha de siembra, finca, lugar y área |
| Rangos del cultivo | Humedad del suelo, humedad del aire (opcional), temperatura y pH |
| Sensores de humedad | Los sensores de humedad del suelo y humedad relativa de ese cultivo, su última lectura y su conexión |
| Niveles de humedad | Las 50 lecturas más recientes de esos sensores, de la más nueva a la más antigua, con su nivel |

Cada lectura se califica contra el rango que le corresponde:

| Nivel | Cuándo |
|---|---|
| **Baja** | Por debajo del mínimo |
| **Normal** | Entre el mínimo y el máximo, ambos incluidos |
| **Alta** | Por encima del máximo |
| **Sin rango definido** | El cultivo no tiene ese rango (la humedad del aire es opcional) |

La humedad del suelo usa el rango de humedad del cultivo. La humedad relativa usa el rango de **humedad del aire**, que se
define en **Cultivos > Editar**. El rango del suelo nunca se aplica al aire. Si falta el rango del aire, la sección lo dice y
enlaza al formulario del cultivo.

## De dónde salen los datos

1. El ESP32 (`firmware/esp32-tcp/`) sirve bloques de texto por TCP en la red `PLANTICAS` (`192.168.4.1:8080`).
2. El backend los lee, promedia cada 60 s y guarda las lecturas como el dispositivo `ESP32_DEVICE_ID`
   (por defecto `ESP32-PLANTICAS`), que pertenece al cultivo Café de la demo.
3. El frontend busca el cultivo de ese dispositivo (tabla `devices`) **solo si es del usuario que inició sesión**; si no,
   muestra «Sin sensor conectado». Nadie ve la planta de otro adivinando un código.
4. La página consulta `/api/ui/esp32/humidity` cada 30 s (`src/main/ts/esp32-humidity.ts`) y repinta la lista cuando hay
   una lectura más nueva. Las lecturas del ESP32 llegan por TCP y no pasan por el manejador de eventos, por eso no viajan por
   el canal en vivo.

El frontend y el backend deben usar el mismo `ESP32_DEVICE_ID`.

## Modo demo

Con el perfil `demo` (o `seed`) la sección funciona sin la placa con los datos de `database/seed_demo.sql`: el dispositivo
`ESP32-PLANTICAS` en el Café, sus sensores AS-001 (suelo) y AS-007 (aire) y rangos de aire de ejemplo (Café 60–85 %,
Plátano 60–90 %, Papa 70–90 %).

## Bases que ya existían

Ejecuta `database/migrations/004_esp32_air_humidity.sql` antes de desplegar este frontend o este backend. Agrega el rango de
humedad del aire a `crops` y renombra el dispositivo `RPI-DEMO-01` a `ESP32-PLANTICAS`. Después, cambia
`ESP32_DEVICE_ID` donde todavía diga `RPI-DEMO-01` (o quítalo para usar el valor por defecto).

## Pruebas

| Qué | Dónde |
|---|---|
| Solo humedad, la planta completa, niveles y el rango del aire | `WebFlowTests.theSensorsPageShowsTheConnectedEsp32...`, `withoutAnAirRange...` |
| El aire usa su propio rango y nunca el del suelo | `WebFlowTests.theAirsHumidityUsesItsOwnRangeAndNeverTheSoils` |
| La consulta periódica y que otro usuario no vea nada | `WebFlowTests.theEsp32HumidityFeedRefreshesOnlyTheOwnersHumidityNewestFirst` |
| Baja, normal y alta en los bordes | `HumidityLevelTests` |
| El rango del aire es opcional, pero completo y en orden | `CropFormAirHumidityTests` |
| Formato del feed y cuándo repintar | `src/test/ts/api-patterns.test.mjs` (`esp32Humidity`) |
| La página no hace más consultas de las previstas | `QueryBudgetTests` (`/sensores`: 5) |
