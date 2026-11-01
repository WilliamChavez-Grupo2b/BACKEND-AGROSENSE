// Runs on a PC: g++ -std=c++17 -I../agrosense/src test_patterns.cpp. See run-tests.sh.
#include <cstdio>
#include <cstring>
#include <string>
#include <cstdlib>
#include <vector>

#include "AgroSenseDevice.h"

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
  BackendResponse post(const char* json) override {
    sent.push_back(json);
    int status = statuses.size() > 1 ? statuses[sent.size() - 1 < statuses.size() ? sent.size() - 1 : statuses.size() - 1]
                                     : statuses[0];
    return BackendResponse{status, body.c_str()};
  }
};

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
  hardware.actuator().start(1000);
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
  CHECK(proxy.post("uno").status == 500);
  proxy.enqueue("dos");
  CHECK(proxy.pending() == 2);

  proxy.flush();
  CHECK(real.sent.size() == 1);  // still waiting
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  proxy.flush();
  CHECK(real.sent.size() == 2);
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  proxy.flush();
  CHECK(real.sent.size() == 2);  // the wait doubled
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  proxy.flush();
  CHECK(real.sent.size() == 3);
  clock.ms += BackendProxy::MAX_WAIT_MS;
  CHECK(proxy.flush().status == 200);
  clock.ms += 1;
  CHECK(proxy.flush().status == 200);
  CHECK(real.sent[3] == "uno" && real.sent[4] == "dos");
  CHECK(proxy.pending() == 0);
}

static void the_proxy_waits_steadily_while_render_wakes_up_then_backs_off() {
  FakeClock clock;
  FakeBackend real;
  real.statuses = {0};
  BackendProxy proxy(real, clock);
  proxy.post("uno");
  CHECK(proxy.waking());
  for (int i = 0; i < 3; i++) {
    clock.ms += BackendProxy::WAKE_RETRY_MS;
    proxy.flush();
  }
  CHECK(real.sent.size() == 4);
  clock.ms += BackendProxy::WAKE_WINDOW_MS;
  proxy.flush();
  CHECK(!proxy.waking());  // it did not wake: this is an outage
  size_t before = real.sent.size();
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  proxy.flush();
  CHECK(real.sent.size() == before + 1);
  clock.ms += BackendProxy::FIRST_WAIT_MS;
  proxy.flush();
  CHECK(real.sent.size() == before + 1);  // now the wait doubles
  real.statuses = {200};
  clock.ms += BackendProxy::MAX_WAIT_MS;
  proxy.flush();
  CHECK(proxy.pending() == 0 && !proxy.waking());
}

static void the_proxy_drops_a_refused_event_and_the_oldest_when_full() {
  FakeClock clock;
  FakeBackend real;
  real.statuses = {400};
  BackendProxy refused(real, clock);
  refused.post("malo");
  CHECK(refused.pending() == 0 && refused.dropped() == 1);

  real.sent.clear();
  real.statuses = {429};
  BackendProxy busy(real, clock);
  busy.post("x");
  CHECK(busy.pending() == 1);  // too many requests is no fault of the event

  BackendProxy full(real, clock);
  char event[8];
  for (int i = 0; i < BackendProxy::CAPACITY + 2; i++) {
    std::snprintf(event, sizeof event, "e%d", i);
    full.enqueue(event);
  }
  CHECK(full.pending() == BackendProxy::CAPACITY && full.lost() == 2);
  real.sent.clear();
  real.statuses = {200};
  full.flush();
  CHECK(real.sent[0] == "e2");
  std::string oversized(BackendProxy::EVENT_BYTES, 'x');
  CHECK(!full.enqueue(oversized.c_str()));
}

static void every_order_works_with_every_driver() {
  FakeClock clock;
  FakeDigital pins;
  RelayDriver relay(pins, 7, true, clock);
  SimulatedDriver simulated(clock);
  CHECK(pins.writes.size() == 1 && pins.writes[0] == true);  // active low: high is off, from the start

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
      "{\"status\":\"stored\",\"readings\":2,\"command\":{\"type\":\"irrigate\",\"source\":\"AI\",\"minutes\":7,"
      "\"confidence\":0.85}}");
  CHECK(ai.present && ai.fromAi && ai.minutes == 7 && ai.confidence > 0.84f && ai.confidence < 0.86f);
  IrrigationCommand manual =
      parseCommand("{\"command\":{\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":12}}");
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
};

static void the_facade_measures_each_interval_and_sends() {
  Bench bench;
  CHECK(bench.device.begin());
  bench.device.tick();
  CHECK(bench.real.sent.size() == 1);
  CHECK(bench.real.sent[0].find("\"soil_moisture\":50.0") != std::string::npos);
  CHECK(bench.real.sent[0].find("\"air_temperature\":24.1") != std::string::npos);
  bench.clock.ms += 29999;
  bench.device.tick();
  CHECK(bench.real.sent.size() == 1);
  bench.clock.ms += 1;
  bench.device.tick();
  CHECK(bench.real.sent.size() == 2);
}

static void without_wifi_it_keeps_measuring_and_catches_up() {
  Bench bench;
  bench.wifi.up = false;
  bench.device.begin();
  bench.device.tick();
  bench.clock.ms += 30000;
  bench.device.tick();
  CHECK(bench.real.sent.empty() && bench.proxy.pending() == 2);
  bench.wifi.up = true;
  bench.device.tick();
  bench.device.tick();
  CHECK(bench.real.sent.size() == 2 && bench.proxy.pending() == 0);
}

static void the_order_in_an_answer_reaches_the_pump_through_the_facade() {
  Bench bench;
  bench.device.begin();
  bench.real.body = "{\"command\":{\"type\":\"irrigate\",\"source\":\"AI\",\"minutes\":1,\"confidence\":0.9}}";
  bench.device.tick();
  CHECK(bench.hardware.actuator().running());
  bench.real.body = "{\"status\":\"stored\"}";
  bench.clock.ms += 60000;
  bench.device.tick();
  CHECK(!bench.hardware.actuator().running());

  bench.hardware.rawSoil().set(6000);  // soaked: 100 %
  bench.clock.ms += 30000;
  bench.real.body = "{\"command\":{\"type\":\"irrigate\",\"source\":\"AI\",\"minutes\":1,\"confidence\":0.9}}";
  bench.device.tick();
  CHECK(!bench.hardware.actuator().running());
  bench.real.body = "{\"command\":{\"type\":\"irrigate\",\"source\":\"MANUAL\",\"minutes\":1}}";
  bench.clock.ms += 30000;
  bench.device.tick();
  CHECK(bench.hardware.actuator().running());  // a person may overrule the soil
}

static void the_button_starts_and_stops_the_pump() {
  Bench bench;
  bench.device.begin();
  bench.hardware.simulatedButton().press();
  bench.device.tick();
  CHECK(bench.hardware.actuator().running());
  bench.hardware.simulatedButton().press();
  bench.device.tick();
  CHECK(!bench.hardware.actuator().running());
}

int main() {
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
  printf("%d checks, %d failed\n", checks, failures);
  return failures == 0 ? 0 : 1;
}
