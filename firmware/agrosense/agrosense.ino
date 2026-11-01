// AgroSense field device for the Arduino UNO R4 WiFi. See firmware/README.md for the wiring and the libraries.
#include "arduino_secrets.h"
#include "src/ArduinoPorts.h"

// Uncomment to run on a bench with nothing wired: Wi-Fi and the backend stay real, the sensors and the pump do not.
// #define AGRO_SIMULATED_HARDWARE
// Uncomment if the air sensor is an SHT31 on the Qwiic connector instead of a DHT22 on D4.
// #define AGRO_USE_SHT31

using namespace agro;

constexpr uint32_t STATUS_EVERY_MS = 60000;

BoardClock boardClock;
BoardWifi boardWifi;
HttpBackend httpBackend(boardClock);
BackendProxy backend(httpBackend, boardClock);

DeviceSettings settings() {
  DeviceSettings values;
  values.wifiSsid = SECRET_WIFI_SSID;
  values.wifiPassword = SECRET_WIFI_PASS;
  values.deviceId = SECRET_DEVICE_ID;
  values.deviceKey = SECRET_DEVICE_KEY;
  values.host = SECRET_EVENTS_HOST;
  values.port = SECRET_EVENTS_PORT;
  values.tls = SECRET_EVENTS_TLS;
  return values;
}

#if defined(AGRO_SIMULATED_HARDWARE)
SimulatedHardwareFactory hardware(boardClock, DeviceSettings().soilDryRaw, DeviceSettings().soilWetRaw);
#else
#if defined(AGRO_USE_SHT31)
Adafruit_SHT31 airChip(&Wire1);
Sht31Adapter airSensor(airChip);
#else
DHT airChip(PIN_DHT, DHT22);
Dht22Adapter airSensor(airChip, boardClock);
#endif
RealHardwareFactory hardware(airSensor, boardClock, DeviceSettings().soilDryRaw, DeviceSettings().soilWetRaw);
#endif

AgroSenseDevice device(hardware, backend, boardClock);
uint32_t nextStatusAt = 0;

void setup() {
  // The level first and the direction after: the other way round an active-low relay closes for an instant.
  digitalWrite(PIN_RELAY, RELAY_ACTIVE_LOW ? HIGH : LOW);
  pinMode(PIN_RELAY, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  Serial.begin(115200);
  analogReadResolution(14);
  Wire1.begin();

  DeviceConfig::instance().load(settings());
  const DeviceSettings& config = DeviceConfig::instance().get();
  WifiManager::instance().attach(boardWifi, config.wifiSsid, config.wifiPassword);
  if (!device.begin()) Serial.println("AgroSense: revisa arduino_secrets.h y el sensor de aire");
  if (!startPumpWatch(hardware.actuator())) Serial.println("AgroSense: sin temporizador de la bomba");
}

void loop() {
  device.tick();
  if (reached(millis(), nextStatusAt)) {
    nextStatusAt = millis() + STATUS_EVERY_MS;
    Serial.print("en cola=");
    Serial.print(backend.pending());
    Serial.print(" perdidos=");
    Serial.print(backend.lost());
    Serial.print(" rechazados=");
    Serial.println(backend.dropped());
  }
}
