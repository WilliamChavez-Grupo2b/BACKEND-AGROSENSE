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
// {"command":{"id":12,"type":"irrigate","source":"AI","minutes":7,"confidence":0.8}}. Anything less than a whole order
// with an id and a known origin is no order: an unknown origin must not become a manual one, the kind that skips the
// soil check, and a cut answer could turn 12 minutes into 1.
inline IrrigationCommand parseCommand(const char* body) {
  IrrigationCommand none{false, false, 0, 0.0f, 0};
  const char* command = body == nullptr ? nullptr : strstr(body, "\"command\"");
  if (command == nullptr || strstr(command, "\"irrigate\"") == nullptr || strchr(command, '}') == nullptr) return none;
  const char* minutes = strstr(command, "\"minutes\":");
  const char* id = strstr(command, "\"id\":");
  if (minutes == nullptr || id == nullptr) return none;
  long length = strtol(minutes + 10, nullptr, 10);
  long order = strtol(id + 5, nullptr, 10);
  if (length <= 0 || length > 65535 || order <= 0) return none;
  bool fromAi = strstr(command, "\"source\":\"AI\"") != nullptr;
  if (!fromAi && strstr(command, "\"source\":\"MANUAL\"") == nullptr) return none;
  const char* confidence = strstr(command, "\"confidence\":");
  return IrrigationCommand{true, fromAi, static_cast<uint16_t>(length),
                           confidence == nullptr ? 0.0f : static_cast<float>(strtod(confidence + 13, nullptr)),
                           static_cast<uint32_t>(order)};
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
      bool answered = backend_.poll(response) == SendState::Done;
      if (answered && response.status >= 200 && response.status < 300) obey(parseCommand(response.body));
    }
    actuator.update();
  }

  Reading lastSoil() const { return soil_; }

 private:
  struct Ack {
    uint32_t id;
    AckResult result;
    uint32_t seconds;
  };
  static constexpr uint8_t MAX_ACKS = 4;  // one travels per event, and two orders can be decided between two events

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
    if (ackCount_ > 0) builder.acknowledge(acks_[0].id, acks_[0].result, acks_[0].seconds);
    if (builder.build()) {
      backend_.begin(event);
      forgetFirstAck();
    }
  }

  // An order seen again is one already decided: running it, or refusing it, would contradict what was reported.
  void obey(const IrrigationCommand& command) {
    if (!command.present || command.id == lastOrder_) return;
    lastOrder_ = command.id;
    ActuatorDriver& actuator = hardware_.actuator();
    bool started = command.fromAi ? AiIrrigationOrder(actuator, command.minutes, command.confidence, soil_).execute()
                                  : ManualIrrigationOrder(actuator, command.minutes).execute();
    report(Ack{command.id, started ? AckResult::Started : AckResult::Refused, 0});
    if (started) runningOrder_ = command.id;
  }

  // What the history must show is what ran, which the device alone knows. A short run may replace the "started" not
  // sent yet: the backend accepts "finished" directly.
  void reportFinishedOrder() {
    if (runningOrder_ == 0 || hardware_.actuator().running()) return;
    report(Ack{runningOrder_, AckResult::Finished, hardware_.actuator().lastRunMs() / 1000});
    runningOrder_ = 0;
  }

  void report(const Ack& ack) {
    for (uint8_t i = 0; i < ackCount_; i++) {
      if (acks_[i].id == ack.id) {
        acks_[i] = ack;
        return;
      }
    }
    if (ackCount_ == MAX_ACKS) forgetFirstAck();
    acks_[ackCount_++] = ack;
  }

  void forgetFirstAck() {
    if (ackCount_ == 0) return;
    ackCount_--;
    for (uint8_t i = 0; i < ackCount_; i++) acks_[i] = acks_[i + 1];
  }

  HardwareFactory& hardware_;
  BackendProxy& backend_;
  IClock& clock_;
  Reading soil_ = Reading::bad();
  uint32_t nextReadingAt_ = 0;
  uint32_t lastOrder_ = 0;
  uint32_t runningOrder_ = 0;
  Ack acks_[MAX_ACKS];
  uint8_t ackCount_ = 0;
};

}  // namespace agro
