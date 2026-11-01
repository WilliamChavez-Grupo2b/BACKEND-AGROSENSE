// Facade: the sketch only calls begin() and tick(). Reading the chain, building the event, the proxy, Wi-Fi, the
// button and the pump's safety all stay behind these two calls.
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
  uint32_t id;
};

// The answer to POST /events may carry
// {"command":{"id":12,"type":"irrigate","source":"AI","minutes":7,"confidence":0.8}}.
inline IrrigationCommand parseCommand(const char* body) {
  IrrigationCommand none{false, false, 0, 0.0f, 0};
  const char* command = body == nullptr ? nullptr : strstr(body, "\"command\"");
  if (command == nullptr || strstr(command, "\"irrigate\"") == nullptr) return none;
  const char* minutes = strstr(command, "\"minutes\":");
  if (minutes == nullptr) return none;
  long length = strtol(minutes + 10, nullptr, 10);
  if (length <= 0 || length > 65535) return none;
  const char* confidence = strstr(command, "\"confidence\":");
  const char* id = strstr(command, "\"id\":");
  return IrrigationCommand{true, strstr(command, "\"source\":\"AI\"") != nullptr, static_cast<uint16_t>(length),
                           confidence == nullptr ? 0.0f : static_cast<float>(strtod(confidence + 13, nullptr)),
                           id == nullptr ? 0 : static_cast<uint32_t>(strtoul(id + 5, nullptr, 10))};
}

class AgroSenseDevice {
 public:
  AgroSenseDevice(HardwareFactory& hardware, BackendProxy& backend, IClock& clock)
      : hardware_(hardware), backend_(backend), clock_(clock) {}

  bool begin() {
    hardware_.actuator().begin();
    bool climateReady = hardware_.climateSensor().begin();
    nextReadingAt_ = clock_.nowMs();
    return DeviceConfig::instance().valid() && climateReady;
  }

  void tick() {
    ActuatorDriver& actuator = hardware_.actuator();
    actuator.update();
    if (hardware_.button().pressed()) toggleByHand();
    reportFinishedOrder();

    uint32_t now = clock_.nowMs();
    if (reached(now, nextReadingAt_)) {
      nextReadingAt_ = now + DeviceConfig::instance().get().intervalMs;
      measure();
    }
    if (WifiManager::instance().ensureConnected(now) || backend_.busy()) {
      BackendResponse response{0, nullptr};
      if (backend_.poll(response) == SendState::Done) obey(parseCommand(response.body));
    }
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
    ReadingJsonBuilder builder(event, sizeof event);
    builder.device(DeviceConfig::instance().get().deviceId)
        .timestamp(clock_.epochSeconds())
        .soilMoisture(soil_)
        .airTemperature(climate.temperature)
        .relativeHumidity(climate.humidity);
    if (ackId_ != 0) builder.acknowledge(ackId_, ackResult_, ackSeconds_);
    if (builder.build()) {
      backend_.begin(event);
      ackId_ = 0;
    }
  }

  void obey(const IrrigationCommand& command) {
    if (!command.present) return;
    ActuatorDriver& actuator = hardware_.actuator();
    bool started = command.fromAi ? AiIrrigationOrder(actuator, command.minutes, command.confidence, soil_).execute()
                                  : ManualIrrigationOrder(actuator, command.minutes).execute();
    ackId_ = command.id;
    ackResult_ = started ? AckResult::Started : AckResult::Refused;
    runningOrder_ = started ? command.id : 0;
  }

  // What the history must show is what ran, which the device alone knows. A short run may replace the "started" not
  // sent yet: the backend accepts "finished" directly.
  void reportFinishedOrder() {
    if (runningOrder_ == 0 || hardware_.actuator().running()) return;
    ackId_ = runningOrder_;
    ackResult_ = AckResult::Finished;
    ackSeconds_ = hardware_.actuator().lastRunMs() / 1000;
    runningOrder_ = 0;
  }

  HardwareFactory& hardware_;
  BackendProxy& backend_;
  IClock& clock_;
  Reading soil_ = Reading::bad();
  uint32_t nextReadingAt_ = 0;
  uint32_t ackId_ = 0;
  uint32_t runningOrder_ = 0;
  uint32_t ackSeconds_ = 0;
  AckResult ackResult_ = AckResult::Started;
};

}  // namespace agro
