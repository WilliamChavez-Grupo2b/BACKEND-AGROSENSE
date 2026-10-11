# Arquitectura de AgroSense

Estado al 9 de octubre de 2026. Este documento recoge la inspección del repositorio, las decisiones
tomadas, qué partes de la interfaz usan datos reales, parciales o no disponibles, y dónde está desplegado
cada componente.

## 1. Inspección del workspace

| Carpeta     | Contenido                                                                                  |
|-------------|--------------------------------------------------------------------------------------------|
| `backend/`  | Proyecto Spring Boot de William Chávez (`BACKEND-AGROSENSE`), auditado y corregido.         |
| `frontend/` | Aplicación web Spring Boot + Thymeleaf (este documento la describe en detalle).             |
| `database/` | Todo lo de base de datos: `schema.sql`, `seed_demo.sql`, `reset.sql`, `datasource.properties`, `.env.example` y `README.md`. |

### Backend (`backend/agrosense`)

- **Versiones:** Java 17, Spring Boot 4.1.1, Maven.
- **Paquetes:** `config`, `controller`, `domain.enums`, `dto`, `event`, `exception`, `models`, `mqtt`,
  `pattern`, `repository`, `security`, `seed`, `service`, `websocket`.
- **Entidades:** `User`, `Estate`, `Crop`, `Sensor`, `SensorReading`, `Alert`, `Irrigation`, `AiPrediction`,
  con un repositorio Spring Data por cada una.
- **API REST** (JSON, bajo `/api`):

  | Ruta                                             | Uso                                             |
  |--------------------------------------------------|-------------------------------------------------|
  | `GET /api/health`                                | Estado del servicio. Pública                    |
  | `POST /api/auth/login`                           | Devuelve el token. Pública                      |
  | `GET` y `POST /api/fincas`, `/api/cultivos`      | Listar y crear fincas y cultivos                |
  | `GET /api/crops/{id}/sensors`, `POST /api/sensors` | Sensores de un cultivo y alta de sensores     |
  | `POST /api/readings`, `GET /api/sensors/{id}/readings` | Registrar una lectura y leer las últimas  |
  | `GET /api/alerts`, `GET /api/crops/{id}/alerts`, `PATCH /api/alerts/{id}/acknowledge` | Alertas abiertas y marcarlas como atendidas |
  | `GET /api/riego/cultivo/{id}`, `POST /api/riego` | Historial y registro de riegos manuales         |
  | `GET /api/dashboard`, `GET /api/dashboard/{id}`  | Resumen general o de un cultivo                 |
  | `GET` y `POST /api/crops/{id}/predictions`       | Predicciones de riego del servicio de IA        |

  Tiene registro de usuarios (`POST /api/auth/register`) y comprobación de correo
  (`GET /api/auth/check-user`), pero no edición ni eliminación, y los listados no incluyen todo lo que muestra
  el frontend (por ejemplo, el nombre de la finca en una alerta o los cultivos inactivos).
- **Seguridad:** sin estado, con token JWT HS512 en la cabecera `Authorization`. El usuario se vuelve a
  leer de la tabla `users` en cada petición, así que una cuenta desactivada deja de funcionar de
  inmediato. Roles `ADMIN`, `FARMER` y `TECHNICIAN`; crear fincas, cultivos, riegos y predicciones, y atender
  alertas, exige `ADMIN` o `FARMER`. El login
  limita los intentos fallidos por cuenta y por dirección. CORS solo admite los orígenes de
  `CORS_ALLOWED_ORIGINS`. Arranca únicamente si `JWT_SECRET` tiene al menos 64 bytes.
- **Lecturas y alertas:** una lectura puede llegar por REST o por MQTT (cliente Paho, apagado salvo
  `MQTT_ENABLED=true`). `SensorFacade` la valida, la normaliza, la guarda y, si queda fuera del rango del
  cultivo, crea una alerta bloqueando la fila del cultivo para no duplicarla.
- **Tiempo real:** WebSocket STOMP en `/ws`, autenticado con el mismo token; las sesiones se cierran
  cuando el token vence o la cuenta se desactiva.
- **IA:** `AiClientService` llama al servicio de predicción; sin `AI_SERVICE_URL` queda desactivado.
- **Patrones de diseño** en `pattern/`: builder, factory, prototype y singleton (creacionales); adapter,
  decorator, facade y proxy (estructurales).

### Frontend (`frontend/`)

