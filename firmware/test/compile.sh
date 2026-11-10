#!/bin/sh
# Compiles the sketch with the real toolchain for the ESP32 Dev Module, in its three variants, and the calibration
# sketch. Needs arduino-cli with the esp32:esp32 core and the libraries in firmware/README.md. Example secrets are
# used: no key is needed. ARDUINO_CLI picks another binary; ARDUINO_CONFIG passes a --config-file.
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
