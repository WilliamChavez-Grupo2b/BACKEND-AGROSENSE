// Prints the raw soil reading twice a second. Note it with the sensor in dry soil and with it in water, and put both
// numbers in soilDryRaw and soilWetRaw (agrosense/src/DeviceConfig.h). The relay pin is held off the whole time.
const uint8_t PIN_SOIL = A0;
const uint8_t PIN_RELAY = 7;
const bool RELAY_ACTIVE_LOW = true;

void setup() {
  // Not digitalWrite() and pinMode(): pinMode() leaves the pin low on this board, which closes an active-low relay, and
  // this sketch has no timer to open it again. Direction and level go in one register write.
  ioport_cfg_options_t level = RELAY_ACTIVE_LOW ? IOPORT_CFG_PORT_OUTPUT_HIGH : IOPORT_CFG_PORT_OUTPUT_LOW;
  R_IOPORT_PinCfg(NULL, g_pin_cfg[PIN_RELAY].pin, IOPORT_CFG_PORT_DIRECTION_OUTPUT | level);
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
