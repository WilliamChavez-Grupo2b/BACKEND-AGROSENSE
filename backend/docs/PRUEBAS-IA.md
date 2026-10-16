# Pruebas del asistente de IA

Quince preguntas de referencia para comprobar el asistente (`POST /api/ai/chat`) después de cada cambio:
cinco sobre la finca, cinco de agricultura general, tres sobre la aplicación y dos fuera de tema.

Nota de calidad:
- **Buena**: responde lo que se pregunta, con las cifras reales cuando aplica.
- **Aceptable**: útil pero incompleta.
- **Mala**: no responde a la pregunta.

## Ronda 1: modo local, sin llave (16 de octubre de 2026)

**Entorno.** Rama `feature/asistente-ia-abierto`, con backend y frontend arrancados juntos sobre una base H2
local recién creada con `database/seed_demo.sql`. No había ninguna llave definida (`AI_CHAT_API_KEY`,
`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`). Las preguntas se enviaron al backend con el token del usuario
`demo@agrosense.co`, sin historial y con 7 segundos entre una y otra, para respetar `AI_REQUESTS_PER_MINUTE=10`.

**Datos de la finca de demostración en ese momento:**
- 3 cultivos (Café, Papa, Plátano), 5 sensores y 3 alertas sin atender.
- Café: humedad 46.77 %, temperatura 19.26 °C y pH 6.15. Plátano: humedad 64.74 %. Papa: sin lecturas.

**Resultado:** las 15 respondieron con 200 y `source: "local"`. Ninguna se rechazó y ninguna inventó cifras;
todas las que se citan son las de la base. Como se esperaba, el modo local solo responde bien a las
preguntas sobre la finca. Busca palabras clave, así que cualquier otra pregunta acaba en la respuesta de
la finca que comparta una palabra con ella.

| # | Pregunta | `source` | Calidad | Qué respondió |
|---|---|---|---|---|
| 1 | ¿Necesito regar hoy? | local | Buena | Por cultivo: Café 46.77 % y Plátano 64.74 % dentro del rango 40–80 %, no hace falta regar; Papa sin lecturas de humedad. |
| 2 | ¿Cómo está el pH de mis cultivos? | local | Buena | Café 6.15, en su rango 5.5–7; Papa y Plátano sin lecturas de pH. |
| 3 | ¿Hay alertas activas y qué debo hacer? | local | Aceptable | Lista las 3 alertas (humedad baja en Café, riego recomendado en Plátano, posible plaga en Papa). El consejo se queda en «revísalas en Alertas», sin pasos para cada una. |
| 4 | ¿Cuál es el cultivo en peor estado ahora mismo? | local | Aceptable | Resumen con las cifras de cada cultivo, pero no dice cuál está peor. |
| 5 | ¿Por qué se activó la alerta de ayer? | local | Mala | Lista las alertas abiertas; no identifica la de ayer ni explica la causa. |
| 6 | ¿Cómo controlo la roya del café? | local | Mala | Solo muestra las lecturas del Café; nada sobre la roya. |
| 7 | ¿Cada cuánto debo fertilizar el maíz? | local | Mala | Dice con honestidad que sin el modelo solo puede responder sobre la finca, y enumera lo que sí puede hacer. |
| 8 | ¿Qué pH ideal necesita el tomate? | local | Mala | Responde el pH de los cultivos de la finca, no el ideal del tomate. |
| 9 | ¿Cómo sé si estoy regando de más? | local | Aceptable | Compara la humedad de cada cultivo con su máximo (ninguno lo supera), pero no explica las señales del exceso de riego. |
| 10 | ¿Qué hago si hay una helada esta noche? | local | Mala | Da la temperatura actual del Café (19.26 °C, en rango); nada sobre cómo proteger los cultivos. |
| 11 | ¿Cómo registro un sensor nuevo? | local | Mala | Cuenta los sensores activos; no explica cómo registrar uno. |
| 12 | ¿Cómo funciona el riego inteligente de AgroSense? | local | Mala | Da la humedad de cada cultivo; no explica cómo funciona. |
| 13 | ¿Qué significan los colores de las alertas? | local | Mala | Lista las alertas abiertas; no explica los colores. |
| 14 | Ayúdame a resolver un ejercicio de programación. | local | Aceptable | Redirige a lo que sí puede hacer (riego, alertas, temperatura, pH), aunque empieza con un «Hola.» fuera de lugar. |
| 15 | ¿Quién va a ganar las próximas elecciones? | local | Mala | Devuelve el resumen de la finca en vez de redirigir con amabilidad. |

**Totales:** 2 buenas, 4 aceptables, 9 malas.

Las preguntas 6 a 13 y la 15 solo se pueden responder bien con un modelo, y el prompt nuevo
(`ai/prompts/system-es.txt`) ya contiene lo necesario para las preguntas sobre la aplicación. Una posible
mejora, aparte de esta rama, es que el modo local reconozca las preguntas que no son sobre la finca. En lugar
de devolver datos que no vienen al caso, diría que esa respuesta necesita el modelo.

## Ronda 2: con modelo (pendiente)

Repetir con una llave definida en la terminal como variable de entorno (por ejemplo
`$env:ANTHROPIC_API_KEY`), nunca en archivos. Anotar el proveedor y el modelo usados. Lo esperado:
- todas con `source: "model"`;
- las de la finca, citando las cifras de la tabla de arriba;
- la 5, basada en la fecha de las alertas;
- de la 11 a la 13, con lo descrito en el prompt;
- la 14 y la 15, redirigiendo con amabilidad.

| # | `source` | Calidad | Observaciones |
|---|---|---|---|
| 1–15 | | | |