- **Versiones:** Java 21, Spring Boot 4.1.1, Spring MVC, Thymeleaf, Spring Security (sesión), Spring Data
  JPA, Maven.
- **Bibliotecas de interfaz**, servidas localmente desde `/webjars` con versión fija: Bootstrap 5.3.3
  (solo la rejilla), Bootstrap Icons 1.11.3, Chart.js 4.4.1, Leaflet 1.9.4.
- **Sin** React, Angular ni Vue: las páginas las genera el servidor. Los scripts del navegador se escriben
  en **TypeScript estricto** (`frontend/src/main/ts`) y `tsc` los compila a módulos ES en `static/js`. Ese
  resultado se guarda en el repositorio, así que Maven y la imagen de despliegue no necesitan Node; solo
  hace falta para editar los scripts (`npm install` y `npm run build` en `frontend/`).

## 2. Decisiones

1. **El frontend lee la base de datos directamente con JPA.** El backend no expone ningún endpoint, así
   que no hay API que consumir. Para no inventar endpoints, el frontend tiene sus propias entidades
   (copiadas del backend, mismo esquema de tablas), repositorios y servicios. Cuando el backend tenga API
   REST, los servicios del frontend son el único punto que habría que cambiar. *Esto describe el 8 de
   octubre: el backend ya tiene API y la decisión 21 explica qué parte usa el frontend.*
2. **El esquema de tablas es una propuesta.** Los nombres de tablas y columnas (`crops`,
   `sensor_readings`, `created_at`…) salen de las entidades; no existe un script SQL contra el que
   validarlos. En producción se usa `ddl-auto=validate`, así que la base de datos real debe coincidir.
3. **Perfil `demo`.** Arranca con H2 en memoria (modo PostgreSQL) y carga `database/schema.sql` y `database/seed_demo.sql`. La interfaz lo
   indica con la etiqueta «Vista de demostración». Sin ese perfil se conecta a PostgreSQL mediante
   `DATABASE_URL`, `DATABASE_USERNAME` y `DATABASE_PASSWORD`.
4. **Cada consulta se filtra por el usuario autenticado.** Los repositorios buscan siempre por el correo
   del dueño de la finca; pedir un registro ajeno devuelve 404.
5. **Bibliotecas por WebJars en lugar de CDN.** Cumple «versiones fijas» y hace innecesario el atributo
   `integrity`, porque los archivos se sirven desde el mismo origen. Permite una política CSP estricta.
6. **Sin estilos ni scripts en línea.** La CSP solo admite recursos propios y las teselas de OpenStreetMap.
   La fuente (Plus Jakarta Sans) se sirve desde `static/fonts/`; el tema se aplica antes del primer pintado con un script
   externo bloqueante (`js/theme-init.js`), no con uno en línea.
7. **Fotografías locales de dominio público.** El login, el inicio, Parcelas y Riego usan fotos del USDA
   en `static/img/photos/` (JPEG a 960 y 1600 px; no hay conversor a WebP sin añadir Node). Autoría y
   licencias están en `static/img/brand/CREDITS.md`.
8. **El riego se registra, no se ejecuta.** No existe integración con bombas: el MQTT del backend solo
   recibe lecturas, no envía órdenes. «Iniciar riego» guarda el riego manual y la interfaz lo dice de
   forma explícita.
9. **Iconos:** Bootstrap Icons (fuente local). **Tipografía:** Plus Jakarta Sans variable, local, para toda la interfaz, con fuentes del
   sistema como respaldo. **Temas:** `tokens.css` define las variables semánticas de `premium` y de `mono`
   (blanco y negro, no un modo oscuro) y `styles.css` no contiene colores; en `mono` los estados se
   distinguen por icono, texto y tipo de borde. `/design-system` muestra marca, tokens y componentes y solo
   existe en el perfil `demo`.
10. **Registro de usuarios.** `/registro` crea cuentas con rol `farmer`, contraseña BCrypt y validación
    Bean Validation: nombre y apellido de 2 a 18 caracteres y contraseña de 8 a 32, contando los espacios.
    El formulario muestra contadores en vivo, un indicador de fortaleza y si el correo ya está registrado.
    Con el backend disponible la cuenta la crea `POST /api/auth/register` (409 si el correo existe) y el
    inicio de sesión pasa por `POST /api/auth/login`, cuyo límite de intentos (429) el formulario muestra
    como bloqueo; sin backend, ambos usan la base de datos. Como todas las peticiones salen del servidor
    del frontend, el backend debe llevar `LOGIN_MAX_ATTEMPTS_PER_ADDRESS=0` (o `BACKEND_SIGN_IN=false` en el
    frontend). No hay verificación por correo: queda pendiente.
