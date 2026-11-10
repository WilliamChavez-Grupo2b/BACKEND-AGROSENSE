---
name: auditar-patron
description: Checklist para auditar un patrón de diseño de AgroSense - existe, corre en la ruta de producción (demostrado con usos, no con pruebas), tiene prueba, está bien documentado en docs/PATRONES.md y qué pasa si se quita. Úsalo para verificar patrones o preparar la sustentación.
---

# Auditar un patrón

Para cada clase que `docs/CONTEXTO-IA.md` (sección 7) o `docs/PATRONES.md` atribuye al patrón:

1. **Existe.** `Glob` de la ruta exacta. Ruta equivocada en el documento = hallazgo de documentación.
2. **Estructura correcta.** ¿Tiene los roles del patrón? (p. ej. Bridge: abstracción + implementador independientes;
   Decorator: misma interfaz y envuelve a otro; Proxy: misma interfaz que el real y controla el acceso; Factory Method:
   subclases deciden qué crear; Abstract Factory: familias de productos; Prototype: copia **profunda**; Singleton: una
   instancia y cómo se garantiza (en Spring, el bean singleton vale si se dice)).
3. **Corre en producción.** Cadena de llamadas desde un punto de entrada real hasta la clase:
   `Grep` del nombre de la clase y de sus métodos fuera de `src/test`/`tests`. Puntos de entrada válidos: controlador
   REST, `@Scheduled`, `@EventListener`, `CommandLineRunner`, bean inyectado en uno de ellos, ruta FastAPI, `loop()` de
   `firmware/esp32-tcp/planticas_proyecto`, módulo TS importado por una plantilla. Anota si depende de una bandera
   apagada por defecto. **Las pruebas no cuentan como uso.**
4. **Tiene prueba.** Nombre de la prueba y resultado de la última corrida.
5. **Documentado.** `docs/PATRONES.md` dice lo mismo que el código (nombres, roles, flujo).
6. **¿Qué pasa si lo quito?** Qué funcionalidad visible se rompe. Si «nada», es **decorativa** → hallazgo.
7. **Firmware.** Si solo vive en `firmware/agrosense/` (no cargado), márcalo y busca su equivalente que sí corre.

## Fila de salida
`patrón | clase | ruta | producción (sí / con bandera X / solo pruebas / solo firmware no cargado) | cadena de uso
(entrada → … → clase) | prueba | doc ok | decorativa | si lo quito…`
