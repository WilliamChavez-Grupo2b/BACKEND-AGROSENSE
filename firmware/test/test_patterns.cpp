// Runs on a PC: g++ -std=c++17 -I../agrosense/src test_patterns.cpp. See run-tests.sh.
#include <cstdio>
#include <cstring>
#include <string>
#include <cstdlib>
#include <vector>

#include "AgroSenseDevice.h"
#include "HttpResponseReader.h"
#include "LoopSupervisor.h"

using namespace agro;

static int failures = 0;
static int checks = 0;

#define CHECK(condition)                                                \
  do {                                                                  \
    checks++;                                                           \
    if (!(condition)) {                                                 \
      failures++;                                                       \
      printf("FAIL %s:%d: %s\n", __func__, __LINE__, #condition);       \
    }                                                                   \
  } while (0)

class FakeClock : public IClock {
 public:
  uint32_t ms = 1000;
  uint32_t epoch = 1793500000;
  uint32_t nowMs() override { return ms; }
  uint32_t epochSeconds() override { return epoch; }
};

class Scripted : public ISensor {
 public:
  explicit Scripted(std::vector<Reading> values) : values_(values) {}
  Reading read() override { return values_[at_++ % values_.size()]; }

 private:
  std::vector<Reading> values_;
  size_t at_ = 0;
};

class FakeAnalog : public AnalogPort {
 public:
  int value = 9000;
  int read(uint8_t) override { return value; }
};

class FakeDigital : public DigitalPort {
 public:
  std::vector<bool> writes;
  void write(uint8_t, bool high) override { writes.push_back(high); }
};

class FakeWifi : public WifiPort {
 public:
  bool up = false;
  int begins = 0;
  void begin(const char*, const char*) override { begins++; }
  bool connected() override { return up; }
};

class FakeDht {
 public:
  int begins = 0;
  int reads = 0;
  float temperature = 24.5f;
  float humidity = 60.0f;
  void begin() { begins++; }
  float readTemperature() {
    reads++;
    return temperature;
  }
  float readHumidity() { return humidity; }
};

class FakeSht {
 public:
  bool present = true;
  bool works = true;
  uint8_t address = 0;
  bool begin(uint8_t i2c) {
    address = i2c;
    return present;
  }
  bool readBoth(float* temperature, float* humidity) {
    *temperature = 19.5f;
    *humidity = 70.0f;
    return works;
  }
};

class FakeBackend : public IBackend {
 public:
  std::vector<std::string> sent;
  std::vector<int> statuses{200};
  std::string body = "{\"status\":\"stored\"}";
  int busyPolls = 0;
  int left = 0;
  bool canStart = true;
  bool begin(const char* json) override {
    if (!canStart) return false;
    sent.push_back(json);
    left = busyPolls;
    return true;
  }
  SendState poll(BackendResponse& response) override {
    if (left-- > 0) return SendState::Busy;
    size_t at = sent.size() - 1 < statuses.size() ? sent.size() - 1 : statuses.size() - 1;
    response = BackendResponse{statuses[at], body.c_str()};
    return SendState::Done;
  }
};

static BackendResponse send(BackendProxy& proxy) {
  BackendResponse response{0, nullptr};
  while (proxy.poll(response) == SendState::Busy) {
  }
  return response;
}

static BackendResponse post(BackendProxy& proxy, const char* json) {
  proxy.begin(json);
  return send(proxy);
}

static const float NOT_A_NUMBER = std::strtof("nan", nullptr);

static void singletons_are_one_instance_and_refuse_incomplete_settings() {
  CHECK(&DeviceConfig::instance() == &DeviceConfig::instance());
  CHECK(&WifiManager::instance() == &WifiManager::instance());
  DeviceConfig::instance().load(DeviceSettings());
  CHECK(!DeviceConfig::instance().valid());
  DeviceSettings settings;
  settings.wifiSsid = "red";
  settings.deviceId = "UNO-1";
  settings.deviceKey = "k";
  settings.host = "h";
  DeviceConfig::instance().load(settings);
  CHECK(DeviceConfig::instance().valid());
  settings.soilWetRaw = settings.soilDryRaw;
  DeviceConfig::instance().load(settings);
  CHECK(!DeviceConfig::instance().valid());
}

static void wifi_retries_with_a_growing_wait_and_resets_once_connected() {
  FakeWifi wifi;
  WifiManager& manager = WifiManager::instance();
  manager.attach(wifi, "red", "clave");
  CHECK(!manager.ensureConnected(0));
  CHECK(wifi.begins == 1);
  manager.ensureConnected(4999);
  CHECK(wifi.begins == 1);
  manager.ensureConnected(5000);
  CHECK(wifi.begins == 2);
  manager.ensureConnected(14999);
  CHECK(wifi.begins == 2);
  manager.ensureConnected(15000);
  CHECK(wifi.begins == 3);
  wifi.up = true;
  CHECK(manager.ensureConnected(15001));
  wifi.up = false;
  manager.ensureConnected(15002);
  CHECK(wifi.begins == 4);
}

static void the_median_ignores_a_spike_and_needs_a_majority() {
  Scripted spike({Reading::ok(10), Reading::ok(11), Reading::ok(5000), Reading::ok(12), Reading::ok(9)});
  Reading filtered = MedianFilter(spike).read();
  CHECK(filtered.valid && filtered.value == 11.0f);

  Scripted some({Reading::bad(), Reading::ok(20), Reading::bad(), Reading::ok(30), Reading::ok(40)});
  CHECK(MedianFilter(some).read().value == 30.0f);

  Scripted most({Reading::bad(), Reading::bad(), Reading::bad(), Reading::ok(30), Reading::ok(40)});
  CHECK(!MedianFilter(most).read().valid);
}

static void calibration_maps_either_way_the_sensor_counts() {
  Scripted raw({Reading::ok(9000)});
  CHECK(Calibration(raw, 12000, 6000).read().value == 50.0f);
  Scripted rising({Reading::ok(300)});
  CHECK(Calibration(rising, 200, 400).read().value == 50.0f);
  Scripted broken({Reading::bad()});
  CHECK(!Calibration(broken, 12000, 6000).read().valid);
}

static void range_check_clamps_near_the_limit_and_refuses_far_from_it() {
  Scripted slightly({Reading::ok(-5)});
  CHECK(RangeCheck(slightly, 0, 100, 15).read().value == 0.0f);
  Scripted over({Reading::ok(108)});
  CHECK(RangeCheck(over, 0, 100, 15).read().value == 100.0f);
  Scripted far({Reading::ok(140)});
  CHECK(!RangeCheck(far, 0, 100, 15).read().valid);
}

static void the_whole_chain_turns_raw_counts_into_a_percentage() {
  FakeAnalog analog;
  CapacitiveSoilSensor sensor(analog, 0);
  SoilChain chain(sensor, 12000, 6000);
  CHECK(chain.end().read().value == 50.0f);
  analog.value = 0;
  CHECK(!chain.end().read().valid);
  analog.value = CapacitiveSoilSensor::ADC_MAX;
  CHECK(!chain.end().read().valid);
  analog.value = 16000;  // loose but not at the rail: -66 %, far outside
  CHECK(!chain.end().read().valid);
}

static void adapters_give_two_libraries_the_same_face() {
  FakeClock clock;
  FakeDht dht;
  Dht22AdapterT<FakeDht> first(dht, clock);
  IClimateSensor& climate = first;
  CHECK(climate.begin() && dht.begins == 1);
  CHECK(climate.read().temperature.value == 24.5f);
  dht.temperature = NOT_A_NUMBER;
  CHECK(climate.read().temperature.valid);  // inside the two seconds it must rest: the last reading stands
  CHECK(dht.reads == 1);
  clock.ms += 2000;
  Climate misread = climate.read();
  CHECK(!misread.temperature.valid && misread.humidity.valid);

  FakeSht sht;
  Sht31AdapterT<FakeSht> second(sht);
  IClimateSensor& other = second;
  CHECK(other.begin() && sht.address == 0x44);
  CHECK(other.read().humidity.value == 70.0f);
  sht.works = false;
  CHECK(!other.read().temperature.valid);
  sht.present = false;
  Sht31AdapterT<FakeSht> absent(sht);
  CHECK(!absent.begin() && !absent.read().humidity.valid);
}

static void the_factories_give_interchangeable_families() {
  FakeClock clock;
  SimulatedHardwareFactory simulated(clock, 12000, 6000);
  HardwareFactory& hardware = simulated;
  CHECK(hardware.soilSensor().read().value == 50.0f);
  simulated.rawSoil().set(6000);
  CHECK(hardware.soilSensor().read().value == 100.0f);
  CHECK(hardware.climateSensor().begin());
  CHECK(hardware.climateSensor().read().humidity.valid);
  CHECK(!hardware.button().pressed());
  hardware.actuator().begin();
  clock.ms += MIN_PUMP_GAP_MS;
  CHECK(hardware.actuator().start(1000));
  CHECK(simulated.simulatedDriver().starts() == 1);
}

static void the_builder_writes_the_event_of_the_contract() {
  char buffer[200];
  bool built = ReadingJsonBuilder(buffer, sizeof buffer)
                   .device("UNO-R4-01")
                   .timestamp(1793500000)
                   .soilMoisture(Reading::ok(31.47f))
                   .airTemperature(Reading::ok(-2.5f))
                   .relativeHumidity(Reading::bad())
                   .build();
  CHECK(built);
  CHECK(std::string(buffer) ==
        "{\"device_id\":\"UNO-R4-01\",\"type\":\"reading\",\"ts\":1793500000,"
        "\"data\":{\"soil_moisture\":31.5,\"air_temperature\":-2.5}}");

  ReadingJsonBuilder(buffer, sizeof buffer).device("U").soilMoisture(Reading::ok(5)).build();
  CHECK(std::strstr(buffer, "\"ts\"") == nullptr);  // without a clock the backend stamps the arrival
}

static void the_builder_refuses_what_would_not_be_a_valid_event() {
  char buffer[200];
  CHECK(!ReadingJsonBuilder(buffer, sizeof buffer).device("UNO").build());
  CHECK(buffer[0] == '\0');
  CHECK(!ReadingJsonBuilder(buffer, sizeof buffer).soilMoisture(Reading::ok(5)).build());
  CHECK(!ReadingJsonBuilder(buffer, sizeof buffer).device("a\"b").soilMoisture(Reading::ok(5)).build());
  char tiny[20];
  CHECK(!ReadingJsonBuilder(tiny, sizeof tiny).device("UNO").soilMoisture(Reading::ok(5)).build());
  CHECK(tiny[0] == '\0');
}

static void the_proxy_delivers_in_order_and_retries_with_a_growing_wait() {
  FakeClock clock;
  FakeBackend real;
  real.statuses = {500, 500, 500, 200, 200};
  BackendProxy proxy(real, clock);
  CHECK(post(proxy, "uno").status == 500);
  proxy.begin("dos");
  CHECK(proxy.pending() == 2);

  send(proxy);
  CHECK(real.sent.size() == 1);  // still waiting
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  send(proxy);
  CHECK(real.sent.size() == 2);
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  send(proxy);
  CHECK(real.sent.size() == 2);  // the wait doubled
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  send(proxy);
  CHECK(real.sent.size() == 3);
  clock.ms += BackendProxy::MAX_WAIT_MS;
  CHECK(send(proxy).status == 200);
  clock.ms += 1;
  CHECK(send(proxy).status == 200);
  CHECK(real.sent[3] == "uno" && real.sent[4] == "dos");
  CHECK(proxy.pending() == 0);
}

static void the_proxy_waits_steadily_while_render_wakes_up_then_backs_off() {
  FakeClock clock;
  FakeBackend real;
  real.statuses = {0};
  BackendProxy proxy(real, clock);
  post(proxy, "uno");
  CHECK(proxy.waking());
  for (int i = 0; i < 3; i++) {
    clock.ms += BackendProxy::WAKE_RETRY_MS;
    send(proxy);
  }
  CHECK(real.sent.size() == 4);
  clock.ms += BackendProxy::WAKE_WINDOW_MS;
  send(proxy);
  CHECK(!proxy.waking());  // it did not wake: this is an outage
  size_t before = real.sent.size();
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  send(proxy);
  CHECK(real.sent.size() == before + 1);
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  send(proxy);
  CHECK(real.sent.size() == before + 1);  // now the wait doubles
  real.statuses = {200};
  clock.ms += BackendProxy::MAX_WAIT_MS;
  send(proxy);
  CHECK(proxy.pending() == 0 && !proxy.waking());
}

static void the_proxy_drops_a_refused_event_and_the_oldest_when_full() {
  FakeClock clock;
  FakeBackend real;
  real.statuses = {400};
  BackendProxy refused(real, clock);
  post(refused, "malo");
  CHECK(refused.pending() == 0 && refused.dropped() == 1);

  real.sent.clear();
  real.statuses = {429};
  BackendProxy busy(real, clock);
  post(busy, "x");
  CHECK(busy.pending() == 1);  // too many requests is no fault of the event

  BackendProxy full(real, clock);
  char event[8];
  for (int i = 0; i < BackendProxy::CAPACITY + 2; i++) {
    std::snprintf(event, sizeof event, "e%d", i);
    full.begin(event);
  }
  CHECK(full.pending() == BackendProxy::CAPACITY && full.lost() == 2);
  real.sent.clear();
  real.statuses = {200};
  send(full);
  CHECK(real.sent[0] == "e2");
  std::string oversized(BackendProxy::EVENT_BYTES, 'x');
  CHECK(!full.begin(oversized.c_str()));
}

static void every_order_works_with_every_driver() {
  FakeClock clock;
  FakeDigital pins;
  RelayDriver relay(pins, 7, true, clock);
  SimulatedDriver simulated(clock);
  CHECK(pins.writes.empty());  // nothing touches a pin from a constructor, before the core is up
  relay.begin();
  CHECK(pins.writes.size() == 1 && pins.writes[0] == true);  // active low: high is off

  CHECK(ManualIrrigationOrder(relay, 10).execute());
  CHECK(pins.writes.back() == false && relay.running());
  relay.stop();
  CHECK(pins.writes.back() == true);
  CHECK(AiIrrigationOrder(relay, 5, 0.9f, Reading::ok(30)).execute());
  CHECK(relay.running());
  CHECK(ManualIrrigationOrder(simulated, 10).execute());
  CHECK(AiIrrigationOrder(simulated, 5, 0.9f, Reading::ok(30)).execute());
  CHECK(simulated.starts() == 2);
}

static void the_pump_stops_by_itself() {
  FakeClock clock;
  SimulatedDriver driver(clock);
  ManualIrrigationOrder(driver, 2).execute();
  clock.ms += 119999;
  driver.update();
  CHECK(driver.running());
  clock.ms += 1;
  driver.update();
  CHECK(!driver.running());
}

static void the_device_has_the_last_word_on_an_ai_order() {
  FakeClock clock;
  SimulatedDriver driver(clock);
  CHECK(!AiIrrigationOrder(driver, 5, 0.69f, Reading::ok(30)).execute());
  CHECK(!AiIrrigationOrder(driver, 5, 0.9f, Reading::bad()).execute());
  CHECK(!AiIrrigationOrder(driver, 5, 0.9f, Reading::ok(85)).execute());
  CHECK(!AiIrrigationOrder(driver, 0, 0.9f, Reading::ok(30)).execute());
  CHECK(!AiIrrigationOrder(driver, IrrigationOrder::MAX_MINUTES + 1, 0.9f, Reading::ok(30)).execute());
  CHECK(!ManualIrrigationOrder(driver, IrrigationOrder::MAX_MINUTES + 1).execute());
  CHECK(driver.starts() == 0);
}

static void a_command_is_read_from_the_answer() {
  IrrigationCommand ai = parseCommand(
      "{\"status\":\"stored\",\"readings\":2,\"command\":{\"id\":5,\"type\":\"irrigate\",\"source\":\"AI\",\"minutes\":7,"
      "\"confidence\":0.85}}");
  CHECK(ai.present && ai.fromAi && ai.minutes == 7 && ai.confidence > 0.84f && ai.confidence < 0.86f);
  IrrigationCommand manual =
      parseCommand("{\"command\":{\"id\":6,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":12}}");
  CHECK(manual.present && !manual.fromAi && manual.minutes == 12);
  CHECK(!parseCommand("{\"status\":\"stored\",\"delivered\":1}").present);
  CHECK(!parseCommand(nullptr).present);
  CHECK(!parseCommand("{\"command\":{\"type\":\"irrigate\",\"minutes\":-3}}").present);
}

