# Arquitectura de AgroSense

Estado al 8 de octubre de 2026. Este documento recoge la inspección del repositorio, las decisiones
tomadas y qué partes de la interfaz usan datos reales, parciales o no disponibles.

## 1. Inspección del workspace

| Carpeta     | Contenido                                                                                  |
|-------------|--------------------------------------------------------------------------------------------|
| `backend/`  | Proyecto Spring Boot de William Chávez (`BACKEND-AGROSENSE`), auditado y corregido.         |
| `frontend/` | Aplicación web Spring Boot + Thymeleaf (este documento la describe en detalle).             |
| `database/` | Todo lo de base de datos: `schema.sql`, `seed_demo.sql`, `reset.sql`, `datasource.properties`, `.env.example` y `README.md`. |

### Backend (`backend/agrosense`)

- **Versiones:** Java 17, Spring Boot 4.1.1, Maven.
- **Paquetes:** `config`, `domain.enums`, `models`, `repository`, `service`.
- **Entidades:** `User`, `Estate`, `Crop`, `Sensor`, `SensorReading`, `Alert`, `Irrigation`, `AiPrediction`.
- **Repositorios:** solo `UserRepository`.
- **Servicios:** solo `AiClientService` (cliente HTTP del servicio de predicción, desactivado sin URL).
- **Endpoints REST:** ninguno. No hay controladores.
- **Seguridad:** Spring Security sin estado, HTTP Basic, BCrypt y usuarios leídos de la tabla `users`.
- **Otros:** `MqttConfig.java` y `WebSocketConfig.java` son archivos vacíos; Kafka y WebSocket están en
  el `pom.xml` pero nada los usa.

### Frontend (`frontend/`)

- **Versiones:** Java 21, Spring Boot 4.1.1, Spring MVC, Thymeleaf, Spring Security (sesión), Spring Data
  JPA, Maven.
- **Bibliotecas de interfaz**, servidas localmente desde `/webjars` con versión fija: Bootstrap 5.3.3
  (solo la rejilla), Bootstrap Icons 1.11.3, Chart.js 4.4.1, Leaflet 1.9.4.
- **Sin** React, Angular, Vue, TypeScript ni Node.js. El JavaScript es vainilla en módulos ES.

## 2. Decisiones

1. **El frontend lee la base de datos directamente con JPA.** El backend no expone ningún endpoint, así
   que no hay API que consumir. Para no inventar endpoints, el frontend tiene sus propias entidades
   (copiadas del backend, mismo esquema de tablas), repositorios y servicios. Cuando el backend tenga API
   REST, los servicios del frontend son el único punto que habría que cambiar.
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
6. **Sin estilos ni scripts en línea.** La CSP solo admite recursos propios, las teselas de OpenStreetMap
   y Google Fonts.
7. **Sin fotografías.** No hay imágenes WebP del cultivo disponibles; el login usa una ilustración SVG
   propia (`static/img/field.svg`) con degradado oscuro para el texto.
8. **El riego se registra, no se ejecuta.** No existe integración con bombas (MQTT está vacío). «Iniciar
   riego» guarda el riego manual en la base de datos y la interfaz lo dice de forma explícita.
9. **Iconos:** Bootstrap Icons (fuente local). **Tipografías:** Plus Jakarta Sans e Inter desde Google
   Fonts con `preconnect`, con fuentes del sistema como respaldo.
10. **Registro de usuarios.** `/registro` crea cuentas con rol `farmer`, contraseña BCrypt (8 a 72
    caracteres; 72 es el límite real de BCrypt) y validación Bean Validation. Si el correo ya existe, el
    mensaje es genérico para no confirmar qué correos están registrados. No hay verificación por correo
    ni límite de intentos: quedan pendientes.
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
    registro de usuarios, edición ni eliminación, ni devuelve los datos que muestran los listados.
    - Cada llamada lleva un token HS512 de dos minutos firmado con el secreto que comparten las dos
      aplicaciones (`BACKEND_JWT_SECRET` = `JWT_SECRET` del backend). El frontend ya lee la misma base
      de datos, así que el secreto no le da ningún permiso nuevo.
    - El backend puede estar dormido (el alojamiento gratuito lo apaga y tarda minutos en arrancar). Antes
      de cada operación se consulta `/api/health` con un tiempo de espera corto; si no responde, la
      operación se hace contra la base de datos y esa misma consulta empieza a despertarlo.
    - Las lecturas y el marcado de alertas vuelven a la base de datos si la llamada falla. El alta de
      sensores y de riegos no: el backend puede haberla registrado, y repetirla la duplicaría.
    - Sin `BACKEND_URL` y `BACKEND_JWT_SECRET` la conexión queda desactivada, como en el perfil `demo`.

## 3. Estructura del frontend

```
frontend/src/main/java/com/agrosense/frontend/
  config/       SecurityConfig, DemoAccountInitializer (perfil demo)
  controller/   PageController, IrrigationController, AlertController, UiApiController,
                GlobalModelAttributes
  service/      DashboardService, EstateService, SensorService, AlertService, IrrigationService
  repository/   Un repositorio Spring Data por entidad
  entity/       Entidades JPA y enumeraciones
  dto/          Views (proyecciones de solo lectura), IrrigationForm
  security/     DatabaseUserDetailsService, AgroUserPrincipal
  exception/    NotFoundException, BusinessRuleException, GlobalExceptionHandler
frontend/src/main/resources/
  templates/            Páginas y templates/fragments/ (layout, sidebar, navbar, stat-card, status-pill,
                        confirm-modal, toast, empty-state, error-state, page-header, pagination)
  static/css/           tokens.css (variables de diseño) y styles.css
  static/js/            app.js, dashboard.js, login.js
  messages.properties   Textos en español de las enumeraciones
```

