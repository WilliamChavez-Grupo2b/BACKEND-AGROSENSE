// The only file that touches the board: UNO R4 WiFi, its WiFiS3 core library, and Adafruit's DHT and SHT31 libraries.
#pragma once
#include <Adafruit_SHT31.h>
#include <Arduino.h>
#include <DHT.h>
#include <WiFiS3.h>
#include <WiFiSSLClient.h>

#include "AgroSenseDevice.h"

namespace agro {

constexpr uint8_t PIN_SOIL = A0;
constexpr uint8_t PIN_DHT = 4;
constexpr uint8_t PIN_RELAY = 7;
constexpr uint8_t PIN_BUTTON = 2;
constexpr bool RELAY_ACTIVE_LOW = true;  // most relay boards; set false if the pump runs while it should be off
constexpr uint32_t HTTP_TIMEOUT_MS = 45000;

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

class HttpBackend : public IBackend {
 public:
  BackendResponse post(const char* json) override {
    const DeviceSettings& settings = DeviceConfig::instance().get();
    Client& client = settings.tls ? static_cast<Client&>(secure_) : static_cast<Client&>(plain_);
    client.setTimeout(HTTP_TIMEOUT_MS);
    if (!client.connect(settings.host, settings.port)) return BackendResponse{0, nullptr};
    client.print("POST /events HTTP/1.1\r\nHost: ");
    client.print(settings.host);
    client.print("\r\nContent-Type: application/json\r\nX-Device-Key: ");
    client.print(settings.deviceKey);
    client.print("\r\nConnection: close\r\nContent-Length: ");
    client.print(strlen(json));
    client.print("\r\n\r\n");
    client.print(json);
    int status = readStatus(client);
    readBody(client);
    client.stop();
    return BackendResponse{status, body_};
  }

 private:
  static int readStatus(Client& client) {
    if (!client.find("HTTP/1.1 ")) return 0;
    return static_cast<int>(client.parseInt());
  }

  void readBody(Client& client) {
    body_[0] = '\0';
    if (!client.find("\r\n\r\n")) return;
    size_t length = client.readBytes(body_, sizeof body_ - 1);
    body_[length] = '\0';
  }

  WiFiSSLClient secure_;
  WiFiClient plain_;
  char body_[256];
};

class RealHardwareFactory : public HardwareFactory {
 public:
  RealHardwareFactory(IClimateSensor& climate, IClock& clock, float dryRaw, float wetRaw)
      : raw_(analog_, PIN_SOIL), soil_(raw_, dryRaw, wetRaw), climate_(climate),
        relay_(digital_, PIN_RELAY, RELAY_ACTIVE_LOW, clock) {}

  ISensor& soilSensor() override { return soil_.end(); }
  IClimateSensor& climateSensor() override { return climate_; }
  ActuatorDriver& actuator() override { return relay_; }
  IButton& button() override { return button_; }

 private:
  BoardAnalog analog_;
  BoardDigital digital_;
  CapacitiveSoilSensor raw_;
  SoilChain soil_;
  IClimateSensor& climate_;
  RelayDriver relay_;
  BoardButton button_;
};

}  // namespace agro
