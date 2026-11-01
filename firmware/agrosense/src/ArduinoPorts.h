// The only file that touches the board: UNO R4 WiFi, its WiFiS3 core library, and Adafruit's DHT and SHT31 libraries.
#pragma once
#include <Adafruit_SHT31.h>
#include <Arduino.h>
#include <DHT.h>
#include <FspTimer.h>
#include <WiFiS3.h>
#include <WiFiSSLClient.h>

#include "AgroSenseDevice.h"
#include "HttpResponseReader.h"

namespace agro {

constexpr uint8_t PIN_SOIL = A0;
constexpr uint8_t PIN_DHT = 4;
constexpr uint8_t PIN_RELAY = 7;
constexpr uint8_t PIN_BUTTON = 2;
constexpr bool RELAY_ACTIVE_LOW = true;  // most relay boards; set false if the pump runs while it should be off
constexpr int CONNECT_TIMEOUT_MS = 8000;
constexpr uint32_t ANSWER_TIMEOUT_MS = 45000;  // longer than the event service waits for a sleeping backend
constexpr float PUMP_WATCH_HZ = 10.0f;

using Dht22Adapter = Dht22AdapterT<DHT>;
using Sht31Adapter = Sht31AdapterT<Adafruit_SHT31>;

class BoardClock : public IClock {
 public:
  uint32_t nowMs() override { return millis(); }
  uint32_t epochSeconds() override {
    unsigned long time = WiFi.getTime();
    return WiFi.status() == WL_CONNECTED ? static_cast<uint32_t>(time) : 0;
  }
};

class BoardAnalog : public AnalogPort {
 public:
  int read(uint8_t pin) override { return analogRead(pin); }
};

class BoardDigital : public DigitalPort {
 public:
  void write(uint8_t pin, bool high) override { digitalWrite(pin, high ? HIGH : LOW); }
};

class BoardWifi : public WifiPort {
 public:
  void begin(const char* ssid, const char* password) override { WiFi.begin(ssid, password); }
  bool connected() override { return WiFi.status() == WL_CONNECTED; }
};

// Pressed means the pin went low; a press counts once, and bounces inside 50 ms do not count.
class BoardButton : public IButton {
 public:
  bool pressed() override {
    bool down = digitalRead(PIN_BUTTON) == LOW;
    uint32_t now = millis();
    bool press = down && !down_ && now - changedAt_ > 50;
    if (down != down_) {
      down_ = down;
      changedAt_ = now;
    }
    return press;
  }

 private:
  bool down_ = false;
  uint32_t changedAt_ = 0;
};

// Connecting blocks inside WiFiS3, for at most CONNECT_TIMEOUT_MS: the board talks to its Wi-Fi module by AT commands.
// The wait for the answer does not block: poll() takes what has arrived and returns.
class HttpBackend : public IBackend {
 public:
  explicit HttpBackend(IClock& clock) : clock_(clock) {}

  bool begin(const char* json) override {
    const DeviceSettings& settings = DeviceConfig::instance().get();
    secure_.setConnectionTimeout(CONNECT_TIMEOUT_MS);
    plain_.setConnectionTimeout(CONNECT_TIMEOUT_MS);
    client_ = settings.tls ? static_cast<Client*>(&secure_) : static_cast<Client*>(&plain_);
    if (!client_->connect(settings.host, settings.port)) {
      client_->stop();
      return false;
    }
    client_->print("POST /events HTTP/1.1\r\nHost: ");
    client_->print(settings.host);
    client_->print("\r\nContent-Type: application/json\r\nX-Device-Key: ");
    client_->print(settings.deviceKey);
    client_->print("\r\nConnection: close\r\nContent-Length: ");
    client_->print(strlen(json));
    client_->print("\r\n\r\n");
    client_->print(json);
    reader_.reset();
    deadline_ = clock_.nowMs() + ANSWER_TIMEOUT_MS;
    return true;
  }

  SendState poll(BackendResponse& response) override {
    int budget = BYTES_PER_POLL;
    while (budget-- > 0 && client_->available() > 0) reader_.feed(static_cast<char>(client_->read()));
    bool closed = client_->available() == 0 && !client_->connected();
    if (!reader_.complete() && !closed && !reached(clock_.nowMs(), deadline_)) return SendState::Busy;
    client_->stop();
    response = BackendResponse{reader_.status(), reader_.body()};
    return SendState::Done;
  }

 private:
  static constexpr int BYTES_PER_POLL = 128;

  IClock& clock_;
  WiFiSSLClient secure_;
  WiFiClient plain_;
  Client* client_ = nullptr;
  HttpResponseReader reader_;
  uint32_t deadline_ = 0;
};

// Runs from a timer interrupt, so the pump obeys its deadline even while WiFiS3 holds loop() inside a connect().
inline ActuatorDriver* watchedPump = nullptr;
inline FspTimer pumpTimer;

inline void onPumpTimer(timer_callback_args_t*) {
  if (watchedPump != nullptr) watchedPump->enforceDeadline();
}

inline bool startPumpWatch(ActuatorDriver& actuator) {
  watchedPump = &actuator;
  uint8_t type = 0;
  int8_t channel = FspTimer::get_available_timer(type);
  if (channel < 0) return false;
  return pumpTimer.begin(TIMER_MODE_PERIODIC, type, static_cast<uint8_t>(channel), PUMP_WATCH_HZ, 0.0f, onPumpTimer) &&
         pumpTimer.setup_overflow_irq() && pumpTimer.open() && pumpTimer.start();
}

class RealHardwareFactory : public HardwareFactory {
 public:
  RealHardwareFactory(IClimateSensor& climate, IClock& clock, float dryRaw, float wetRaw)
      : raw_(analog_, PIN_SOIL), soil_(raw_, dryRaw, wetRaw), climate_(climate),
        relay_(digital_, PIN_RELAY, RELAY_ACTIVE_LOW, clock), guard_(relay_, clock) {}

  ISensor& soilSensor() override { return soil_.end(); }
  IClimateSensor& climateSensor() override { return climate_; }
  ActuatorDriver& actuator() override { return guard_; }
  IButton& button() override { return button_; }

 private:
  BoardAnalog analog_;
  BoardDigital digital_;
  CapacitiveSoilSensor raw_;
  SoilChain soil_;
  IClimateSensor& climate_;
  RelayDriver relay_;
  PumpGuard guard_;
  BoardButton button_;
};

}  // namespace agro
