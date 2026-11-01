// Prints the raw soil reading twice a second. Note it with the sensor in dry soil and with it in water, and put both
// numbers in soilDryRaw and soilWetRaw (agrosense/src/DeviceConfig.h). The relay pin is held off the whole time.
const uint8_t PIN_SOIL = A0;
const uint8_t PIN_RELAY = 7;
const bool RELAY_ACTIVE_LOW = true;

void setup() {
  digitalWrite(PIN_RELAY, RELAY_ACTIVE_LOW ? HIGH : LOW);
  pinMode(PIN_RELAY, OUTPUT);
  Serial.begin(115200);
  analogReadResolution(14);
}

void loop() {
  long total = 0;
  for (int i = 0; i < 16; i++) total += analogRead(PIN_SOIL);
  Serial.print("raw: ");
  Serial.println(total / 16);
  delay(500);
}
