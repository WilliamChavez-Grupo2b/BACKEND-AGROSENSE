---
name: prueba-regresion
description: Ciclo obligatorio rojo, arreglo, verde para corregir un bug en AgroSense, con plantillas de prueba de regresión en Java (JUnit 5), TypeScript (node --test), Python (unittest/pytest) y C++ (firmware/test). Úsalo cada vez que corrijas un hallazgo.
---

# Prueba de regresión: rojo → arreglo → verde

1. **Rojo.** Escribe la prueba más pequeña que reproduce el hallazgo, con el ID en el nombre o en un comentario. Córrela
   y confirma que **falla por el motivo del bug** (aserción), no por compilación ni por otra cosa. Guarda el extracto.
2. **Arreglo mínimo.** Cambia solo lo necesario.
3. **Verde.** La prueba pasa. Corre la suite completa de la parte (skill `correr-pruebas`): sin fallos nuevos y con la
   misma cifra o mayor.
4. **Prohibido**: borrar o desactivar pruebas, relajar aserciones, ampliar tolerancias, `sleep` para «arreglar»
   carreras (usa `Clock` inyectado, latches o barreras).

## Plantillas

**Java (JUnit 5, backend/frontend)** - en `src/test/java/...` junto a la clase.
```java
@Test
void rejectsLinkLocalHost_BE012() {   // BE-012: ESP32_TCP_HOST aceptaba 169.254.x.x
    assertThatThrownBy(() -> Esp32TcpProperties.validateHost("169.254.1.1"))
        .isInstanceOf(IllegalArgumentException.class);
}
```
Concurrencia: `ExecutorService` + `CountDownLatch` para arrancar N hilos a la vez y aserción sobre el estado final.
Tiempo: `Clock.fixed(...)` o un `MutableClock` de prueba; nunca `Thread.sleep` para esperar una ventana.

**TypeScript (frontend/src/test/ts/*.test.mjs)** - importa el JS compilado de `static/js`, así que corre `npm run build`
antes.
```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextDelay } from '../../main/resources/static/js/events/backoff.js';
test('FE-004: reconnect delay is capped', () => { assert.equal(nextDelay(10), 30000); });
```

**Python (IA/tests, event-service/tests)** - si no necesita FastAPI, `unittest` puro para que corra aquí; si necesita
`TestClient`, pytest y queda `NO VERIFICADO (CI)`.
```python
import unittest, hmac
from agrosense_ia import security
class InternalKeyTests(unittest.TestCase):
    def test_empty_configured_key_rejects_IA003(self):
        self.assertFalse(security.key_matches(provided="", configured=""))
```

**C++ (firmware/test/test_patterns.cpp, g++ en PC)** - usa la misma macro de comprobación del archivo.
```cpp
// FW-002: soil percent must saturate at 0..100
CHECK(soilPercent(3500, 2900, 1300) == 0);
CHECK(soilPercent(900, 2900, 1300) == 100);
```
Si la lógica vive en el `.ino` del sketch real y no es comprobable en PC, extráela a una función pura en un `.h` del mismo
sketch y pruébala; dilo en el commit.

**PowerShell (tools/esp32/tests/verificar.tests.ps1)** - sigue el estilo del archivo existente.

## Evidencia que entregas
Comando exacto, extracto ROJO, extracto VERDE, cifras de la suite antes y después.
