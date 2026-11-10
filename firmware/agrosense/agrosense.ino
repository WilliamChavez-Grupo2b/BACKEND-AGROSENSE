// AgroSense field device for an ESP32 Dev Module. See firmware/README.md for the wiring, the libraries and the soil
// calibration.
#include "arduino_secrets.h"
#include "src/ArduinoPorts.h"

// Uncomment to run on a bench with nothing wired: Wi-Fi and the backend stay real, the sensors and the pump do not.
// #define AGRO_SIMULATED_HARDWARE
// Uncomment if the air sensor is an SHT31 on I2C (SDA GPIO21, SCL GPIO22) instead of a DHT22 on GPIO4.
// #define AGRO_USE_SHT31

// An older arduino_secrets.h may lack these: no root certificate, and a soil that is not calibrated.
#ifndef SECRET_EVENTS_CA_CERT
#define SECRET_EVENTS_CA_CERT ""
#endif
#ifndef AGRO_SOIL_DRY_RAW
#define AGRO_SOIL_DRY_RAW 0
#endif
#ifndef AGRO_SOIL_WET_RAW
#define AGRO_SOIL_WET_RAW 0
#endif

using namespace agro;

constexpr uint32_t STATUS_EVERY_MS = 60000;
// Only for AGRO_SIMULATED_HARDWARE: counts made up so the bench has a soil to read. They calibrate nothing.
constexpr int BENCH_DRY_RAW = 3000;
constexpr int BENCH_WET_RAW = 1500;

DeviceSettings settings() {
  DeviceSettings values;
  values.wifiSsid = SECRET_WIFI_SSID;
  values.wifiPassword = SECRET_WIFI_PASS;
  values.deviceId = SECRET_DEVICE_ID;
  values.deviceKey = SECRET_DEVICE_KEY;
  values.host = SECRET_EVENTS_HOST;
  values.port = SECRET_EVENTS_PORT;
  values.tls = SECRET_EVENTS_TLS;
  values.caCert = SECRET_EVENTS_CA_CERT;
#if defined(AGRO_SIMULATED_HARDWARE)
  values.soilDryRaw = BENCH_DRY_RAW;
  values.soilWetRaw = BENCH_WET_RAW;
#else
  values.soilDryRaw = AGRO_SOIL_DRY_RAW;
  values.soilWetRaw = AGRO_SOIL_WET_RAW;
#endif
  return values;
}

const DeviceSettings fieldSettings = settings();
BoardClock boardClock;
BoardWifi boardWifi;
HttpBackend httpBackend(boardClock);
BackendProxy backend(httpBackend, boardClock);

#if defined(AGRO_SIMULATED_HARDWARE)
SimulatedHardwareFactory hardware(boardClock, fieldSettings.soilDryRaw, fieldSettings.soilWetRaw);
#else
#if defined(AGRO_USE_SHT31)
Adafruit_SHT31 airChip(&Wire);
Sht31Adapter airSensor(airChip);
#else
DHT airChip(PIN_DHT, DHT22);
Dht22Adapter airSensor(airChip, boardClock);
#endif
RealHardwareFactory hardware(airSensor, boardClock, fieldSettings.soilDryRaw, fieldSettings.soilWetRaw);
#endif

AgroSenseDevice device(hardware, backend, boardClock);
uint32_t nextStatusAt = 0;

void setup() {
  configureRelayPinOff();  // the first thing, so the pump cannot run while the rest of setup() takes its time
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  Serial.begin(115200);
  analogReadResolution(ADC_BITS);
  analogSetPinAttenuation(PIN_SOIL, ADC_11db);
#if defined(AGRO_USE_SHT31)
  Wire.begin();
#endif

  DeviceConfig::instance().load(fieldSettings);
  const DeviceSettings& config = DeviceConfig::instance().get();
  WifiManager::instance().attach(boardWifi, config.wifiSsid, config.wifiPassword);
  if (config.soilDryRaw == config.soilWetRaw) {
    Serial.println("AgroSense: suelo sin calibrar (firmware/README.md); no se envía su humedad");
  }
  if (!device.begin()) Serial.println("AgroSense: revisa arduino_secrets.h y el sensor de aire");
  if (config.tls && config.caCert[0] == '\0') {
    Serial.println("AgroSense: sin certificado raíz; HTTPS cifra pero no comprueba el servidor");
  }
  if (!httpBackend.startWorker()) Serial.println("AgroSense: no arrancó la tarea de red");
  if (!startPumpWatch(hardware.actuator())) {
    Serial.println("AgroSense: sin temporizador de la bomba ni perro guardián");
  } else if (!startWatchdog()) {
    Serial.println("AgroSense: sin perro guardián");
  }
}

void loop() {
  feedWatchdog();
  startNetworkTimeOnce(fieldSettings.utcOffsetMinutes * 60L);
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
  delay(1);  // lets the idle task and the Wi-Fi stack run on this core
}