struct Bench {
  FakeClock clock;
  FakeBackend real;
  FakeWifi wifi;
  BackendProxy proxy{real, clock};
  SimulatedHardwareFactory hardware{clock, 12000, 6000};
  AgroSenseDevice device{hardware, proxy, clock};

  Bench() {
    DeviceSettings settings;
    settings.wifiSsid = "red";
    settings.deviceId = "UNO-1";
    settings.deviceKey = "k";
    settings.host = "h";
    DeviceConfig::instance().load(settings);
    WifiManager::instance().attach(wifi, "red", "k");
    wifi.up = true;
  }

  void start() {
    device.begin();
    clock.ms += MIN_PUMP_GAP_MS;  // the gap counts from the boot
  }

  void ticks(int count = 4) {
    for (int i = 0; i < count; i++) device.tick();
  }
};

static const char* AI_ORDER =
    "{\"command\":{\"id\":12,\"type\":\"irrigate\",\"source\":\"AI\",\"minutes\":1,\"confidence\":0.9}}";
static const char* MANUAL_ORDER = "{\"command\":{\"id\":13,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}";

static void the_facade_measures_each_interval_and_sends() {
  Bench bench;
  CHECK(bench.device.begin());
  bench.ticks();
  CHECK(bench.real.sent.size() == 1);
  CHECK(bench.real.sent[0].find("\"soil_moisture\":50.0") != std::string::npos);
  CHECK(bench.real.sent[0].find("\"air_temperature\":24.1") != std::string::npos);
  bench.clock.ms += 29999;
  bench.ticks();
  CHECK(bench.real.sent.size() == 1);
  bench.clock.ms += 1;
  bench.ticks();
  CHECK(bench.real.sent.size() == 2);
}

