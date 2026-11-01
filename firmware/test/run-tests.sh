#!/bin/sh
# The logic of the firmware on a PC, with warnings as errors, and then a compile-only pass of the board's own file
# against stubs of its libraries. The stubs catch typos and type errors; they prove nothing about the real board.
set -e
cd "$(dirname "$0")"
output="$(mktemp -d)"
trap 'rm -rf "$output"' EXIT
flags="-std=c++17 -Wall -Wextra -Werror -fsanitize=undefined"
g++ $flags -I../agrosense/src test_patterns.cpp -o "$output/run_tests" 2>/dev/null \
  || g++ -std=c++17 -Wall -Wextra -Werror -I../agrosense/src test_patterns.cpp -o "$output/run_tests"
"$output/run_tests"
cp ../agrosense/agrosense.ino "$output/sketch.cpp"
cp ../agrosense/arduino_secrets.example.h "$output/arduino_secrets.h"
mkdir "$output/src"
cp ../agrosense/src/* "$output/src/"
g++ -std=c++17 -Wall -Wextra -fsyntax-only -include Arduino.h -Istubs -I"$output" "$output/sketch.cpp"
g++ -std=c++17 -Wall -Wextra -fsyntax-only -include Arduino.h -DAGRO_USE_SHT31 -Istubs -I"$output" "$output/sketch.cpp"
g++ -std=c++17 -Wall -Wextra -fsyntax-only -include Arduino.h -DAGRO_SIMULATED_HARDWARE -Istubs -I"$output" "$output/sketch.cpp"
echo "board file compiled against the stubs"