11. **Eliminar cuenta.** Exige volver a escribir la contraseña, que el servidor comprueba contra el hash.
    Borra en una transacción lecturas, alertas, riegos, sensores, cultivos, fincas y el usuario, y cierra
    la sesión. La cuenta de demostración no se puede eliminar, para no dejar la demo sin datos.
12. **Mapa tolerante a fallos.** Si ninguna finca tiene coordenadas, el servidor no genera el mapa y
    muestra una vista esquemática con el aviso «Sin coordenadas registradas». El script valida que
    Leaflet esté cargado y que cada coordenada sea un número dentro de rango; ante cualquier fallo cambia
    a esa misma vista en lugar de lanzar un error.
13. **Preferencias en el navegador.** No existe una tabla de preferencias, así que la única disponible
    (menú lateral contraído) se guarda en `localStorage` y la página lo indica.
14. **Corrección de «Access blocked» en el mapa.** OpenStreetMap devuelve una imagen de bloqueo a las
    peticiones sin cabecera `Referer`, y la aplicación la eliminaba con `Referrer-Policy: same-origin`.
    La política pasa a `strict-origin-when-cross-origin`, que envía solo el origen, y las teselas vuelven
    a cargar (comprobado: la misma tesela pesa 6,9 kB sin `Referer` y 20 kB con él). Se probó CARTO
    Voyager como alternativa, pero hoy entrega las teselas con la marca «API KEY REQUIRED», así que se
    mantiene OpenStreetMap en `tile.openstreetmap.org`, único origen de imágenes externas en la CSP.
15. **Gestión de parcelas, cultivos y sensores.** `CatalogService` concentra altas, ediciones y bajas,
    siempre limitadas al dueño. Al eliminar una parcela o un cultivo se borran antes, en la misma
    transacción, sus lecturas, alertas y riegos, porque esas tablas no tienen cascada. Al eliminar un
    sensor se borran sus lecturas y sus alertas se conservan sin la referencia al sensor. El código de
    sensor es único en todo el sistema y se guarda en mayúsculas.
16. **El esquema vive en `database/schema.sql`.** Es la única fuente de verdad de las tablas: backend y
    frontend usan `ddl-auto=validate` y nunca crean ni alteran tablas. El script es idempotente y se
    aplica desde la propia aplicación con `SQL_INIT_MODE=always` (el perfil `seed` del backend y el
    perfil `demo` del frontend lo hacen), de modo que no hace falta `psql`. Incluye claves foráneas con
    borrado en cascada, restricciones `CHECK` e índices en todas las claves foráneas. Esto sustituye a la
    decisión 2: los nombres de tablas ya no son una propuesta implícita en las entidades.
17. **PostgreSQL local bloqueado por Windows.** En este equipo el servidor PostgreSQL 18 arranca pero
    rechaza cada conexión con «no se pudo lanzar el nuevo proceso para la conexión». El registro de
    Integridad de código de Windows (eventos 3033 y 3077) muestra que la directiva de Control de
    aplicaciones impide a `postgres.exe` lanzar sus procesos hijo. No es un fallo del proyecto y no se
    ha eludido. Hasta que se resuelva, el esquema se verifica con H2 en modo PostgreSQL.
18. **Los datos de ejemplo también viven en `database/`.** `seed_demo.sql` sustituye a los cargadores
    Java que había en backend y frontend, de modo que hay una sola copia de los datos. El script crea el
    usuario de ejemplo con un hash que no corresponde a ninguna contraseña; cada aplicación le asigna la
    suya al arrancar (`SeedAccountInitializer` con `SEED_USER_PASSWORD`, `DemoAccountInitializer` con
    `DEMO_PASSWORD`). Ningún archivo SQL guarda una contraseña ni un hash utilizable.
19. **La configuración de base de datos también vive en `database/`.** `datasource.properties` reúne
    la conexión, `ddl-auto=validate`, la ejecución de los scripts y los perfiles `seed` y `demo`.
    Backend y frontend lo cargan con `spring.config.import` y solo declaran la ruta de la carpeta
    (`DATABASE_DIR` la cambia). Una prueba en cada proyecto comprueba lo que resuelve el archivo con y
    sin perfil. En los proyectos Java quedan únicamente las entidades y los repositorios, que siguen
    duplicados entre backend y frontend.