static void without_wifi_it_keeps_measuring_and_catches_up() {
  Bench bench;
  bench.wifi.up = false;
  bench.device.begin();
  bench.ticks();
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.real.sent.empty() && bench.proxy.pending() == 2);
  bench.wifi.up = true;
  bench.ticks(6);
  CHECK(bench.real.sent.size() == 2 && bench.proxy.pending() == 0);
}

static void the_order_in_an_answer_reaches_the_pump_through_the_facade() {
  Bench bench;
  bench.start();
  bench.real.body = AI_ORDER;
  bench.ticks();
  CHECK(bench.hardware.actuator().running());
  bench.real.body = "{\"status\":\"stored\"}";
  bench.clock.ms += 60000;
  bench.ticks();
  CHECK(!bench.hardware.actuator().running());

  bench.hardware.rawSoil().set(6000);  // soaked: 100 %
  bench.clock.ms += MIN_PUMP_GAP_MS;
  bench.real.body = AI_ORDER;
  bench.ticks();
  CHECK(!bench.hardware.actuator().running());
  bench.real.body = MANUAL_ORDER;
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.hardware.actuator().running());  // a person may overrule the soil
}

static void the_result_of_an_order_travels_in_the_next_event() {
  Bench bench;
  bench.start();
  bench.real.body = AI_ORDER;
  bench.ticks();
  bench.real.body = "{\"status\":\"stored\"}";
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.real.sent.back().find("\"ack\":{\"command_id\":12,\"result\":\"started\"}") != std::string::npos);
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.real.sent.back().find("\"result\":\"finished\",\"ran_seconds\":60}") != std::string::npos);
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.real.sent.back().find("\"ack\"") == std::string::npos);  // reported once

  bench.hardware.rawSoil().set(6000);
  bench.clock.ms += MIN_PUMP_GAP_MS;
  bench.real.body = AI_ORDER;
  bench.ticks();
  bench.real.body = "{\"status\":\"stored\"}";
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.real.sent.back().find("\"result\":\"refused\"") != std::string::npos);
}

