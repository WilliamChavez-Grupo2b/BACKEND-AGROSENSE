
#ifndef TEMPERATURA_H
#define TEMPERATURA_H

#include <Arduino.h>
#include <max6675.h>

// Pines del MAX6675
#define SO_PIN  5
#define CS_PIN  17
#define SCK_PIN 16

// Inicializar termocupla
MAX6675 termocupla(SCK_PIN, CS_PIN, SO_PIN);

// Variables de temperatura
double sumaTemperaturas = 0;
double promTem = 0;

// Leer 500 veces y calcular el promedio
double leerTemperaturaPromedio() {
  sumaTemperaturas = 0;
  for (int i = 0; i < 500; i++) {
    // Valor crudo: la compensación (-8.5 °C, por verificar) la aplica el backend con SENSOR_CALIBRATION.
    double temperatura = termocupla.readCelsius();
    sumaTemperaturas += temperatura;
    delay(2);
  }
  promTem = sumaTemperaturas / 500.0;
  return promTem;
}

#endif
