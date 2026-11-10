
#ifndef SENSORTIERRA_H
#define SENSORTIERRA_H

#include <Arduino.h>

#define PIN_SOIL_V2 34

// PENDIENTE: calibrar. Valores de partida, no medidos con este sensor. Mídelos con el modo CALIBRAR_SUELO
// (firmware/esp32-tcp/README.md): ADC_SECO con el sensor al aire y ADC_HUMEDO con el sensor en un vaso con agua.
#define ADC_SECO 3000
#define ADC_HUMEDO 1400

inline void iniciarSensorTierra() {
  pinMode(PIN_SOIL_V2, INPUT);
}

inline float leerSensorTierra() {

  int lecturaADC = analogRead(PIN_SOIL_V2);

  float humedad = ((float)(ADC_SECO - lecturaADC) /
                  (ADC_SECO - ADC_HUMEDO)) * 100.0;

    humedad = constrain(humedad, 0.0, 100.0);

  return humedad;
}

// Modo de calibración: con #define CALIBRAR_SUELO en planticas_proyecto.ino, imprime cada segundo por Serial el valor
// crudo del ADC del sensor de suelo (0 a 4095).
inline void imprimirCrudoSuelo() {
  static unsigned long ultimaImpresion = 0;
  if (millis() - ultimaImpresion < 1000) {
    return;
  }
  ultimaImpresion = millis();
  Serial.print("CALIBRAR_SUELO adc=");
  Serial.println(analogRead(PIN_SOIL_V2));
}

#endif


