#pragma once
#include "Arduino.h"

class Adafruit_SHT31 {
 public:
  explicit Adafruit_SHT31(TwoWire* wire);
  bool begin(uint8_t address);
  bool readBoth(float* temperature, float* humidity);
};