20. **Las sesiones del frontend se guardan en la base de datos.** Con Spring Session JDBC un inicio de
    sesión sobrevive a un reinicio y funciona con varias instancias, que es como lo ejecuta un servicio
    que apaga las instancias inactivas. Las tablas `spring_session` y `spring_session_attributes` están en
    `database/schema.sql` como las demás y la biblioteca no las crea (`initialize-schema=never`).
21. **El frontend usa la API del backend para lo que la API ofrece.** `BackendClient` llama al backend
    para marcar alertas como atendidas, registrar sensores, registrar riegos manuales y leer la serie de
    lecturas de un sensor. Todo lo demás sigue leyendo la base de datos con JPA, porque la API no tiene
    edición ni eliminación, ni devuelve los datos que muestran los listados.
    - Cada llamada lleva un token HS512 de dos minutos firmado con el secreto que comparten las dos
      aplicaciones (`BACKEND_JWT_SECRET` = `JWT_SECRET` del backend). El frontend ya lee la misma base
      de datos, así que el secreto no le da ningún permiso nuevo.
    - El backend puede estar dormido (el alojamiento gratuito lo apaga y tarda minutos en arrancar). Antes
      de cada operación se consulta `/api/health` con un tiempo de espera corto; si no responde, la
      operación se hace contra la base de datos y esa misma consulta empieza a despertarlo.
    - Las lecturas y el marcado de alertas vuelven a la base de datos si la llamada falla. El alta de
      sensores y de riegos no: el backend puede haberla registrado, y repetirla la duplicaría.
    - Sin `BACKEND_URL` y `BACKEND_JWT_SECRET` la conexión queda desactivada, como en el perfil `demo`.
22. **Rendimiento medido.** `QueryBudgetTests` (frontend) y `listingsStayWithinAFixedNumberOfQueries`
    (backend) fijan cuántas sentencias SQL cuesta cada página. El inicio pasó de 12 a 6 y Sensores de 8 a 3
    al leer la última lectura de todos los sensores en una consulta; el mapa y el consumo de agua van
    incrustados en la página en vez de pedirse aparte. Fuentes, imágenes y WebJars se guardan en la caché
    del navegador (antes Spring Security los marcaba `no-store`) y las respuestas de texto van comprimidas.
    El pool de conexiones mantiene dos abiertas (`DB_POOL_MIN_IDLE`, `DB_POOL_MAX_SIZE`).

## 3. Estructura del frontend

```
frontend/src/main/java/com/agrosense/frontend/
  config/       SecurityConfig, DemoAccountInitializer (perfil demo)
  controller/   PageController, AccountController, CatalogController, IrrigationController,
                AlertController, UiApiController, GlobalModelAttributes
  service/      DashboardService, EstateService, SensorService, AlertService, IrrigationService,
                CatalogService, AccountService
  backend/      BackendClient (llamadas a la API del backend), BackendToken, BackendUnavailableException
  repository/   Un repositorio Spring Data por entidad
  entity/       Entidades JPA y enumeraciones
  dto/          Views (proyecciones de solo lectura) y los formularios (IrrigationForm, EstateForm,
                CropForm, SensorForm, RegistrationForm)
  security/     DatabaseUserDetailsService, AgroUserPrincipal
  exception/    NotFoundException, BusinessRuleException, GlobalExceptionHandler
frontend/src/main/resources/
  templates/            Páginas y templates/fragments/ (layout, brand, sidebar, navbar, stat-card, status-pill,
                        confirm-modal, toast, empty-state, error-state, page-header, pagination)
  static/css/           tokens.css (variables de diseño por tema) y styles.css
  static/js/            Salida de tsc; no se edita a mano
frontend/src/main/ts/   theme-init, theme, app, tables, palette, forms, login, dashboard, settings,
                        design-system; api/ (cliente HTTP y endpoints), core/dom, types/
  static/img/           brand/ (logotipos, favicon, créditos) y photos/
  static/fonts/         PlusJakartaSans.woff2
  messages.properties   Textos en español de las enumeraciones
```

Los controladores solo delegan en servicios; los servicios devuelven DTO, nunca entidades.

## 4. Cobertura: página ↔ servicio ↔ estado

La columna central indica el servicio Java del frontend que atiende cada parte. Donde dice «API del
backend», la operación pasa por el backend cuando está configurado y responde, y por la base de datos en
caso contrario (decisión 21).

