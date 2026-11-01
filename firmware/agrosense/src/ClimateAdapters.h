// Adapter: the DHT22 and the SHT31 libraries read differently and fail differently. Each adapter turns its library
// into IClimateSensor. They are templates over the library class so the tests can pass a fake with the same methods.
#pragma once
#include "Core.h"

namespace agro {

inline bool isNumber(float value) { return value == value; }

inline Reading checked(float value, float low, float high) {
  return isNumber(value) && value >= low && value <= high ? Reading::ok(value) : Reading::bad();
}

// Adafruit's DHT: two calls that give NaN on a misread, and a sensor that must rest two seconds between reads.
template <class Dht>
class Dht22AdapterT : public IClimateSensor {
 public:
  static constexpr uint32_t REST_MS = 2000;

  Dht22AdapterT(Dht& dht, IClock& clock) : dht_(dht), clock_(clock) {}

  bool begin() override {
    dht_.begin();
    return true;
  }

  Climate read() override {
    uint32_t now = clock_.nowMs();
    if (!read_ || reached(now, last_ + REST_MS)) {
      cached_ = Climate{checked(dht_.readTemperature(), -40.0f, 80.0f), checked(dht_.readHumidity(), 0.0f, 100.0f)};
      last_ = now;
      read_ = true;
    }
    return cached_;
  }

 private:
  Dht& dht_;
  IClock& clock_;
  Climate cached_{Reading::bad(), Reading::bad()};
  uint32_t last_ = 0;
  bool read_ = false;
};

// Adafruit's SHT31: one I2C transaction fills both values and says whether it worked; begin() can fail.
template <class Sht>
class Sht31AdapterT : public IClimateSensor {
 public:
  static constexpr uint8_t ADDRESS = 0x44;

  explicit Sht31AdapterT(Sht& sht) : sht_(sht) {}

  bool begin() override {
    ready_ = sht_.begin(ADDRESS);
    return ready_;
  }

  Climate read() override {
    float temperature = 0.0f;
    float humidity = 0.0f;
    if (!ready_ || !sht_.readBoth(&temperature, &humidity)) return Climate{Reading::bad(), Reading::bad()};
    return Climate{checked(temperature, -40.0f, 125.0f), checked(humidity, 0.0f, 100.0f)};
  }

 private:
  Sht& sht_;
  bool ready_ = false;
};

}  // namespace agro
