// Descomenta para calibrar el sensor de suelo: el monitor serie muestra el valor crudo del ADC cada segundo.
// #define CALIBRAR_SUELO

#include <WiFi.h>
#include "arduino_secrets.h"  // nombre y clave de la red: fuera del repositorio
#include "TEMPERATURA.h"
#include "HUMEDAD.h"
#include "SENSORTIERRA.h"

double temperaturaExterna = 0;
float humedadRelativa = 0;
float humedadDelSuelo = 0;

SemaphoreHandle_t mutexSensores;

const char* nombreRed = SECRET_AP_SSID;
const char* claveRed = SECRET_AP_PASS;

const int puertoTCP = 8080;

WiFiServer servidor(puertoTCP);
WiFiClient cliente;

void gestionarVariables(void *parameter);

void setup() {

  Serial.begin(115200);
  delay(500);

  mutexSensores = xSemaphoreCreateMutex();

  if (mutexSensores == NULL) {
    Serial.println("ERROR: No se pudo crear el mutex");

    while (true) {
      delay(1000);
    }
  }

  iniciarSensorTierra();
  iniciarHumedad();

  WiFi.mode(WIFI_AP);

  bool redIniciada = WiFi.softAP(nombreRed, claveRed);

  if (redIniciada) {

    Serial.println("Red WiFi iniciada");

    Serial.print("Nombre: ");
    Serial.println(nombreRed);

    Serial.print("IP del ESP32: ");
    Serial.println(WiFi.softAPIP());

    servidor.begin();

    Serial.print("Servidor TCP iniciado en puerto: ");
    Serial.println(puertoTCP);

  } else {
    Serial.println("ERROR al iniciar WiFi");
  }

  xTaskCreatePinnedToCore(
    gestionarVariables,
    "Variables",
    4096,
    NULL,
    1,
    NULL,
    1
  );
}

void loop() {

  sensorTemperatura();
  sensorHumRelativa();
  sensorTierra();

#ifdef CALIBRAR_SUELO
  imprimirCrudoSuelo();
#endif

  double temp;
  float humedad;
  float humedadSuelo;

  xSemaphoreTake(mutexSensores, portMAX_DELAY);

  temp = temperaturaExterna;
  humedad = humedadRelativa;
  humedadSuelo = humedadDelSuelo;

  xSemaphoreGive(mutexSensores);

  Serial.println("------------------------------------------");

  Serial.print("Temperatura exterior: ");
  Serial.println(temp);

  Serial.print("Humedad relativa: ");
  Serial.println(humedad);

  Serial.print("Humedad de la tierra: ");
  Serial.println(humedadSuelo);

  delay(500);
}

void sensorTemperatura() {

  double lectura = leerTemperaturaPromedio();

  xSemaphoreTake(mutexSensores, portMAX_DELAY);
  temperaturaExterna = lectura;
  xSemaphoreGive(mutexSensores);
}

void sensorHumRelativa() {

  float lectura = leerHumedad();

  xSemaphoreTake(mutexSensores, portMAX_DELAY);
  humedadRelativa = lectura;
  xSemaphoreGive(mutexSensores);
}

void sensorTierra() {

  float lectura = leerSensorTierra();

  xSemaphoreTake(mutexSensores, portMAX_DELAY);
  humedadDelSuelo = lectura;
  xSemaphoreGive(mutexSensores);
}

void gestionarVariables(void *parameter) {

  double temp;
  float humedad;
  float humedadSuelo;

  unsigned long ultimaEnvio = 0;

  while (true) {

    if (!cliente || !cliente.connected()) {

      cliente.stop();

      WiFiClient nuevoCliente = servidor.available();

      if (nuevoCliente) {
        cliente = nuevoCliente;
        cliente.setNoDelay(true);

        Serial.println("Cliente TCP conectado");
      }
    }

    xSemaphoreTake(mutexSensores, portMAX_DELAY);

    temp = temperaturaExterna;
    humedad = humedadRelativa;
    humedadSuelo = humedadDelSuelo;

    xSemaphoreGive(mutexSensores);

    if (cliente && cliente.connected()) {

      if (millis() - ultimaEnvio >= 1000) {

        ultimaEnvio = millis();

        cliente.println("===== PLANTICAS =====");

        cliente.print("Temperatura exterior: ");
        cliente.print(temp);
        cliente.println(" C");

        cliente.print("Humedad relativa: ");
        cliente.print(humedad);
        cliente.println(" %");

        cliente.print("Humedad del suelo: ");
        cliente.print(humedadSuelo);
        cliente.println(" %");

        cliente.println();
      }
    }

    vTaskDelay(pdMS_TO_TICKS(100));
  }
}