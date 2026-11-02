// Proxy: same interface as the real connection, with what the field needs in front of it. Events wait in a ring
// buffer, leave oldest first, are retried with a growing wait, and a backend asleep on Render's free plan (it takes
// up to three minutes to start) gets a steady retry instead of the back-off. A 4xx is the event's own fault: dropped.
//
// A send is begun and then polled, so loop() keeps running while the server thinks. Up to CAPACITY events survive a
// network outage but not a power cut: this is RAM. lost() and dropped() count what never arrived.
#pragma once
#include <string.h>

#include "Core.h"

namespace agro {

struct BackendResponse {
  int status;        // 0 when nothing came back
  const char* body;  // valid until the next send
};

enum class SendState : uint8_t { Idle, Busy, Done };

class IBackend {
 public:
  virtual ~IBackend() = default;
  virtual bool begin(const char* json) = 0;  // false when the send could not even start
  virtual SendState poll(BackendResponse& response) = 0;
};

class BackendProxy : public IBackend {
 public:
  static constexpr uint8_t CAPACITY = 16;
  static constexpr size_t EVENT_BYTES = 256;
  static constexpr uint32_t FIRST_WAIT_MS = 5000;
  static constexpr uint32_t MAX_WAIT_MS = 300000;
  static constexpr uint32_t WAKE_RETRY_MS = 15000;
  static constexpr uint32_t WAKE_WINDOW_MS = 180000;

  BackendProxy(IBackend& real, IClock& clock) : real_(real), clock_(clock) {}

  // With the buffer full the oldest reading goes: the newest says more about the field.
  bool begin(const char* json) override {
    if (strlen(json) >= EVENT_BYTES) return false;
    if (count_ == CAPACITY) {
      head_ = (head_ + 1) % CAPACITY;
      count_--;
      lost_++;
      if (inFlight_) headGone_ = true;
    }
    strcpy(events_[(head_ + count_) % CAPACITY], json);
    count_++;
    return true;
  }

  // Sends one event at a time and gets out of the way between polls.
  SendState poll(BackendResponse& response) override {
    response = BackendResponse{0, nullptr};
    if (inFlight_) {
      if (real_.poll(response) == SendState::Busy) return SendState::Busy;
      inFlight_ = false;
      settle(response.status);
      return SendState::Done;
    }
    if (count_ == 0 || (waiting_ && !elapsed(clock_.nowMs(), waitingSince_, retryAfter_))) return SendState::Idle;
    if (!real_.begin(events_[head_])) {
      settle(0);
      return SendState::Done;
    }
    inFlight_ = true;
    return SendState::Busy;
  }

  bool busy() const { return inFlight_; }
  uint8_t pending() const { return count_; }
  uint16_t lost() const { return lost_; }
  uint16_t dropped() const { return dropped_; }
  bool waking() const { return waking_; }

 private:
  static bool delivered(int status) { return status >= 200 && status < 300; }
  static bool refused(int status) { return status >= 400 && status < 500 && status != 408 && status != 429; }
  static bool looksAsleep(int status) { return status == 0 || status == 502 || status == 503 || status == 504; }

  void settle(int status) {
    bool gone = delivered(status) || refused(status);
    if (refused(status)) dropped_++;
    if (gone) {
      if (!headGone_) {
        head_ = (head_ + 1) % CAPACITY;
        count_--;
      }
      failing_ = false;
      waking_ = false;
      waiting_ = false;
      wait_ = FIRST_WAIT_MS;
    } else {
      planRetry(status, clock_.nowMs());
    }
    headGone_ = false;
  }

  void planRetry(int status, uint32_t now) {
    if (!failing_) {
      failing_ = true;
      failingSince_ = now;
    }
    waking_ = looksAsleep(status) && now - failingSince_ < WAKE_WINDOW_MS;
    uint32_t delay = WAKE_RETRY_MS;
    if (!waking_) {
      delay = wait_;
      wait_ = wait_ * 2 > MAX_WAIT_MS ? MAX_WAIT_MS : wait_ * 2;
    }
    waitingSince_ = now;
    retryAfter_ = delay;
    waiting_ = true;
  }

  IBackend& real_;
  IClock& clock_;
  char events_[CAPACITY][EVENT_BYTES];
  uint8_t head_ = 0;
  uint8_t count_ = 0;
  uint16_t lost_ = 0;
  uint16_t dropped_ = 0;
  uint32_t wait_ = FIRST_WAIT_MS;
  uint32_t waitingSince_ = 0;
  uint32_t retryAfter_ = 0;
  uint32_t failingSince_ = 0;
  bool waiting_ = false;
  bool failing_ = false;
  bool waking_ = false;
  bool inFlight_ = false;
  bool headGone_ = false;
};

}  // namespace agro