static void an_order_above_the_maximum_reports_what_really_ran() {
  Bench bench;
  bench.start();
  bench.real.body = "{\"command\":{\"id\":21,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":20}}";
  bench.ticks();
  CHECK(bench.hardware.actuator().running());
  bench.real.body = "{\"status\":\"stored\"}";
  bench.clock.ms += MAX_PUMP_ON_MS;
  bench.ticks();
  CHECK(!bench.hardware.actuator().running());
  bench.clock.ms += 30000;
  bench.ticks();
  bool reported = false;
  for (const std::string& event : bench.real.sent) {
    if (event.find("\"command_id\":21,\"result\":\"finished\",\"ran_seconds\":300}") != std::string::npos) reported = true;
  }
  CHECK(reported);
}

static void stopping_an_order_by_hand_reports_the_shorter_run() {
  Bench bench;
  bench.start();
  bench.real.body = MANUAL_ORDER;
  bench.ticks();
  bench.real.body = "{\"status\":\"stored\"}";
  bench.clock.ms += 20000;
  bench.hardware.simulatedButton().press();
  bench.device.tick();
  CHECK(!bench.hardware.actuator().running());
  bench.clock.ms += 30000;
  bench.ticks();
  bool reported = false;
  for (const std::string& event : bench.real.sent) {
    if (event.find("\"ran_seconds\":20}") != std::string::npos) reported = true;
  }
  CHECK(reported);
}