| Página / sección                | Servicio o endpoint                                   | Estado          |
|---------------------------------|--------------------------------------------------------|-----------------|
| Login / logout                  | Spring Security + `DatabaseUserDetailsService`         | Real            |
| Crear cuenta (`/registro`)      | `AccountService.register`                              | Real            |
| Inicio · indicadores            | `DashboardService`                                     | Real            |
| Inicio · clima                  | —                                                      | No disponible   |
| Inicio · mapa y detalle         | `GET /api/ui/estates/markers`                          | Real            |
| Inicio · control de riego       | `POST /api/ui/irrigations` → `IrrigationService` → API del backend (`POST /api/riego`) | Parcial (registra el riego; no acciona la bomba) |
| Inicio · sensores               | `SensorService.summarize`                              | Real            |
| Inicio · gráfica de lecturas    | `GET /api/ui/sensors/{id}/readings` → API del backend (`GET /api/sensors/{id}/readings`) | Real |
| Inicio · agua por día           | `GET /api/ui/water-usage`                              | Real            |
| Inicio · alertas recientes      | `AlertService`                                         | Real            |
| Parcelas                        | `EstateService` (lectura), `CatalogService` (alta, edición, baja de parcelas y cultivos) | Real |
| Sensores                        | `SensorService` (lectura), `CatalogService` (baja; alta por la API del backend, `POST /api/sensors`) | Parcial (sin edición; no llegan lecturas nuevas mientras la ingesta del backend esté apagada) |
| Riego                           | `IrrigationService` (historial); alta por la API del backend (`POST /api/riego`) | Parcial (registra el riego con sus litros; no acciona la bomba) |
| Alertas                         | `AlertService` (listar, paginar); marcar como atendida por la API del backend (`PATCH /api/alerts/{id}/acknowledge`) | Real |
| Reportes                        | `IrrigationService.waterReport`                        | Parcial (exporta a CSV la tabla visible; sin PDF) |
| Configuración · perfil          | `AccountService.findProfile`                           | Parcial (solo lectura) |
| Configuración · preferencias    | `localStorage` del navegador                           | Parcial (no se guardan en la cuenta) |
| Configuración · cerrar sesión   | `POST /logout` con CSRF                                | Real            |
| Configuración · eliminar cuenta | `AccountService.deleteAccount`                         | Real            |

«Real» significa que los datos salen de la base de datos, leídos con JPA o a través de la API del
backend. Con el perfil `demo` esa base de datos está en memoria, contiene datos de ejemplo y el backend
no interviene.

## 5. Seguridad

Del frontend (la del backend está en la sección 1):

- Inicio de sesión por formulario con sesión de servidor, guardada en la base de datos; contraseñas con
  BCrypt.
- Las llamadas al backend llevan un token de dos minutos firmado con el secreto compartido, a nombre del
  usuario que tiene la sesión; el token nunca llega al navegador.
- Todas las rutas requieren autenticación excepto `/login`, los recursos estáticos y `/error`.
- CSRF activo en formularios y en las peticiones `fetch` (cabecera leída de las etiquetas `<meta>`).
- Cookie de sesión `HttpOnly`, `SameSite=Lax` y `Secure` (desactivado solo en el perfil `demo`).
- Cabecera `Content-Security-Policy` restrictiva y `Referrer-Policy: strict-origin-when-cross-origin`.
- Thymeleaf escapa todo el contenido (`th:text`); el JavaScript usa `textContent`, nunca `innerHTML`.
- Validación con Bean Validation en `IrrigationForm`, tanto en el formulario como en el JSON.
- El mensaje de error del login es el mismo para usuario inexistente y contraseña incorrecta.

## 6. Verificación realizada

- `mvnw test` en `frontend/`: 57 pruebas (JUnit 5, Mockito, MockMvc); 55 correctas y 2 omitidas, que son
  las de PostgreSQL. Incluyen las del cliente del backend contra un servidor HTTP local.
- `mvnw test` en `backend/agrosense`: 42 pruebas; 39 correctas y 3 omitidas, también las de PostgreSQL.
- `PostgresCompatibilityTests` de los dos proyectos contra Neon (PostgreSQL 18.6): todas correctas, en
  un esquema propio que se borra al terminar.
- En producción, con la cuenta de ejemplo: inicio de sesión en el frontend y en el backend, lecturas de
  sensores, una alerta atendida y un riego manual registrados a través del backend. Los dos cambios se
  revirtieron después en la base de datos.
