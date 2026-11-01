// Decorator: CapacitiveSoilSensor -> MedianFilter -> Calibration -> RangeCheck. Each step is an ISensor wrapping an
// ISensor, so the device reads the end of the chain the same way it would read the bare sensor.
#pragma once
#include "Core.h"

namespace agro {

class CapacitiveSoilSensor : public ISensor {
 public:
  static constexpr int ADC_MAX = 16383;  // the UNO R4 reads 14 bits

  CapacitiveSoilSensor(AnalogPort& port, uint8_t pin) : port_(port), pin_(pin) {}

  // A reading stuck at either rail is a loose wire, not a soil.
  Reading read() override {
    int raw = port_.read(pin_);
    return raw <= 0 || raw >= ADC_MAX ? Reading::bad() : Reading::ok(static_cast<float>(raw));
  }

 private:
  AnalogPort& port_;
  uint8_t pin_;
};

class MedianFilter : public ISensor {
 public:
  static constexpr uint8_t SAMPLES = 5;

  explicit MedianFilter(ISensor& inner) : inner_(inner) {}

  // One spike cannot move a median. Bad samples are left out; with a majority of them there is no reading.
  Reading read() override {
    float samples[SAMPLES];
    uint8_t count = 0;
    for (uint8_t i = 0; i < SAMPLES; i++) {
      Reading sample = inner_.read();
      if (!sample.valid) continue;
      uint8_t at = count++;
      while (at > 0 && samples[at - 1] > sample.value) {
        samples[at] = samples[at - 1];
        at--;
      }
      samples[at] = sample.value;
    }
    return count * 2 > SAMPLES ? Reading::ok(samples[count / 2]) : Reading::bad();
  }

 private:
  ISensor& inner_;
};

class Calibration : public ISensor {
 public:
  Calibration(ISensor& inner, float dryRaw, float wetRaw) : inner_(inner), dryRaw_(dryRaw), wetRaw_(wetRaw) {}

  // 0 % at the dry reading and 100 % at the wet one, whichever way the sensor counts. Not clamped: a value far
  // outside is what lets RangeCheck notice a broken sensor.
  Reading read() override {
    Reading raw = inner_.read();
    if (!raw.valid) return raw;
    return Reading::ok((raw.value - dryRaw_) / (wetRaw_ - dryRaw_) * 100.0f);
  }

 private:
  ISensor& inner_;
  float dryRaw_;
  float wetRaw_;
};

class RangeCheck : public ISensor {
 public:
  RangeCheck(ISensor& inner, float low, float high, float margin)
      : inner_(inner), low_(low), high_(high), margin_(margin) {}

  // Just past a limit is soil drier or wetter than the calibration saw; well past it is not a measurement.
  Reading read() override {
    Reading reading = inner_.read();
    if (!reading.valid || reading.value < low_ - margin_ || reading.value > high_ + margin_) return Reading::bad();
    if (reading.value < low_) return Reading::ok(low_);
    if (reading.value > high_) return Reading::ok(high_);
    return reading;
  }

 private:
  ISensor& inner_;
  float low_;
  float high_;
  float margin_;
};

// The chain itself, built once with its links as members so nothing is allocated.
class SoilChain {
 public:
  static constexpr float MARGIN_PERCENT = 15.0f;

  SoilChain(ISensor& raw, float dryRaw, float wetRaw)
      : median_(raw), calibration_(median_, dryRaw, wetRaw), range_(calibration_, 0.0f, 100.0f, MARGIN_PERCENT) {}

  ISensor& end() { return range_; }

 private:
  MedianFilter median_;
  Calibration calibration_;
  RangeCheck range_;
};

}  // namespace agro
