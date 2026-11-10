
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

// El MAX6675 convierte cada ~220 ms: se lee como máximo cada 250 ms y se promedian las últimas 4 lecturas.
#define TEMP_INTERVALO_MS 250
#define TEMP_MUESTRAS 4

// Variables de temperatura
double muestrasTemp[TEMP_MUESTRAS];
int cantidadTemp = 0;
int siguienteTemp = 0;
unsigned long ultimaLecturaTemp = 0;
double promTem = NAN;

// Promedio de las últimas 4 lecturas, sin bloquear el loop: si no han pasado 250 ms desde la última lectura, devuelve el
// promedio que ya tenía. Valor crudo: la compensación (-8.5 °C, por verificar) la aplica el backend con
// SENSOR_CALIBRATION. Con la termocupla desconectada el MAX6675 da NAN, y el promedio también (sale "nan" por TCP).
double leerTemperaturaPromedio() {
  unsigned long ahora = millis();
  if (cantidadTemp > 0 && ahora - ultimaLecturaTemp < TEMP_INTERVALO_MS) {
    return promTem;
  }
  ultimaLecturaTemp = ahora;
  double temperatura = termocupla.readCelsius();
  if (temperatura != temperatura) {  // solo NaN es distinto de si mismo; no depende de isnan()
    cantidadTemp = 0;
    siguienteTemp = 0;
    promTem = NAN;
    return promTem;
  }
  muestrasTemp[siguienteTemp] = temperatura;
  siguienteTemp = (siguienteTemp + 1) % TEMP_MUESTRAS;
  if (cantidadTemp < TEMP_MUESTRAS) {
    cantidadTemp++;
  }
  double suma = 0;
  for (int i = 0; i < cantidadTemp; i++) {
    suma += muestrasTemp[i];
  }
  promTem = suma / cantidadTemp;
  return promTem;
}

#endif