static void the_button_starts_and_stops_the_pump() {
  Bench bench;
  bench.start();
  bench.hardware.simulatedButton().press();
  bench.device.tick();
  CHECK(bench.hardware.actuator().running());
  bench.hardware.simulatedButton().press();
  bench.device.tick();
  CHECK(!bench.hardware.actuator().running());
}

static void the_pump_is_off_after_begin_and_cannot_start_right_after_a_reset() {
  Bench bench;
  bench.device.begin();
  CHECK(!bench.hardware.actuator().running());
  CHECK(!bench.hardware.actuator().start(60000));
  CHECK(bench.hardware.simulatedDriver().starts() == 0);
  bench.clock.ms += MIN_PUMP_GAP_MS;
  CHECK(bench.hardware.actuator().start(60000));
}

static void no_order_runs_the_pump_past_the_absolute_maximum() {
  FakeClock clock;
  SimulatedDriver driver(clock);
  CHECK(ManualIrrigationOrder(driver, IrrigationOrder::MAX_MINUTES).execute());
  clock.ms += MAX_PUMP_ON_MS - 1;
  driver.update();
  CHECK(driver.running());
  clock.ms += 1;
  driver.update();
  CHECK(!driver.running());
}

static void the_interrupt_stops_the_pump_while_the_loop_is_blocked() {
  FakeClock clock;
  FakeDigital pins;
  RelayDriver relay(pins, 7, true, clock);
  PumpGuard guard(relay, clock);
  guard.begin();
  clock.ms += MIN_PUMP_GAP_MS;
  guard.start(60000);
  clock.ms += 60000;
  guard.enforceDeadline();  // what the timer does: loop() never ran
  CHECK(!guard.running());
  CHECK(pins.writes.back() == true);
  guard.update();
  CHECK(guard.usedTodayMs() == 60000);
}

static void a_dead_connection_does_not_keep_the_pump_on() {
  Bench bench;
  bench.start();
  bench.real.body = AI_ORDER;
  bench.ticks();
  CHECK(bench.hardware.actuator().running());
  bench.real.busyPolls = 1000000;  // the server never answers again
  bench.wifi.up = false;
  bench.clock.ms += 30000;
  bench.ticks();
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(!bench.hardware.actuator().running());
}

static void the_loop_keeps_running_while_an_answer_is_pending() {
  Bench bench;
  bench.start();
  bench.real.busyPolls = 1000000;
  bench.ticks();
  CHECK(bench.proxy.busy());
  bench.hardware.simulatedButton().press();
  bench.device.tick();
  CHECK(bench.hardware.actuator().running());  // the button still answers, mid send
}

