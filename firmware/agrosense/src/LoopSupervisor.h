// Lets the watchdog see through WiFiS3. Its maximum on this board is 5.6 s, while one AT command may wait 10 s, so
// loop() cannot be the one that feeds it. The timer interrupt does, for as long as loop() was alive not long ago. If
// loop() hangs the interrupt stops feeding; if the interrupts themselves die nobody feeds. Either way the board resets
// and the pump comes back off.
#pragma once
#include "Core.h"

namespace agro {

class LoopSupervisor {
 public:
  // Longer than anything WiFiS3 can legitimately block for: several AT commands in a row while connecting.
  static constexpr uint32_t MAX_SILENCE_MS = 60000;

  void beat(uint32_t now) { lastBeat_ = now; }

  bool mayFeed(uint32_t now) const { return now - lastBeat_ < MAX_SILENCE_MS; }

 private:
  volatile uint32_t lastBeat_ = 0;
};

}  // namespace agro
