// Only the signatures the firmware uses, for the compile-only check in run-tests.sh. Never linked or run.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

constexpr uint8_t A0 = 14;
constexpr int HIGH = 1;
constexpr int LOW = 0;
constexpr int OUTPUT = 1;
constexpr int INPUT_PULLUP = 2;

unsigned long millis();
void delay(unsigned long);
int analogRead(uint8_t);
void analogReadResolution(int);
void pinMode(uint8_t, int);
void digitalWrite(uint8_t, int);
int digitalRead(uint8_t);

class Client {
 public:
  virtual ~Client() = default;
  virtual int connect(const char* host, uint16_t port) = 0;
  virtual void stop() = 0;
  void setTimeout(unsigned long);
  size_t print(const char*);
  size_t print(size_t);
  bool find(const char*);
  long parseInt();
  size_t readBytes(char*, size_t);
};

struct SerialStub {
  void begin(unsigned long);
  size_t println(const char*);
};
extern SerialStub Serial;

class TwoWire {
 public:
  void begin();
};
extern TwoWire Wire1;
