// Proxy: same interface as the real connection, with what the field needs in front of it. Events wait in a ring
// buffer, leave oldest first, are retried with a growing wait, and a backend asleep on Render's free plan (it takes
// up to three minutes to start) gets a steady retry instead of the back-off. A 4xx is the event's own fault: dropped.
#pragma once
#include <string.h>

#include "Core.h"

namespace agro {

struct BackendResponse {
  int status;        // 0 when nothing came back
  const char* body;  // valid until the next post
};

class IBackend {
 public:
  virtual ~IBackend() = default;
  virtual BackendResponse post(const char* json) = 0;
};

class BackendProxy : public IBackend {
 public:
  static constexpr uint8_t CAPACITY = 16;
  static constexpr size_t EVENT_BYTES = 200;
  static constexpr uint32_t FIRST_WAIT_MS = 5000;
  static constexpr uint32_t MAX_WAIT_MS = 300000;
  static constexpr uint32_t WAKE_RETRY_MS = 15000;
  static constexpr uint32_t WAKE_WINDOW_MS = 180000;

  BackendProxy(IBackend& real, IClock& clock) : real_(real), clock_(clock) {}

  // Stores it and tries to send what is waiting, so it answers as the real backend would.
  BackendResponse post(const char* json) override {
    enqueue(json);
    return flush();
  }

  // With the buffer full the oldest reading goes: the newest says more about the field.
  bool enqueue(const char* json) {
    if (strlen(json) >= EVENT_BYTES) return false;
    if (count_ == CAPACITY) {
      head_ = (head_ + 1) % CAPACITY;
      count_--;
      lost_++;
    }
    strcpy(events_[(head_ + count_) % CAPACITY], json);
    count_++;
    return true;
  }

  // Sends one event at most: a call can block for a long time and the pump's timer must keep running.
  BackendResponse flush() {
    uint32_t now = clock_.nowMs();
    if (count_ == 0 || (waiting_ && !reached(now, retryAt_))) return BackendResponse{0, nullptr};
    BackendResponse response = real_.post(events_[head_]);
    now = clock_.nowMs();
    if (delivered(response.status) || refused(response.status)) {
      if (refused(response.status)) dropped_++;
      head_ = (head_ + 1) % CAPACITY;
      count_--;
      failing_ = false;
      waiting_ = false;
      wait_ = FIRST_WAIT_MS;
      return response;
    }
    planRetry(response.status, now);
    return response;
  }

  uint8_t pending() const { return count_; }
  uint16_t lost() const { return lost_; }
  uint16_t dropped() const { return dropped_; }
  bool waking() const { return waking_; }

 private:
  static bool delivered(int status) { return status >= 200 && status < 300; }
  static bool refused(int status) { return status >= 400 && status < 500 && status != 408 && status != 429; }
  static bool looksAsleep(int status) { return status == 0 || status == 502 || status == 503 || status == 504; }

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
    retryAt_ = now + delay;
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
  uint32_t retryAt_ = 0;
  uint32_t failingSince_ = 0;
  bool waiting_ = false;
  bool failing_ = false;
  bool waking_ = false;
};

}  // namespace agro
