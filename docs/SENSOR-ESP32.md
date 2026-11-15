# Humedad del ESP32 en la página Sensores

La página **Sensores** termina con la sección **«Humedad de tu cultivo en vivo»**. El botón **Humedad del ESP32** de la
barra lleva directo a ella. Se ve como la lista de sensores y muestra solo resultados en palabras del agricultor: ningún
código de dispositivo, de sensor ni de configuración, y ninguna tabla técnica.

## Qué muestra

| Bloque | Contenido |
|---|---|
| Tarjetas de resultado | Una por medida (humedad del suelo y humedad relativa), como las tarjetas de sensores: el valor en grande, si está **Baja**, **Normal** o **Alta**, qué significa para la planta («La tierra está seca: conviene regar»), cuándo llegó y la humedad ideal |
| Las últimas 24 horas | Una gráfica con una línea por medida, promediada cada 15 minutos, y la humedad ideal de cada una como líneas punteadas |
| Tu cultivo | Cultivo, variedad, etapa, siembra, finca, lugar, área y temperatura ideal |
| Últimas lecturas | Las 20 lecturas más recientes, de la más nueva a la más antigua: hora, medida, valor y nivel |

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

## Solo lo que mide el ESP32

El ESP32 mide tres valores: **humedad del suelo** (la sonda), **temperatura del aire** y **humedad relativa** (el DHT11).
Toda la aplicación web trabaja solo con esos tres:

- **Sensores > Nuevo** ofrece solo esos tres tipos, y el servidor rechaza cualquier otro.
- El tablero, Sensores, Cultivos, Parcelas y Reportes muestran solo sensores de esos tipos.
- El formulario del cultivo ya no pide pH. Al editar un cultivo, el pH que tenía guardado se conserva.

Los otros tipos (pH, conductividad, luz, lluvia, temperatura del suelo) siguen existiendo en la base y en el backend,
así los datos viejos no se rompen y las pruebas del backend siguen igual. Solo dejan de verse. En la demo, AS-003 (pH) y
AS-005 (conductividad) quedan guardados pero ocultos, y AS-006 pasó de luz a humedad del suelo (sigue inactivo).

## Parcelas y Cultivos muestran lo que mide cada sensor

En **Parcelas**, en el detalle de cada parcela y en **Cultivos**, cada cultivo muestra solo lo que miden sus sensores
**activados**: humedad del suelo, temperatura del aire y humedad relativa, con su último valor. Una medida sin sensor
activado no aparece, y un cultivo sin ninguno dice «Sin sensores activados». Un sensor desactivado no muestra datos (ni
en la página ni en la actualización cada 30 segundos) y dice «Desactivado: no muestra datos». Ninguna de estas páginas
muestra el código del sensor.

## Humedad del día en Riego

La página **Riego** tiene la tarjeta **«Humedad del día»**, con las últimas 24 horas del cultivo que mide el ESP32. Por cada
medida (humedad del suelo y humedad relativa) muestra:

- el valor de **ahora** y si está **Baja**, **Normal** o **Alta**;
- la **mínima**, la **máxima** y el **promedio** del día;
- **cuánto tiempo estuvo fuera de lo ideal**, por ejemplo «La tierra estuvo seca 18 h de 24 h medidas» o «La tierra estuvo
  seca todo el tiempo medido (4 h 30 min)»;
- qué significa para la planta y la humedad ideal.

Debajo va la misma gráfica de 24 horas de Sensores, que se vuelve a dibujar cada 5 minutos. Los cálculos salen de los
promedios cada 15 minutos; un valor justo en el mínimo o en el máximo cuenta como dentro de lo ideal. Otro usuario no ve
la tarjeta.

## Aviso «Sin datos del ESP32»

El ESP32 guarda una lectura por minuto. Si pasan más de **5 minutos** sin ninguna lectura de humedad, la sección muestra un
aviso amarillo arriba, por ejemplo: «Sin datos del ESP32 desde las 14:32 (hace 12 minutos). Revisa que esté encendido y el
cable USB o la red PLANTICAS». El aviso aparece y desaparece solo con la consulta de cada 30 s, sin recargar la página.

- El servidor arma la frase con su propia hora, así la zona horaria del navegador no la cambia.
- Si el cultivo nunca recibió lecturas, el aviso dice que el ESP32 todavía no ha enviado nada.
- El límite se cambia con `ESP32_SILENT_AFTER_MINUTES`. Es distinto del estado «Sin datos recientes» de los sensores,
  que llega a la hora.