- Recorrido en Edge sin interfaz con el perfil `demo`: registro → login → inicio → cada opción del menú
  → configuración → cerrar sesión → ruta protegida redirige a login → login → eliminar cuenta (contraseña
  incorrecta y correcta) → la cuenta eliminada ya no puede entrar. Sin errores de consola ni bloqueos de
  CSP.
- Sin desborde horizontal a 360, 768, 1024 y 1440 px en inicio, parcelas, alertas y configuración.

## 7. Despliegue

| Componente    | Dónde                                   | Dirección                                      |
|---------------|-----------------------------------------|------------------------------------------------|
| Base de datos | Neon (PostgreSQL 18), recurso `agrosense-db` | La entrega Neon en las variables del proyecto |
| Frontend      | Vercel, proyecto `agrosense-frontend`, contenedor | `https://agrosense-frontend-iota.vercel.app` |
| Backend       | Render, servicio `BACKEND-AGROSENSE`, Docker, plan gratuito | `https://backend-agrosense-oyhq.onrender.com` |

- **Base de datos.** Una sola para los dos componentes, con las tablas de `database/schema.sql` y los
  datos de `seed_demo.sql`. No hay base separada para pruebas: `PostgresCompatibilityTests` usa la misma,
  dentro de un esquema temporal.
- **Frontend.** Se despliega desde la raíz del workspace con `vercel deploy --prod`, que construye
  `Dockerfile.vercel`. La conexión sale de las variables `NEON_*` que pone la integración de Neon, y
  aplica `schema.sql` al arrancar (`SQL_INIT_MODE=always`). Para llamar al backend necesita
  `BACKEND_URL` y `BACKEND_JWT_SECRET`. `.vercelignore` deja fuera `/backend` y `/docs`.
- **Backend.** Render construye `agrosense/Dockerfile` del repositorio `BACKEND-AGROSENSE` en cada
  cambio de su rama `main`. Variables: `DATABASE_URL` en formato JDBC, `DATABASE_USERNAME`,
  `DATABASE_PASSWORD`, `JWT_SECRET` y `SQL_INIT_MODE=never`. El plan gratuito apaga el servicio tras unos
  15 minutos sin uso y tarda cerca de 3 minutos en volver a arrancar; mientras tanto el frontend trabaja
  contra la base de datos.
- **Secreto compartido.** `BACKEND_JWT_SECRET` en Vercel y `JWT_SECRET` en Render deben tener el mismo
  valor; si se cambia uno hay que cambiar el otro y volver a desplegar.
- **Acceso.** Vercel Authentication está activa en el proyecto del frontend: quien no tenga sesión en
  el equipo de Vercel ve la pantalla de acceso de Vercel en lugar de AgroSense.
- **Otro despliegue del backend.** El proyecto `agrosense-backend` de Vercel
  (`https://agrosense-backend.vercel.app`) corre el mismo backend con `backend/Dockerfile.vercel` contra
  la misma base. Funciona, pero el frontend no lo usa.

## 8. Pendiente

- Ampliar la API del backend (registro, edición, eliminación, listados completos) para que el frontend
  pueda dejar de leer la base de datos directamente (ver decisión 21).
- Ingesta de lecturas: el backend las recibe por REST y por MQTT, pero MQTT está apagado y no hay broker
  ni dispositivos que envíen datos.
- Servicio de IA para las predicciones: el backend lo llama, pero `AI_SERVICE_URL` está vacía.
- Abrir el sitio al público (quitar Vercel Authentication) y, antes, cambiar la contraseña de la cuenta
  de ejemplo, que está escrita en `frontend/README.md`.
- Decidir un único despliegue del backend y mantenerlo despierto, o pasarlo a un plan que no se apague.
- Restricciones `CHECK` para `users.role`, `sensor_readings.quality` y
  `ai_predictions.prediction_type`, que hoy aceptan cualquier texto.
- Las fechas que inserta `seed_demo.sql` quedan en UTC y las que inserta la aplicación, en la hora del
  servidor: coinciden en la nube, pero no al ejecutar en local contra Neon.
- Edición de sensores (hoy solo alta y baja).
- Edición del perfil, cambio de contraseña, verificación de correo y límite de intentos de acceso.
- Integración con dispositivos (bombas), servicio de clima y exportación de reportes.
- El PostgreSQL local sigue bloqueado (decisión 17). El esquema, los datos de ejemplo, las entidades y
  las sesiones sí se probaron contra PostgreSQL 18 en Neon con `PostgresCompatibilityTests`; el resto de
  las pruebas y la demo usan H2 (ver `database/README.md`).
