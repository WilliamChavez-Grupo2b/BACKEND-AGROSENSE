// Singleton: there is one board and one configuration. A static instance, because the board has no heap to spare.
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
  uint32_t intervalMs = 30000;
  int soilDryRaw = 12000;  // 14-bit readings; measure both with the sensor in dry soil and in water
  int soilWetRaw = 6000;
  uint8_t manualMinutes = 10;
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