Los controladores solo delegan en servicios; los servicios devuelven DTO, nunca entidades.

## 4. Cobertura: página ↔ servicio ↔ estado

No hay endpoints del backend; la columna central indica el servicio Java del frontend que atiende cada
parte.

| Página / sección                | Servicio o endpoint                                   | Estado          |
|---------------------------------|--------------------------------------------------------|-----------------|
| Login / logout                  | Spring Security + `DatabaseUserDetailsService`         | Real            |
| Crear cuenta (`/registro`)      | `AccountService.register`                              | Real            |
| Inicio · indicadores            | `DashboardService`                                     | Real            |
| Inicio · clima                  | —                                                      | No disponible   |
| Inicio · mapa y detalle         | `GET /api/ui/estates/markers`                          | Real            |
| Inicio · control de riego       | `POST /api/ui/irrigations` → `IrrigationService`       | Parcial (registra el riego; no acciona la bomba) |
| Inicio · sensores               | `SensorService.summarize`                              | Real            |
| Inicio · gráfica de lecturas    | `GET /api/ui/sensors/{id}/readings`                    | Real            |
| Inicio · agua por día           | `GET /api/ui/water-usage`                              | Real            |
| Inicio · alertas recientes      | `AlertService`                                         | Real            |
| Parcelas                        | `EstateService` (lectura), `CatalogService` (alta, edición, baja de parcelas y cultivos) | Real |
| Sensores                        | `SensorService` (lectura), `CatalogService` (alta y baja) | Parcial (sin edición; las lecturas dependen de un servicio de ingesta que no existe) |
| Riego                           | `IrrigationService`                                    | Parcial (registra el riego con sus litros; no acciona la bomba) |
| Dispositivos                    | —                                                      | No disponible   |
| Alertas                         | `AlertService` (listar, paginar, marcar como atendida) | Real            |
| Reportes                        | `IrrigationService.waterReport`                        | Parcial (sin exportación) |
| Configuración · perfil          | `AccountService.findProfile`                           | Parcial (solo lectura) |
| Configuración · preferencias    | `localStorage` del navegador                           | Parcial (no se guardan en la cuenta) |
| Configuración · cerrar sesión   | `POST /logout` con CSRF                                | Real            |
| Configuración · eliminar cuenta | `AccountService.deleteAccount`                         | Real            |

«Real» significa que los datos salen de la base de datos mediante JPA. Con el perfil `demo` esa base de
datos contiene datos de ejemplo.

## 5. Seguridad

- Inicio de sesión por formulario con sesión de servidor; contraseñas con BCrypt.
- Todas las rutas requieren autenticación excepto `/login`, los recursos estáticos y `/error`.
- CSRF activo en formularios y en las peticiones `fetch` (cabecera leída de las etiquetas `<meta>`).
- Cookie de sesión `HttpOnly`, `SameSite=Lax` y `Secure` (desactivado solo en el perfil `demo`).
- Cabecera `Content-Security-Policy` restrictiva y `Referrer-Policy: strict-origin-when-cross-origin`.
- Thymeleaf escapa todo el contenido (`th:text`); el JavaScript usa `textContent`, nunca `innerHTML`.
- Validación con Bean Validation en `IrrigationForm`, tanto en el formulario como en el JSON.
- El mensaje de error del login es el mismo para usuario inexistente y contraseña incorrecta.

## 6. Verificación realizada

- `mvnw clean verify` en `frontend/`: 43 pruebas (JUnit 5, Mockito, MockMvc), todas correctas.
- `mvnw clean verify` en `backend/agrosense`: 10 pruebas, todas correctas.
- Recorrido en Edge sin interfaz con el perfil `demo`: registro → login → inicio → cada opción del menú
  → configuración → cerrar sesión → ruta protegida redirige a login → login → eliminar cuenta (contraseña
  incorrecta y correcta) → la cuenta eliminada ya no puede entrar. Sin errores de consola ni bloqueos de
  CSP.
- Sin desborde horizontal a 360, 768, 1024 y 1440 px en inicio, parcelas, alertas y configuración.

## 7. Pendiente

- Ampliar la API del backend (registro, edición, eliminación, listados completos) para que el frontend
  pueda dejar de leer la base de datos directamente (ver decisión 21).
- Ingesta de lecturas: los sensores se pueden registrar, pero nada recibe datos de los dispositivos.
- Edición de sensores (hoy solo alta y baja).
- Edición del perfil, cambio de contraseña, verificación de correo y límite de intentos de acceso.
- Integración con dispositivos (bombas), servicio de clima y exportación de reportes.
- El PostgreSQL local sigue bloqueado (decisión 17). El esquema, los datos de ejemplo, las entidades y
  las sesiones sí se probaron contra PostgreSQL 18 en Neon con `PostgresCompatibilityTests`; el resto de
  las pruebas y la demo usan H2 (ver `database/README.md`).
