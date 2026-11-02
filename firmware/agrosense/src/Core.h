// What the rest of the firmware depends on. Nothing here, or in any header but ArduinoPorts.h, includes Arduino.h,
// so the same logic compiles for the board and for the tests on a PC.
#pragma once
#include <stddef.h>
#include <stdint.h>

namespace agro {

struct Reading {
  float value;
  bool valid;
  static Reading ok(float value) { return Reading{value, true}; }
  static Reading bad() { return Reading{0.0f, false}; }
};

struct Climate {
  Reading temperature;
  Reading humidity;
};

class ISensor {
 public:
  virtual ~ISensor() = default;
  virtual Reading read() = 0;
};

class IClimateSensor {
 public:
  virtual ~IClimateSensor() = default;
  virtual bool begin() = 0;
  virtual Climate read() = 0;
};

class IClock {
 public:
  virtual ~IClock() = default;
  virtual uint32_t nowMs() = 0;
  virtual uint32_t epochSeconds() = 0;  // 0 while the time is unknown
};

class IButton {
 public:
  virtual ~IButton() = default;
  virtual bool pressed() = 0;
};

class AnalogPort {
 public:
  virtual ~AnalogPort() = default;
  virtual int read(uint8_t pin) = 0;
};

class DigitalPort {
 public:
  virtual ~DigitalPort() = default;
  virtual void write(uint8_t pin, bool high) = 0;
};

// millis() wraps every 49 days; the unsigned difference stays right across the wrap.
inline bool elapsed(uint32_t now, uint32_t since, uint32_t length) { return now - since >= length; }

// For a moment in the near future that is checked often: past 24.8 days late it reads as not reached yet. Anything
// that may go unchecked for weeks has to use elapsed().
inline bool reached(uint32_t now, uint32_t moment) { return static_cast<int32_t>(now - moment) >= 0; }

}  // namespace agro
