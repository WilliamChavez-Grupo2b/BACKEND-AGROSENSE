// The only file that touches the board: an ESP32 Dev Module on Espressif's esp32 core (2.x or 3.x), its WiFi and
// HTTPClient, and Adafruit's DHT and SHT31 libraries. Every other header compiles on a PC for the tests.
//
// Two tasks besides loop(): an esp_timer that enforces the pump's deadline ten times a second, and the HTTP task that
// does the blocking POST so loop(), the button and the pump never wait for the network.
#pragma once
#include <Adafruit_SHT31.h>
#include <Arduino.h>
#include <DHT.h>
#include <HTTPClient.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <Wire.h>
#include <driver/gpio.h>
#include <esp_task_wdt.h>
#include <esp_timer.h>
#include <time.h>

#include <atomic>

#include "AgroSenseDevice.h"

namespace agro {

// GPIO numbers. The soil sensor is on ADC1 (GPIO32-39): ADC2 cannot read while Wi-Fi is on. None of these is a
// strapping pin (0, 2, 5, 12, 15) or a flash pin (6-11).
constexpr uint8_t PIN_SOIL = 34;
constexpr uint8_t PIN_DHT = 4;
constexpr uint8_t PIN_RELAY = 26;
constexpr uint8_t PIN_BUTTON = 27;     // optional push button to GND; unwired, the pull-up keeps it released
constexpr bool RELAY_ACTIVE_LOW = true;  // most relay boards; set false if the pump runs while it should be off
constexpr uint8_t ADC_BITS = 12;       // 0..4095, with ADC_11db set in setup()

constexpr int32_t CONNECT_TIMEOUT_MS = 8000;
constexpr uint32_t TLS_HANDSHAKE_S = 15;
constexpr uint16_t ANSWER_TIMEOUT_MS = 45000;  // longer than the event service waits for a sleeping backend
constexpr uint32_t SEND_HANG_MS = 120000;      // past connect + handshake + answer: the HTTP task is stuck
constexpr uint32_t PUMP_WATCH_US = 100000;     // ten times a second
constexpr uint32_t WATCHDOG_MS = 10000;        // loop() never blocks on the network, so 10 s is plenty
constexpr uint32_t HTTP_TASK_STACK = 12288;    // TLS needs a deep stack

using Dht22Adapter = Dht22AdapterT<DHT>;
using Sht31Adapter = Sht31AdapterT<Adafruit_SHT31>;

// No inline variables: core 2.x compiles with gnu++11, so the shared state lives in function statics.
inline ActuatorDriver*& watchedPump() {
  static ActuatorDriver* pump = nullptr;
  return pump;
}

inline bool& watchdogOn() {
  static bool on = false;
  return on;
}

class BoardClock : public IClock {
 public:
  uint32_t nowMs() override { return millis(); }
  // Seconds since 1970 in UTC once NTP has answered; before that time() counts from the boot, which the device refuses.
  uint32_t epochSeconds() override {
    time_t now = time(nullptr);
    return now > 0 ? static_cast<uint32_t>(now) : 0;
  }
};

// configTime() needs the network stack, so it starts with the first connection and only once. The offset only affects
// localtime(); the ts of each event is built from the UTC seconds and the offset in DeviceSettings.
inline void startNetworkTimeOnce(long utcOffsetSeconds) {
  static bool started = false;
  if (started || WiFi.status() != WL_CONNECTED) return;
  configTime(utcOffsetSeconds, 0, "pool.ntp.org", "time.google.com");
  started = true;
}

// The level first and the direction after, so the pin starts driving already at "off" and the relay does not close
// for an instant at boot. digitalWrite() before pinMode() is not enough: core 3.x ignores a write to a pin that is not
// set up as GPIO yet. gpio_set_level() writes the output register directly.
inline void configureRelayPinOff() {
  const int off = RELAY_ACTIVE_LOW ? 1 : 0;
  gpio_set_level(static_cast<gpio_num_t>(PIN_RELAY), off);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, off);
}

class BoardAnalog : public AnalogPort {
 public:
  int read(uint8_t pin) override { return analogRead(pin); }
};

class BoardDigital : public DigitalPort {
 public:
  void write(uint8_t pin, bool high) override { digitalWrite(pin, high ? HIGH : LOW); }
};

// WifiManager decides when to try again, so the core's own reconnection stays off.
class BoardWifi : public WifiPort {
 public:
  void begin(const char* ssid, const char* password) override {
    if (!configured_) {
      WiFi.mode(WIFI_STA);
      WiFi.setAutoReconnect(false);
      configured_ = true;
    } else {
      WiFi.disconnect();
    }
    WiFi.begin(ssid, password);
  }
  bool connected() override { return WiFi.status() == WL_CONNECTED; }

 private:
  bool configured_ = false;
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

// Keeps the first bytes of the body and counts the rest, so a long answer is not a failed one.
class BodySink : public Stream {
 public:
  BodySink(char* buffer, size_t size) : buffer_(buffer), size_(size) { buffer_[0] = '\0'; }
  size_t write(uint8_t c) override {
    if (used_ + 1 < size_) {
      buffer_[used_++] = static_cast<char>(c);
      buffer_[used_] = '\0';
    }
    return 1;
  }
  int available() override { return 0; }
  int read() override { return -1; }
  int peek() override { return -1; }

