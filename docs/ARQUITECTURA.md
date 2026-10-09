# Arquitectura de AgroSense

Estado al 8 de octubre de 2026. Este documento recoge la inspección del repositorio, las decisiones
tomadas y qué partes de la interfaz usan datos reales, parciales o no disponibles.

## 1. Inspección del workspace

| Carpeta     | Contenido                                                                                  |
|-------------|--------------------------------------------------------------------------------------------|
| `backend/`  | Proyecto Spring Boot de William Chávez (`BACKEND-AGROSENSE`), auditado y corregido.         |
| `frontend/` | Aplicación web Spring Boot + Thymeleaf (este documento la describe en detalle).             |
| `database/` | Vacía. El repositorio `BASE-DE-DATOS-AGROSENSE` no tiene ningún archivo todavía.            |

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
3. **Perfil `demo`.** Arranca con H2 en memoria y datos de ejemplo (`DemoDataLoader`). La interfaz lo
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

## 3. Estructura del frontend

```
frontend/src/main/java/com/agrosense/frontend/
  config/       SecurityConfig, DemoDataLoader (perfil demo)
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
| Inicio · indicadores            | `DashboardService`                                     | Real            |
| Inicio · clima                  | —                                                      | No disponible   |
| Inicio · mapa y detalle         | `GET /api/ui/estates/markers`                          | Real            |
| Inicio · control de riego       | `POST /api/ui/irrigations` → `IrrigationService`       | Parcial (registra el riego; no acciona la bomba) |
| Inicio · sensores               | `SensorService.summarize`                              | Real            |
| Inicio · gráfica de lecturas    | `GET /api/ui/sensors/{id}/readings`                    | Real            |
| Inicio · agua por día           | `GET /api/ui/water-usage`                              | Real            |
| Inicio · alertas recientes      | `AlertService`                                         | Real            |
| Parcelas                        | `EstateService`                                        | Real (solo lectura) |
| Sensores                        | `SensorService`                                        | Real (solo lectura) |
| Riego                           | `IrrigationService`                                    | Parcial (los riegos manuales no registran litros) |
| Dispositivos                    | —                                                      | No disponible   |
| Alertas                         | `AlertService` (listar, paginar, marcar como atendida) | Real            |
| Reportes                        | `IrrigationService.waterReport`                        | Parcial (sin exportación) |
| Configuración                   | Datos de la sesión                                     | Parcial (solo lectura) |

«Real» significa que los datos salen de la base de datos mediante JPA. Con el perfil `demo` esa base de
datos contiene datos de ejemplo.

## 5. Seguridad

- Inicio de sesión por formulario con sesión de servidor; contraseñas con BCrypt.
- Todas las rutas requieren autenticación excepto `/login`, los recursos estáticos y `/error`.
- CSRF activo en formularios y en las peticiones `fetch` (cabecera leída de las etiquetas `<meta>`).
- Cookie de sesión `HttpOnly`, `SameSite=Lax` y `Secure` (desactivado solo en el perfil `demo`).
- Cabecera `Content-Security-Policy` restrictiva y `Referrer-Policy: same-origin`.
- Thymeleaf escapa todo el contenido (`th:text`); el JavaScript usa `textContent`, nunca `innerHTML`.
- Validación con Bean Validation en `IrrigationForm`, tanto en el formulario como en el JSON.
- El mensaje de error del login es el mismo para usuario inexistente y contraseña incorrecta.

## 6. Verificación realizada

- `mvnw clean verify` en `frontend/`: 30 pruebas (JUnit 5, Mockito, MockMvc), todas correctas.
- `mvnw package` en `backend/agrosense`: 5 pruebas, todas correctas.
- Recorrido en Edge sin interfaz con el perfil `demo`: login → inicio → cada opción del menú → riego con
  confirmación → logout → ruta protegida redirige a login. Sin errores de consola ni bloqueos de CSP.

## 7. Pendiente

- Definir el esquema real en `database/` y alinear las entidades de backend y frontend.
- API REST en el backend; después, decidir si el frontend sigue con JPA o pasa a consumirla.
- Altas y edición de fincas, cultivos y sensores; registro de usuarios; edición del perfil.
- Integración con dispositivos (bombas), servicio de clima y exportación de reportes.
- No se ha probado contra PostgreSQL: las pruebas y la demo usan H2.
