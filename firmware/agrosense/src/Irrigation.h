// Bridge: IrrigationOrder says who asks for water and on what terms; ActuatorDriver says how it is started. The same
// two hierarchies as the backend, ending here at the relay.
//
// The pump's safety lives in this file and depends on no network. TimedDriver stops at a deadline measured with the
// local clock and refuses to run past MAX_PUMP_ON_MS; PumpGuard, a decorator, adds the limits per day and between
// runs. A timer of its own (ArduinoPorts.h) calls enforceDeadline() ten times a second, so a blocked loop() cannot keep
// the pump running.
#pragma once
#include "Core.h"

namespace agro {

constexpr uint32_t MAX_PUMP_ON_MS = 5UL * 60UL * 1000UL;
constexpr uint32_t MAX_PUMP_MS_PER_DAY = 30UL * 60UL * 1000UL;
constexpr uint32_t MIN_PUMP_GAP_MS = 5UL * 60UL * 1000UL;
constexpr uint32_t DAY_MS = 24UL * 60UL * 60UL * 1000UL;

class ActuatorDriver {
 public:
  virtual ~ActuatorDriver() = default;
  virtual void begin() = 0;                       // pump off; call it from setup(), never from a constructor
  virtual bool start(uint32_t durationMs) = 0;    // false when refused
  virtual void stop() = 0;
  virtual bool running() const = 0;
  virtual uint32_t lastRunMs() const = 0;         // how long the last run really lasted
  virtual void update() = 0;                      // from loop()
  virtual void enforceDeadline() = 0;             // safe from an interrupt
};

// Keeps the time; the subclasses only say what on and off mean.
class TimedDriver : public ActuatorDriver {
 public:
  explicit TimedDriver(IClock& clock) : clock_(clock) {}

  void begin() override { stop(); }

  bool start(uint32_t durationMs) override {
    if (durationMs == 0) return false;
    uint32_t length = durationMs > MAX_PUMP_ON_MS ? MAX_PUMP_ON_MS : durationMs;
    startedAt_ = clock_.nowMs();
    lastRunMs_ = 0;
    stopAt_ = startedAt_ + length;
    // The relay first and running_ last: with these two the other way round, an interrupt between them could stop a
    // run that this line then turns on, leaving the relay closed with running_ false and nothing watching it.
    apply(true);
    running_ = true;  // and after stopAt_: the interrupt must never see running with an old deadline
    return true;
  }

  void stop() override {
    if (running_) lastRunMs_ = clock_.nowMs() - startedAt_;
    running_ = false;
    apply(false);
  }

  bool running() const override { return running_; }
  uint32_t lastRunMs() const override { return lastRunMs_; }

  void update() override { enforceDeadline(); }

  void enforceDeadline() override {
    if (running_ && reached(clock_.nowMs(), stopAt_)) stop();
  }

 protected:
  virtual void apply(bool on) = 0;

 private:
  IClock& clock_;
  volatile uint32_t startedAt_ = 0;
  volatile uint32_t lastRunMs_ = 0;
  volatile uint32_t stopAt_ = 0;
  volatile bool running_ = false;
};

class RelayDriver : public TimedDriver {
 public:
  RelayDriver(DigitalPort& port, uint8_t pin, bool activeLow, IClock& clock)
      : TimedDriver(clock), port_(port), pin_(pin), activeLow_(activeLow) {}

 protected:
  void apply(bool on) override { port_.write(pin_, on != activeLow_); }

 private:
  DigitalPort& port_;
  uint8_t pin_;
  bool activeLow_;
};

class SimulatedDriver : public TimedDriver {
 public:
  using TimedDriver::TimedDriver;
  uint16_t starts() const { return starts_; }

 protected:
  void apply(bool on) override {
    if (on) starts_++;
  }

