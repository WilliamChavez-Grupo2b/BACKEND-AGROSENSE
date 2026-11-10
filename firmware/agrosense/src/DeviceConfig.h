// Singleton: there is one board and one configuration. A static instance, so nothing depends on the heap.
#pragma once
#include "Core.h"

namespace agro {

struct DeviceSettings {
  const char* wifiSsid = "";
  const char* wifiPassword = "";
  const char* deviceId = "";
  const char* deviceKey = "";
  const char* host = "";
  uint16_t port = 443;
  bool tls = true;
  const char* caCert = "";  // PEM of the event service's root CA; empty encrypts without checking who answers
  int16_t utcOffsetMinutes = -300;  // the field's zone, -05:00, for the ts of every event
  uint32_t intervalMs = 30000;
  // NOT CALIBRATED: two equal values mean no soil moisture is reported at all. Run firmware/calibrate_soil with your
  // own sensor and soil and put its two counts (0 to 4095) in AGRO_SOIL_DRY_RAW and AGRO_SOIL_WET_RAW of
  // arduino_secrets.h. Invented numbers would make every percentage, and every decision made from it, wrong.
  int soilDryRaw = 0;
  int soilWetRaw = 0;
  uint8_t manualMinutes = 5;
};

class DeviceConfig {
 public:
  static DeviceConfig& instance() {
    static DeviceConfig config;
    return config;
  }
  DeviceConfig(const DeviceConfig&) = delete;
  DeviceConfig& operator=(const DeviceConfig&) = delete;

  void load(const DeviceSettings& settings) { settings_ = settings; }
  const DeviceSettings& get() const { return settings_; }

  bool valid() const {
    return settings_.deviceId[0] != '\0' && settings_.deviceKey[0] != '\0' && settings_.host[0] != '\0' &&
           settings_.wifiSsid[0] != '\0' && settings_.soilDryRaw != settings_.soilWetRaw;
  }

 private:
  DeviceConfig() = default;
  DeviceSettings settings_;
};

}  // namespace agro