## Alertas de humedad del aire

Cada lectura de humedad relativa se compara con el rango de humedad del aire del cultivo:

| Alerta | Cuándo | Qué hacer |
|---|---|---|
| **Humedad del aire baja** (`LOW_AIR_HUMIDITY`) | Por debajo del mínimo | El aire seco hace que la planta pierda agua más rápido: revisa el suelo y considera regar |
| **Humedad del aire alta** (`HIGH_AIR_HUMIDITY`) | Por encima del máximo | Suben los hongos: ventila si es posible y vigila hojas y frutos |

Los bordes del rango cuentan como normales. Si el cultivo no tiene rango de aire, no hay alerta, y el rango del suelo nunca
se usa para el aire. Como las demás alertas, no se repite mientras haya una igual sin atender en ese cultivo. Necesita la
migración `database/migrations/005_air_humidity_alerts.sql`.

## De dónde salen los datos

1. El ESP32 (`firmware/esp32-tcp/`) sirve bloques de texto por TCP en la red `PLANTICAS` (`192.168.4.1:8080`).
2. El backend los lee, promedia cada 60 s y guarda las lecturas como el dispositivo `ESP32_DEVICE_ID`
   (por defecto `ESP32-PLANTICAS`), que pertenece al cultivo Café de la demo.
3. El frontend busca el cultivo de ese dispositivo (tabla `devices`) **solo si es del usuario que inició sesión**; si no,
   muestra «Aún no hay un ESP32 en tus cultivos». Nadie ve la planta de otro adivinando un código.
4. La gráfica pide `/api/ui/esp32/humidity/day` al abrir la página y otra vez cada vez que llega una lectura nueva. Si el
   ESP32 estuvo apagado un rato, la línea queda cortada en ese tramo en vez de unir los puntos.
5. La página consulta `/api/ui/esp32/humidity` cada 30 s (`src/main/ts/esp32-humidity.ts`) y repinta la lista cuando hay
   una lectura más nueva. Las lecturas del ESP32 llegan por TCP y no pasan por el manejador de eventos, por eso no viajan por
   el canal en vivo.

El frontend y el backend deben usar el mismo `ESP32_DEVICE_ID`.

## Sin la red PLANTICAS: por USB

`run-demo.ps1 -Usb` lee la misma placa por el cable USB con `tools/esp32/esp32_usb.py` (ver [DEMO.md](DEMO.md)). Las lecturas
son las reales y llegan a esta sección igual que por Wi-Fi.

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
| La página no hace más consultas de las previstas | `QueryBudgetTests` (`/parcelas`: 4, `/sensores`: 5, `/riego`: 6) |
| Solo se ofrecen y se muestran los tres valores del ESP32 | `WebFlowTests.theApplicationOnlyOffersAndShowsWhatTheEsp32Measures` |
| El aviso «Sin datos del ESP32»: cuándo sale y qué dice | `Esp32SilencePolicyTests`, `WebFlowTests.anEsp32ThatStoppedSendingIsSaidOnThePageAndInTheFeed` |
| Parcelas y Cultivos muestran solo los datos de los sensores activados, sin códigos | `WebFlowTests.estatesAndCropsShowWhatEachCropsActivatedSensorsMeasureAndADeactivatedSensorShowsNoData` |
| La humedad del día en Riego: cifras, tiempo fuera de lo ideal y solo para el dueño | `HumidityDaySummaryTests`, `WebFlowTests.theIrrigationPageShowsTheDaysHumidityOfTheEsp32sCropOnlyToItsOwner` |
| Alerta de aire baja o alta, solo con su propio rango | `DesignPatternTests.theAirsHumidityIsJudgedOnlyAgainstItsOwnOptionalRange`, `DeviceIntegrationTests` |
| Editar un cultivo sin campo de pH no borra su pH | `WebFlowTests.editingACropWithoutAPhFieldKeepsTheStoredPh` |
| La gráfica de 24 h: series, rangos y solo del dueño | `WebFlowTests.theEsp32DayChartHasTheOwnersSoilAndAirHumidityWithTheirRanges` |
| Promedios cada 15 minutos y huecos sin unir | `HumidityBucketTests`, `api-patterns.test.mjs` (`alignSeries`) |
