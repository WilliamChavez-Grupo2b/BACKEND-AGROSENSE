// Singleton: the radio is one piece of hardware, so a second owner could only fight the first. Every attempt drops
// the association being negotiated, so attempts are spaced with a growing wait; the core's own reconnection is off.
#pragma once
#include "Core.h"

namespace agro {

class WifiPort {
 public:
  virtual ~WifiPort() = default;
  virtual void begin(const char* ssid, const char* password) = 0;
  virtual bool connected() = 0;
};

class WifiManager {
 public:
  static constexpr uint32_t FIRST_WAIT_MS = 5000;
  static constexpr uint32_t MAX_WAIT_MS = 60000;

  static WifiManager& instance() {
    static WifiManager manager;
    return manager;
  }
  WifiManager(const WifiManager&) = delete;
  WifiManager& operator=(const WifiManager&) = delete;

  void attach(WifiPort& port, const char* ssid, const char* password) {
    port_ = &port;
    ssid_ = ssid;
    password_ = password;
    wait_ = FIRST_WAIT_MS;
    tried_ = false;
  }

  bool ensureConnected(uint32_t now) {
    if (port_ == nullptr) return false;
    if (port_->connected()) {
      wait_ = FIRST_WAIT_MS;
      tried_ = false;
      return true;
    }
    if (tried_ && !reached(now, nextTry_)) return false;
    port_->begin(ssid_, password_);
    nextTry_ = now + wait_;
    tried_ = true;
    wait_ = wait_ * 2 > MAX_WAIT_MS ? MAX_WAIT_MS : wait_ * 2;
    return port_->connected();
  }

 private:
  WifiManager() = default;
  WifiPort* port_ = nullptr;
  const char* ssid_ = "";
  const char* password_ = "";
  uint32_t wait_ = FIRST_WAIT_MS;
  uint32_t nextTry_ = 0;
  bool tried_ = false;
};

}  // namespace agro
