#!/bin/sh
# The logic of the firmware on a PC, with warnings as errors. The board's own code (ArduinoPorts.h and the sketch) only
# compiles with the Arduino toolchain: see compile.sh.
set -e
cd "$(dirname "$0")"
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
g++ -std=c++17 -Wall -Wextra -Werror -I../agrosense/src test_patterns.cpp -o "$output/run_tests"
"$output/run_tests"
