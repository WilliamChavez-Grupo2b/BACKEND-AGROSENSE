// Facade: the sketch only calls begin() and tick(). Reading the chain, building the event, the proxy, Wi-Fi, the
// button and the pump's timer all stay behind these two calls.
#pragma once
#include <stdlib.h>
#include <string.h>

#include "BackendProxy.h"
#include "ClimateAdapters.h"
#include "Core.h"
#include "DeviceConfig.h"
#include "HardwareFactory.h"
#include "Irrigation.h"
#include "ReadingJsonBuilder.h"
#include "WifiManager.h"

namespace agro {

struct IrrigationCommand {
  bool present;
  bool fromAi;
  uint16_t minutes;
  float confidence;
};

// The answer to POST /events may carry {"command":{"type":"irrigate","source":"AI","minutes":7,"confidence":0.8}}.
inline IrrigationCommand parseCommand(const char* body) {
  IrrigationCommand none{false, false, 0, 0.0f};
  const char* command = body == nullptr ? nullptr : strstr(body, "\"command\"");
  if (command == nullptr || strstr(command, "\"irrigate\"") == nullptr) return none;
  const char* minutes = strstr(command, "\"minutes\":");
  if (minutes == nullptr) return none;
  long length = strtol(minutes + 10, nullptr, 10);
  if (length <= 0 || length > 65535) return none;
  const char* confidence = strstr(command, "\"confidence\":");
  return IrrigationCommand{true, strstr(command, "\"source\":\"AI\"") != nullptr, static_cast<uint16_t>(length),
                           confidence == nullptr ? 0.0f : static_cast<float>(strtod(confidence + 13, nullptr))};
}

class AgroSenseDevice {
 public:
  AgroSenseDevice(HardwareFactory& hardware, BackendProxy& backend, IClock& clock)
      : hardware_(hardware), backend_(backend), clock_(clock) {}

  bool begin() {
    bool climateReady = hardware_.climateSensor().begin();
    nextReadingAt_ = clock_.nowMs();
    return DeviceConfig::instance().valid() && climateReady;
  }

  void tick() {
    ActuatorDriver& actuator = hardware_.actuator();
    actuator.update();
    if (hardware_.button().pressed()) toggleByHand();

    uint32_t now = clock_.nowMs();
    if (reached(now, nextReadingAt_)) {
      nextReadingAt_ = now + DeviceConfig::instance().get().intervalMs;
      measure();
    }
    if (WifiManager::instance().ensureConnected(now)) obey(parseCommand(backend_.flush().body));
    actuator.update();
  }

  Reading lastSoil() const { return soil_; }

 private:
  void toggleByHand() {
    ActuatorDriver& actuator = hardware_.actuator();
    if (actuator.running()) {
      actuator.stop();
    } else {
      ManualIrrigationOrder(actuator, DeviceConfig::instance().get().manualMinutes).execute();
    }
  }

  void measure() {
    soil_ = hardware_.soilSensor().read();
    Climate climate = hardware_.climateSensor().read();
    char event[BackendProxy::EVENT_BYTES];
    bool built = ReadingJsonBuilder(event, sizeof event)
                     .device(DeviceConfig::instance().get().deviceId)
                     .timestamp(clock_.epochSeconds())
                     .soilMoisture(soil_)
                     .airTemperature(climate.temperature)
                     .relativeHumidity(climate.humidity)
                     .build();
    if (built) backend_.enqueue(event);
  }

  void obey(const IrrigationCommand& command) {
    if (!command.present) return;
    ActuatorDriver& actuator = hardware_.actuator();
    if (command.fromAi) {
      AiIrrigationOrder(actuator, command.minutes, command.confidence, soil_).execute();
    } else {
      ManualIrrigationOrder(actuator, command.minutes).execute();
    }
  }

  HardwareFactory& hardware_;
  BackendProxy& backend_;
  IClock& clock_;
  Reading soil_ = Reading::bad();
  uint32_t nextReadingAt_ = 0;
};

}  // namespace agro
