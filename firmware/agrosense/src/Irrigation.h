// Bridge: IrrigationOrder says who asks for water and on what terms; ActuatorDriver says how it is started. The same
// two hierarchies as the backend, ending here at the relay.
#pragma once
#include "Core.h"

namespace agro {

class ActuatorDriver {
 public:
  virtual ~ActuatorDriver() = default;
  virtual void start(uint32_t durationMs) = 0;
  virtual void stop() = 0;
  virtual bool running() const = 0;
  virtual void update() = 0;
};

// Keeps the time; the subclasses only say what on and off mean.
class TimedDriver : public ActuatorDriver {
 public:
  explicit TimedDriver(IClock& clock) : clock_(clock) {}

  void start(uint32_t durationMs) override {
    stopAt_ = clock_.nowMs() + durationMs;
    running_ = true;
    apply(true);
  }

  void stop() override {
    running_ = false;
    apply(false);
  }

  bool running() const override { return running_; }

  void update() override {
    if (running_ && reached(clock_.nowMs(), stopAt_)) stop();
  }

 protected:
  virtual void apply(bool on) = 0;

 private:
  IClock& clock_;
  uint32_t stopAt_ = 0;
  bool running_ = false;
};

class RelayDriver : public TimedDriver {
 public:
  RelayDriver(DigitalPort& port, uint8_t pin, bool activeLow, IClock& clock)
      : TimedDriver(clock), port_(port), pin_(pin), activeLow_(activeLow) {
    apply(false);  // whatever happened before a reset, the pump starts off
  }

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

class IrrigationOrder {
 public:
  static constexpr uint16_t MAX_MINUTES = 120;

  explicit IrrigationOrder(ActuatorDriver& driver) : driver_(driver) {}
  virtual ~IrrigationOrder() = default;

  bool execute() {
    uint16_t length = minutes();
    if (length == 0 || length > MAX_MINUTES || !authorized()) return false;
    driver_.start(static_cast<uint32_t>(length) * 60000UL);
    return true;
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