static void the_gap_between_runs_is_respected() {
  FakeClock clock;
  SimulatedDriver driver(clock);
  PumpGuard guard(driver, clock);
  guard.begin();
  clock.ms += MIN_PUMP_GAP_MS;
  CHECK(guard.start(1000));
  CHECK(!guard.start(1000));  // already running
  clock.ms += 1000;
  guard.update();
  clock.ms += MIN_PUMP_GAP_MS - 1;
  CHECK(!guard.start(1000));
  clock.ms += 1;
  CHECK(guard.start(1000));
}

static void the_daily_limit_cuts_and_then_refuses_until_the_next_day() {
  FakeClock clock;
  SimulatedDriver driver(clock);
  PumpGuard guard(driver, clock);
  guard.begin();
  for (uint32_t used = 0; used < MAX_PUMP_MS_PER_DAY; used += MAX_PUMP_ON_MS) {
    clock.ms += MIN_PUMP_GAP_MS;
    CHECK(guard.start(MAX_PUMP_ON_MS));
    clock.ms += MAX_PUMP_ON_MS;
    guard.update();
    CHECK(!guard.running());
  }
  CHECK(guard.usedTodayMs() == MAX_PUMP_MS_PER_DAY);
  clock.ms += MIN_PUMP_GAP_MS;
  CHECK(!guard.start(1000));
  clock.ms += DAY_MS;
  CHECK(guard.start(1000));

  FakeClock second;
  SimulatedDriver other(second);
  PumpGuard limited(other, second);
  limited.begin();
  for (int i = 0; i < 5; i++) {
    second.ms += MIN_PUMP_GAP_MS;
    limited.start(MAX_PUMP_ON_MS);
    second.ms += MAX_PUMP_ON_MS;
    limited.update();
  }
  second.ms += MIN_PUMP_GAP_MS;
  limited.start(MAX_PUMP_ON_MS);
  second.ms += 60000;
  limited.stop();
  second.ms += MIN_PUMP_GAP_MS;
  CHECK(limited.start(MAX_PUMP_ON_MS));  // only four minutes are left today
  second.ms += 4UL * 60000UL;
  limited.update();
  CHECK(!limited.running());
}

static void the_answer_is_read_byte_by_byte() {
  HttpResponseReader reader;
  reader.reset();
  const char* answer = "HTTP/1.1 200 OK\r\nDate: x\r\ncontent-LENGTH: 7\r\n\r\n{\"a\":1}";
  for (const char* c = answer; *c != '\0'; c++) {
    CHECK(!reader.complete());
    reader.feed(*c);
  }
  CHECK(reader.complete());
  CHECK(reader.status() == 200);
  CHECK(std::string(reader.body()) == "{\"a\":1}");

  reader.reset();
  for (const char* c = "HTTP/1.1 502 Bad Gateway\r\n\r\nno"; *c != '\0'; c++) reader.feed(*c);
  CHECK(reader.status() == 502 && !reader.complete());  // without a length, the end is the closed connection

  reader.reset();
  CHECK(reader.status() == 0);
  std::string big = "HTTP/1.1 200 OK\r\n\r\n" + std::string(HttpResponseReader::BODY_BYTES * 2, 'x');
  for (char c : big) reader.feed(c);
  CHECK(std::strlen(reader.body()) == HttpResponseReader::BODY_BYTES - 1);
}

static void a_send_that_cannot_start_waits_and_an_event_lost_in_flight_is_not_popped_twice() {
  FakeClock clock;
  FakeBackend real;
  real.canStart = false;
  BackendProxy proxy(real, clock);
  post(proxy, "uno");
  CHECK(proxy.pending() == 1 && proxy.waking());

  FakeBackend slow;
  slow.busyPolls = 1;
  BackendProxy full(slow, clock);
  char event[8];
  for (int i = 0; i < BackendProxy::CAPACITY; i++) {
    std::snprintf(event, sizeof event, "e%d", i);
    full.begin(event);
  }
  BackendResponse response{0, nullptr};
  full.poll(response);  // e0 leaves
  CHECK(full.busy());
  full.begin("nuevo");  // and the buffer drops it while it travels
  CHECK(full.lost() == 1);
  send(full);
  CHECK(full.pending() == BackendProxy::CAPACITY);
  send(full);
  CHECK(slow.sent[1] == "e1");
}

static void the_watchdog_is_fed_while_the_loop_lives_even_inside_a_long_connect() {
  LoopSupervisor supervisor;
  supervisor.beat(1000);
  CHECK(supervisor.mayFeed(1000));
  CHECK(supervisor.mayFeed(1000 + 8000));   // connect()
  CHECK(supervisor.mayFeed(1000 + 45000));  // far beyond it
  CHECK(!supervisor.mayFeed(1000 + LoopSupervisor::MAX_SILENCE_MS));  // hung: the board resets
  supervisor.beat(70000);
  CHECK(supervisor.mayFeed(70001));
  supervisor.beat(0xFFFFFF00UL);
  CHECK(supervisor.mayFeed(0x00000100UL));  // across the wrap of millis()
}

