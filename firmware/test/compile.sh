#!/bin/sh
# Compiles, for the ESP32 Dev Module, the real device (esp32-tcp/planticas_proyecto) and the unused HTTP firmware in its
# three variants with its calibration sketch. Needs arduino-cli with the esp32:esp32 core and the libraries of both
# READMEs. Example secrets are used: no key is needed. ARDUINO_CLI picks another binary; ARDUINO_CONFIG passes a --config-file.
set -e
cd "$(dirname "$0")/.."
cli="${ARDUINO_CLI:-arduino-cli}"
if [ -n "$ARDUINO_CONFIG" ]; then cli="$cli --config-file $ARDUINO_CONFIG"; fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r agrosense "$work/agrosense"
cp agrosense/arduino_secrets.example.h "$work/agrosense/arduino_secrets.h"
board=esp32:esp32:esp32
$cli compile --fqbn "$board" --warnings default "$work/agrosense"
$cli compile --fqbn "$board" --build-property "compiler.cpp.extra_flags=-DAGRO_USE_SHT31" "$work/agrosense"
$cli compile --fqbn "$board" --build-property "compiler.cpp.extra_flags=-DAGRO_SIMULATED_HARDWARE" "$work/agrosense"
$cli compile --fqbn "$board" calibrate_soil
# The real device: firmware/esp32-tcp, with its example secrets.
cp -r esp32-tcp/planticas_proyecto "$work/planticas_proyecto"
sed 's/"CAMBIA_ESTA_CLAVE"/"clave-de-compilacion-ci"/' esp32-tcp/planticas_proyecto/arduino_secrets.example.h   > "$work/planticas_proyecto/arduino_secrets.h"
$cli compile --fqbn "$board" "$work/planticas_proyecto"
