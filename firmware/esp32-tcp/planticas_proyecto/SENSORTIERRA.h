
#ifndef SENSORTIERRA_H
#define SENSORTIERRA_H

#include <Arduino.h>

#define PIN_SOIL_V2 34

#define ADC_SECO 3000
#define ADC_HUMEDO 1400

inline void iniciarSensorTierra() {
  pinMode(PIN_SOIL_V2, INPUT);
}

inline float leerSensorTierra() {

  int lecturaADC = analogRead(PIN_SOIL_V2);

  float humedad = ((float)(ADC_SECO - lecturaADC) /
                  (ADC_SECO - ADC_HUMEDO)) * 100.0;

    humedad = constrain(humedad - 76.0, 0.0, 100.0);

  return humedad;
}

#endif