 private:
  char* buffer_;
  size_t size_;
  size_t used_ = 0;
};

[[noreturn]] inline void restartWithThePumpOff() {
  if (watchedPump() != nullptr) watchedPump()->stop();
  esp_restart();
  for (;;) {
  }
}

// POST /events with X-Device-Key over HTTPClient, in a task of its own: begin() hands the event over and returns,
// poll() says whether the answer is in. A request that outlives SEND_HANG_MS means the task is stuck, and the board
// restarts with the pump off.
class HttpBackend : public IBackend {
 public:
  static constexpr size_t BODY_BYTES = 384;

  explicit HttpBackend(IClock& clock) : clock_(clock) {}

  bool startWorker() {
    if (task_ != nullptr) return true;
    return xTaskCreate(work, "agro-http", HTTP_TASK_STACK, this, 1, &task_) == pdPASS;
  }

  bool begin(const char* json) override {
    size_t length = strlen(json);
    if (task_ == nullptr || state_.load() != IDLE || length >= sizeof request_) return false;
    memcpy(request_, json, length + 1);
    deadline_ = clock_.nowMs() + SEND_HANG_MS;
    state_.store(SENDING);
    xTaskNotifyGive(task_);
    return true;
  }

  SendState poll(BackendResponse& response) override {
    response = BackendResponse{0, nullptr};
    uint8_t state = state_.load();
    if (state == SENDING) {
      if (reached(clock_.nowMs(), deadline_)) restartWithThePumpOff();
      return SendState::Busy;
    }
    if (state == ANSWERED) {
      response = BackendResponse{status_, body_};
      state_.store(IDLE);
    }
    return SendState::Done;
  }

 private:
  enum : uint8_t { IDLE, SENDING, ANSWERED };

  static void work(void* self) { static_cast<HttpBackend*>(self)->serve(); }

  void serve() {
    for (;;) {
      ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
      status_ = post();
      state_.store(ANSWERED);  // last: loop() reads status_ and body_ only after it sees this
    }
  }

  // 0 when nothing whole came back: no connection, a timeout, or a body cut short. The proxy then retries; a repeated
  // event with the same ts is not stored twice.
  int post() {
    const DeviceSettings& settings = DeviceConfig::instance().get();
    HTTPClient http;
    http.setReuse(false);
    http.setConnectTimeout(CONNECT_TIMEOUT_MS);
    http.setTimeout(ANSWER_TIMEOUT_MS);
    bool opened = false;
    if (settings.tls) {
      if (settings.caCert != nullptr && settings.caCert[0] != '\0') {
        secure_.setCACert(settings.caCert);
      } else {
        secure_.setInsecure();
      }
      secure_.setHandshakeTimeout(TLS_HANDSHAKE_S);
      opened = http.begin(secure_, settings.host, settings.port, "/events", true);
    } else {
      opened = http.begin(plain_, settings.host, settings.port, "/events", false);
    }
    body_[0] = '\0';
    if (!opened) return 0;
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-Key", settings.deviceKey);
    int status = http.POST(reinterpret_cast<uint8_t*>(request_), strlen(request_));
    if (status > 0 && http.getSize() != 0) {
      BodySink sink(body_, sizeof body_);
      if (http.writeToStream(&sink) < 0) status = 0;
    }
    http.end();
    return status > 0 ? status : 0;
  }

  IClock& clock_;
  WiFiClientSecure secure_;
  WiFiClient plain_;
  TaskHandle_t task_ = nullptr;
  std::atomic<uint8_t> state_{IDLE};
  int status_ = 0;
  uint32_t deadline_ = 0;
  char request_[BackendProxy::EVENT_BYTES];
  char body_[BODY_BYTES];
};

// Runs in the esp_timer task, not in loop(): the pump obeys its deadline even if loop() is stuck.
inline void onPumpTimer(void*) {
  if (watchedPump() != nullptr) watchedPump()->enforceDeadline();
}

inline bool startPumpWatch(ActuatorDriver& actuator) {
  watchedPump() = &actuator;
  static esp_timer_handle_t timer = nullptr;
  esp_timer_create_args_t args = {};
  args.callback = onPumpTimer;
  args.name = "agro-pump";
  return esp_timer_create(&args, &timer) == ESP_OK && esp_timer_start_periodic(timer, PUMP_WATCH_US) == ESP_OK;
}

// The task watchdog watches loop() itself. Core 3.x (ESP-IDF 5) already started it with its own settings, so they are
// replaced; core 2.x (ESP-IDF 4) takes seconds and starts or updates it with the same call.
inline bool startWatchdog() {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  esp_task_wdt_config_t config = {};
  config.timeout_ms = WATCHDOG_MS;
  config.idle_core_mask = 0;
  config.trigger_panic = true;
  esp_err_t ready = esp_task_wdt_reconfigure(&config);
  if (ready == ESP_ERR_INVALID_STATE) ready = esp_task_wdt_init(&config);
#else
  esp_err_t ready = esp_task_wdt_init(WATCHDOG_MS / 1000, true);
#endif
  watchdogOn() = ready == ESP_OK && esp_task_wdt_add(nullptr) == ESP_OK;
  return watchdogOn();
}

inline void feedWatchdog() {
  if (watchdogOn()) esp_task_wdt_reset();
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
