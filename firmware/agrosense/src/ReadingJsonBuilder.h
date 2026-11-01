// Builder: every measure is optional, the event must still come out valid, and on this board it has to be written
// into a fixed buffer. Numbers go through integers because the board's printf may not format floats.
#pragma once
#include <stdio.h>
#include <string.h>

#include "Core.h"

namespace agro {

class ReadingJsonBuilder {
 public:
  ReadingJsonBuilder(char* buffer, size_t size) : buffer_(buffer), size_(size) {}

  ReadingJsonBuilder& device(const char* id) {
    device_ = id;
    return *this;
  }
  ReadingJsonBuilder& timestamp(uint32_t epochSeconds) {
    epoch_ = epochSeconds;
    return *this;
  }
  ReadingJsonBuilder& soilMoisture(Reading reading) {
    soil_ = reading;
    return *this;
  }
  ReadingJsonBuilder& airTemperature(Reading reading) {
    air_ = reading;
    return *this;
  }
  ReadingJsonBuilder& relativeHumidity(Reading reading) {
    humidity_ = reading;
    return *this;
  }

  // The result of an irrigation order, reported in the next event so the backend can record it.
  ReadingJsonBuilder& acknowledge(uint32_t commandId, bool started) {
    ackId_ = commandId;
    ackStarted_ = started;
    return *this;
  }

  // False, and an empty buffer, without a safe device code, without a single measure or without room.
  bool build() {
    used_ = 0;
    overflow_ = false;
    bool usable = safeCode(device_) && (soil_.valid || air_.valid || humidity_.valid);
    if (usable) {
      append("{\"device_id\":\"");
      append(device_);
      append("\",\"type\":\"reading\"");
      if (epoch_ != 0) {
        char digits[24];
        snprintf(digits, sizeof digits, ",\"ts\":%lu", static_cast<unsigned long>(epoch_));
        append(digits);
      }
      if (ackId_ != 0) {
        char ack[56];
        snprintf(ack, sizeof ack, ",\"ack\":{\"command_id\":%lu,\"result\":\"%s\"}",
                 static_cast<unsigned long>(ackId_), ackStarted_ ? "started" : "refused");
        append(ack);
      }
      append(",\"data\":{");
      bool first = true;
      field("soil_moisture", soil_, first);
      field("air_temperature", air_, first);
      field("relative_humidity", humidity_, first);
      append("}}");
    }
    if (!usable || overflow_) {
      if (size_ > 0) buffer_[0] = '\0';
      return false;
    }
    return true;
  }

 private:
  static bool safeCode(const char* code) {
    if (code == nullptr || code[0] == '\0') return false;
    for (const char* c = code; *c != '\0'; c++) {
      bool letter = (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z');
      bool digit = *c >= '0' && *c <= '9';
      if (!letter && !digit && *c != '-' && *c != '_') return false;
    }
    return true;
  }

  void append(const char* text) {
    size_t length = strlen(text);
    if (used_ + length + 1 > size_) {
      overflow_ = true;
      return;
    }
    memcpy(buffer_ + used_, text, length + 1);
    used_ += length;
  }

  void field(const char* name, Reading reading, bool& first) {
    if (!reading.valid) return;
    if (!first) append(",");
    first = false;
    append("\"");
    append(name);
    append("\":");
    long tenths = static_cast<long>(reading.value * 10.0f + (reading.value < 0 ? -0.5f : 0.5f));
    unsigned long magnitude = static_cast<unsigned long>(tenths < 0 ? -tenths : tenths);
    char digits[20];
    snprintf(digits, sizeof digits, "%s%lu.%lu", tenths < 0 ? "-" : "", magnitude / 10, magnitude % 10);
    append(digits);
  }

  char* buffer_;
  size_t size_;
  size_t used_ = 0;
  bool overflow_ = false;
  const char* device_ = nullptr;
  uint32_t epoch_ = 0;
  uint32_t ackId_ = 0;
  bool ackStarted_ = false;
  Reading soil_ = Reading::bad();
  Reading air_ = Reading::bad();
  Reading humidity_ = Reading::bad();
};

}  // namespace agro