 private:
  uint16_t starts_ = 0;
};

// Decorator of a driver. The counters are in RAM: a reset forgets the day's total, so the gap also counts from the
// boot, and a board stuck rebooting cannot run the pump again and again.
class PumpGuard : public ActuatorDriver {
 public:
  PumpGuard(ActuatorDriver& inner, IClock& clock) : inner_(inner), clock_(clock) {}

  void begin() override {
    inner_.begin();
    lastStopAt_ = clock_.nowMs();
    dayStartedAt_ = lastStopAt_;
    usedMs_ = 0;
    wasRunning_ = false;
  }

  bool start(uint32_t durationMs) override {
    update();
    uint32_t now = clock_.nowMs();
    if (now - dayStartedAt_ >= DAY_MS) {
      dayStartedAt_ = now;
      usedMs_ = 0;
    }
    if (inner_.running() || !elapsed(now, lastStopAt_, MIN_PUMP_GAP_MS) || usedMs_ >= MAX_PUMP_MS_PER_DAY) return false;
    uint32_t left = MAX_PUMP_MS_PER_DAY - usedMs_;
    if (!inner_.start(durationMs > left ? left : durationMs)) return false;
    wasRunning_ = true;
    return true;
  }

  void stop() override {
    inner_.stop();
    account();
  }

  bool running() const override { return inner_.running(); }
  uint32_t lastRunMs() const override { return inner_.lastRunMs(); }

  void update() override {
    inner_.update();
    account();
  }

  void enforceDeadline() override { inner_.enforceDeadline(); }

  uint32_t usedTodayMs() const { return usedMs_; }

 private:
  void account() {
    if (!wasRunning_ || inner_.running()) return;
    wasRunning_ = false;
    lastStopAt_ = clock_.nowMs();
    usedMs_ += inner_.lastRunMs();
  }

  ActuatorDriver& inner_;
  IClock& clock_;
  uint32_t lastStopAt_ = 0;
  uint32_t dayStartedAt_ = 0;
  uint32_t usedMs_ = 0;
  bool wasRunning_ = false;
};

class IrrigationOrder {
 public:
  static constexpr uint16_t MAX_MINUTES = 120;  // an order above this is an error; what runs is capped by the driver

  explicit IrrigationOrder(ActuatorDriver& driver) : driver_(driver) {}
  virtual ~IrrigationOrder() = default;

  bool execute() {
    uint16_t length = minutes();
    if (length == 0 || length > MAX_MINUTES || !authorized()) return false;
    return driver_.start(static_cast<uint32_t>(length) * 60000UL);
  }

 protected:
  virtual bool authorized() const = 0;
  virtual uint16_t minutes() const = 0;

 private:
  ActuatorDriver& driver_;
};

class ManualIrrigationOrder : public IrrigationOrder {
 public:
  ManualIrrigationOrder(ActuatorDriver& driver, uint16_t minutes) : IrrigationOrder(driver), minutes_(minutes) {}

 protected:
  bool authorized() const override { return true; }
  uint16_t minutes() const override { return minutes_; }

 private:
  uint16_t minutes_;
};

// The backend already judged the prediction. The device is the only one that sees the soil right now, so it has the
// last word: without a good reading, or with wet soil, no water.
class AiIrrigationOrder : public IrrigationOrder {
 public:
  static constexpr float MIN_CONFIDENCE = 0.70f;
  static constexpr float WET_SOIL_PERCENT = 85.0f;

  AiIrrigationOrder(ActuatorDriver& driver, uint16_t minutes, float confidence, Reading soil)
      : IrrigationOrder(driver), minutes_(minutes), confidence_(confidence), soil_(soil) {}

 protected:
  bool authorized() const override {
    return confidence_ >= MIN_CONFIDENCE && soil_.valid && soil_.value < WET_SOIL_PERCENT;
  }
  uint16_t minutes() const override { return minutes_; }

 private:
  uint16_t minutes_;
  float confidence_;
  Reading soil_;
};

}  // namespace agro
