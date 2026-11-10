
#ifndef HUMEDAD_H
#define HUMEDAD_H

#include <Arduino.h>
#include <DHT.h>

// Configuración del DHT22
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
  humedad = dht.readHumidity();
  humedad = humedad - 6;
  return humedad;
}



#endif