// The timer fires in the instant between "running" being set and the relay being written. Only a very short run can
// expire there, and it is what the last milliseconds of the day's allowance give.
class InterruptedPort : public DigitalPort {
 public:
  ActuatorDriver* driver = nullptr;
  FakeClock* clock = nullptr;
  bool level = true;
  void write(uint8_t, bool high) override {
    if (!high && driver != nullptr) {  // low is on: the interrupt lands just before the write takes effect
      ActuatorDriver* interrupting = driver;
      driver = nullptr;
      clock->ms += 10;
      interrupting->enforceDeadline();
    }
    level = high;
  }
};

static void the_timer_firing_during_a_start_cannot_leave_the_relay_closed_and_unwatched() {
  FakeClock clock;
  InterruptedPort pin;
  RelayDriver relay(pin, 7, true, clock);
  relay.begin();
  pin.clock = &clock;
  pin.driver = &relay;
  relay.start(1);
  relay.enforceDeadline();  // the next tick of the timer
  CHECK(!relay.running());
  CHECK(pin.level == true);  // high is off: never closed with nobody watching
}

static void what_is_not_a_whole_order_starts_nothing() {
  // Cut in the middle of the number: "minutes":12 would read as 1, and a shorter run is still an unasked one.
  CHECK(!parseCommand("{\"command\":{\"id\":6,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1").present);
  // No id: it could be neither confirmed nor told from a repetition.
  CHECK(!parseCommand("{\"command\":{\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}").present);
  CHECK(!parseCommand("{\"command\":{\"id\":0,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}").present);
  CHECK(!parseCommand("{\"command\":{\"id\":-4,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}").present);
  // An unknown origin must not fall into the manual order, the one that skips the soil check.
  CHECK(!parseCommand("{\"command\":{\"id\":6,\"type\":\"irrigate\",\"minutes\":1}}").present);
  CHECK(!parseCommand("{\"command\":{\"id\":6,\"type\":\"irrigate\",\"source\":\"Ai\",\"minutes\":1}}").present);
  CHECK(!parseCommand("{\"command\":null}").present);
  CHECK(!parseCommand("<html>irrigate \"command\" \"minutes\":5</html>").present);
}

static void only_a_stored_event_may_carry_an_order() {
  for (int status : {201, 302, 400, 401, 404, 429, 500, 503}) {
    Bench bench;
    bench.start();
    bench.real.statuses = {status};
    bench.real.body = MANUAL_ORDER;
    bench.ticks();
    CHECK(bench.hardware.actuator().running() == (status == 201));
  }
}

static void feed(HttpResponseReader& reader, const char* text) {
  for (const char* c = text; *c != '\0'; c++) reader.feed(*c);
}

