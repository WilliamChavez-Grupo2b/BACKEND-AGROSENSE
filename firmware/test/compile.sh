#!/bin/sh
# Compiles the sketch with the real toolchain, in its three variants, and the calibration sketch. Needs arduino-cli with
# the arduino:renesas_uno core and the libraries in firmware/README.md. Example secrets are used: no key is needed.
set -e
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r agrosense "$work/agrosense"
cp agrosense/arduino_secrets.h.example "$work/agrosense/arduino_secrets.h"
board=arduino:renesas_uno:unor4wifi
arduino-cli compile --fqbn "$board" "$work/agrosense"
arduino-cli compile --fqbn "$board" --build-property "compiler.cpp.extra_flags=-DAGRO_USE_SHT31" "$work/agrosense"
arduino-cli compile --fqbn "$board" --build-property "compiler.cpp.extra_flags=-DAGRO_SIMULATED_HARDWARE" "$work/agrosense"
arduino-cli compile --fqbn "$board" calibrate_soil
