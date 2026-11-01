#pragma once
#include "Arduino.h"

constexpr int WL_CONNECTED = 3;

struct WiFiStub {
  int begin(const char*, const char*);
  int status();
  unsigned long getTime();
};
extern WiFiStub WiFi;

class WiFiClient : public Client {
 public:
  int connect(const char*, uint16_t) override;
  void stop() override;
};