static void an_answer_cut_short_counts_as_no_answer() {
  HttpResponseReader reader;
  reader.reset();
  feed(reader, "HTTP/1.1 200 OK\r\nContent-Length: 90\r\n\r\n{\"command\":{\"id\":6");
  CHECK(reader.finalStatus(true) == 0);   // closed before the promised length
  CHECK(reader.finalStatus(false) == 0);  // or still waiting at the deadline

  reader.reset();
  feed(reader, "HTTP/1.1 200 OK\r\nContent-Le");
  CHECK(reader.finalStatus(true) == 0);  // the headers never ended

  reader.reset();
  feed(reader, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
  CHECK(reader.finalStatus(false) == 200);

  reader.reset();
  feed(reader, "HTTP/1.1 502 Bad Gateway\r\n\r\nno");
  CHECK(reader.finalStatus(true) == 502);  // without a length, the closed connection is the end
  CHECK(reader.finalStatus(false) == 0);   // and while it stays open nothing says the answer ended

  reader.reset();
  CHECK(reader.finalStatus(true) == 0);
  feed(reader, "garbage-without-a-space\r\n\r\n");
  CHECK(reader.finalStatus(true) == 0);
}

static int countOf(const Bench& bench, const std::string& text) {
  int found = 0;
  for (const std::string& event : bench.real.sent) {
    if (event.find(text) != std::string::npos) found++;
  }
  return found;
}

static const char* ORDER_21 = "{\"command\":{\"id\":21,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}";
static const char* ORDER_22 = "{\"command\":{\"id\":22,\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}";

static void an_order_refused_while_another_runs_does_not_lose_the_one_running() {
  Bench bench;
  bench.start();
  bench.real.body = ORDER_21;
  bench.ticks();
  CHECK(bench.hardware.actuator().running());
  bench.real.body = ORDER_22;  // answered to the next event, with the pump still on
  bench.clock.ms += 30000;
  bench.ticks();
  CHECK(bench.hardware.actuator().running());
  bench.real.body = "{\"status\":\"stored\"}";
  for (int i = 0; i < 5; i++) {
    bench.clock.ms += 30000;
    bench.ticks();
  }
  CHECK(countOf(bench, "\"command_id\":22,\"result\":\"refused\"") == 1);
  CHECK(countOf(bench, "\"command_id\":21,\"result\":\"finished\",\"ran_seconds\":60") == 1);
}

static void no_result_is_overwritten_by_the_next_one() {
  Bench bench;
  bench.start();
  bench.real.body = ORDER_21;
  bench.ticks();
  bench.real.body = ORDER_22;  // both results wait for the same event when the next one arrives
  bench.clock.ms += 30000;
  bench.ticks();
  bench.real.body = "{\"status\":\"stored\"}";
  for (int i = 0; i < 5; i++) {
    bench.clock.ms += 30000;
    bench.ticks();
  }
  CHECK(countOf(bench, "\"command_id\":21,\"result\":\"started\"") == 1);
  CHECK(countOf(bench, "\"command_id\":22,\"result\":\"refused\"") == 1);
}

static void an_order_that_arrives_twice_runs_once_and_reports_once() {
  Bench bench;
  bench.start();
  bench.real.body = ORDER_21;
  bench.ticks();
  CHECK(bench.hardware.simulatedDriver().starts() == 1);
  bench.clock.ms += 30000;
  bench.ticks();  // the same order again, say a retry of the answer: no refusal may reach the backend
  bench.clock.ms += 90000;
  bench.ticks();
  bench.clock.ms += MIN_PUMP_GAP_MS;
  bench.ticks();  // and past the gap it must not run a second time
  CHECK(bench.hardware.simulatedDriver().starts() == 1);
  bench.real.body = "{\"status\":\"stored\"}";
  for (int i = 0; i < 4; i++) {
    bench.clock.ms += 30000;
    bench.ticks();
  }
  CHECK(countOf(bench, "\"command_id\":21,\"result\":\"refused\"") == 0);
  CHECK(countOf(bench, "\"command_id\":21,\"result\":\"finished\",\"ran_seconds\":60") == 1);
}

int main() {
  an_order_refused_while_another_runs_does_not_lose_the_one_running();
  no_result_is_overwritten_by_the_next_one();
  an_order_that_arrives_twice_runs_once_and_reports_once();
  what_is_not_a_whole_order_starts_nothing();
  only_a_stored_event_may_carry_an_order();
  an_answer_cut_short_counts_as_no_answer();
  the_timer_firing_during_a_start_cannot_leave_the_relay_closed_and_unwatched();
  singletons_are_one_instance_and_refuse_incomplete_settings();
  wifi_retries_with_a_growing_wait_and_resets_once_connected();
  the_median_ignores_a_spike_and_needs_a_majority();
  calibration_maps_either_way_the_sensor_counts();
  range_check_clamps_near_the_limit_and_refuses_far_from_it();
  the_whole_chain_turns_raw_counts_into_a_percentage();
  adapters_give_two_libraries_the_same_face();
  the_factories_give_interchangeable_families();
  the_builder_writes_the_event_of_the_contract();
  the_builder_refuses_what_would_not_be_a_valid_event();
  the_proxy_delivers_in_order_and_retries_with_a_growing_wait();
  the_proxy_waits_steadily_while_render_wakes_up_then_backs_off();
  the_proxy_drops_a_refused_event_and_the_oldest_when_full();
  every_order_works_with_every_driver();
  the_pump_stops_by_itself();
  the_device_has_the_last_word_on_an_ai_order();
  a_command_is_read_from_the_answer();
  the_facade_measures_each_interval_and_sends();
  without_wifi_it_keeps_measuring_and_catches_up();
  the_order_in_an_answer_reaches_the_pump_through_the_facade();
  the_button_starts_and_stops_the_pump();
  the_watchdog_is_fed_while_the_loop_lives_even_inside_a_long_connect();
  an_order_above_the_maximum_reports_what_really_ran();
  stopping_an_order_by_hand_reports_the_shorter_run();
  the_result_of_an_order_travels_in_the_next_event();
  the_pump_is_off_after_begin_and_cannot_start_right_after_a_reset();
  no_order_runs_the_pump_past_the_absolute_maximum();
  the_interrupt_stops_the_pump_while_the_loop_is_blocked();
  a_dead_connection_does_not_keep_the_pump_on();
  the_loop_keeps_running_while_an_answer_is_pending();
  the_gap_between_runs_is_respected();
  the_daily_limit_cuts_and_then_refuses_until_the_next_day();
  the_answer_is_read_byte_by_byte();
  a_send_that_cannot_start_waits_and_an_event_lost_in_flight_is_not_popped_twice();
  printf("%d checks, %d failed\n", checks, failures);
  return failures == 0 ? 0 : 1;
}
