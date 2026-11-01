// Abstract Factory: a family of parts that belong together. RealHardwareFactory (ArduinoPorts.h) makes the board's;
// SimulatedHardwareFactory makes parts that run on a bench with nothing wired, and in the tests.
#pragma once
#include "Core.h"
#include "Irrigation.h"
#include "SensorChain.h"

namespace agro {

class HardwareFactory {
 public:
  virtual ~HardwareFactory() = default;
  virtual ISensor& soilSensor() = 0;
  virtual IClimateSensor& climateSensor() = 0;
  virtual ActuatorDriver& actuator() = 0;
  virtual IButton& button() = 0;
};

class SimulatedSoil : public ISensor {
 public:
  explicit SimulatedSoil(int raw) : raw_(raw) {}
  void set(int raw) { raw_ = raw; }
  Reading read() override { return Reading::ok(static_cast<float>(raw_)); }

 private:
  int raw_;
};

class SimulatedClimate : public IClimateSensor {
 public:
  bool begin() override { return true; }
  Climate read() override { return Climate{Reading::ok(24.1f), Reading::ok(61.0f)}; }
};

class SimulatedButton : public IButton {
 public:
  void press() { pressed_ = true; }
  bool pressed() override {
    bool was = pressed_;
    pressed_ = false;
    return was;
  }

 private:
  bool pressed_ = false;
};

class SimulatedHardwareFactory : public HardwareFactory {
 public:
  SimulatedHardwareFactory(IClock& clock, int dryRaw, int wetRaw)
      : raw_((dryRaw + wetRaw) / 2), soil_(raw_, static_cast<float>(dryRaw), static_cast<float>(wetRaw)), driver_(clock) {}

  ISensor& soilSensor() override { return soil_.end(); }
  IClimateSensor& climateSensor() override { return climate_; }
  ActuatorDriver& actuator() override { return driver_; }
  IButton& button() override { return button_; }

  SimulatedSoil& rawSoil() { return raw_; }
  SimulatedButton& simulatedButton() { return button_; }
  SimulatedDriver& simulatedDriver() { return driver_; }

 private:
  SimulatedSoil raw_;
  SoilChain soil_;
  SimulatedClimate climate_;
  SimulatedDriver driver_;
  SimulatedButton button_;
};

}  // namespace agro
