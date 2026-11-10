// For an ESP32 Dev Module: prints the raw soil reading (0 to 4095) twice a second. Note it with the sensor in dry
// soil and with it in water, and write both numbers as AGRO_SOIL_DRY_RAW and AGRO_SOIL_WET_RAW in
// agrosense/arduino_secrets.h. The relay pin is held off the whole time.
#include <driver/gpio.h>

const uint8_t PIN_SOIL = 34;  // ADC1: ADC2 pins do not read while Wi-Fi is on
const uint8_t PIN_RELAY = 26;
const bool RELAY_ACTIVE_LOW = true;

void setup() {
  // The level before the direction, as in the device firmware, so the relay never closes while calibrating.
  const int off = RELAY_ACTIVE_LOW ? 1 : 0;
  gpio_set_level(static_cast<gpio_num_t>(PIN_RELAY), off);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, off);
  Serial.begin(115200);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_SOIL, ADC_11db);
}

void loop() {
  long total = 0;
  for (int i = 0; i < 16; i++) total += analogRead(PIN_SOIL);
  Serial.print("raw: ");
  Serial.println(total / 16);
  delay(500);
}
