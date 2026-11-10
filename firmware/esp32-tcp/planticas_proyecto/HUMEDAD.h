
#ifndef HUMEDAD_H
#define HUMEDAD_H

#include <Arduino.h>
#include <DHT.h>

// Configuración del DHT11 (humedad relativa)
#define DHT_PIN 4
#define DHT_TYPE DHT11

// Inicializar sensor
DHT dht(DHT_PIN, DHT_TYPE);

// Variables de lectura
float humedad = 0;
float temperaturaDHT = 0;

// Inicializar sensor
void iniciarHumedad() {
  dht.begin();
}

// Leer humedad relativa
float leerHumedad() {
  // Valor crudo: la compensación (-6 %, por verificar) la aplica el backend con SENSOR_CALIBRATION.
  humedad = dht.readHumidity();
  return humedad;
}



#endif
