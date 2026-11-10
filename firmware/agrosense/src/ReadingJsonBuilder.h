// Builder: every measure is optional, the event must still come out valid, and it is written into a fixed buffer so
// nothing is allocated on every reading. Numbers go through integers so the output does not depend on how a printf
// formats floats.
#pragma once
#include <stdio.h>
#include <string.h>

#include "Core.h"

namespace agro {

enum class AckResult : uint8_t { Started, Refused, Finished };

class ReadingJsonBuilder {
 public:
  ReadingJsonBuilder(char* buffer, size_t size) : buffer_(buffer), size_(size) {}

  ReadingJsonBuilder& device(const char* id) {
    device_ = id;
    return *this;
  }
  // The moment as seconds since 1970 in UTC, written as ISO-8601 in the field's own zone: -300 is -05:00.
  ReadingJsonBuilder& timestamp(uint32_t epochSeconds, int16_t utcOffsetMinutes) {
    epoch_ = epochSeconds;
    offsetMinutes_ = utcOffsetMinutes;
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
  ReadingJsonBuilder& acknowledge(uint32_t commandId, AckResult result, uint32_t ranSeconds) {
    ackId_ = commandId;
    ackResult_ = result;
    ackSeconds_ = ranSeconds;
    return *this;
  }

  // False, and an empty buffer, without a safe device code, without a single measure or without room.
  // 2026-10-31T21:26:40-05:00. The civil date comes from the day count (Howard Hinnant's days_from_civil, inverted),
  // so no time zone database or gmtime() is needed and the same code runs on the board and in the tests.
  static void isoTime(uint32_t epochSeconds, int16_t offsetMinutes, char* out, size_t size) {
    long long local = static_cast<long long>(epochSeconds) + offsetMinutes * 60LL;
    long long days = local >= 0 ? local / 86400 : (local - 86399) / 86400;
    long secondOfDay = static_cast<long>(local - days * 86400);
    long long z = days + 719468;
    long long era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned dayOfEra = static_cast<unsigned>(z - era * 146097);
    unsigned yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365;
    unsigned dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
    unsigned shifted = (5 * dayOfYear + 2) / 153;
    unsigned day = dayOfYear - (153 * shifted + 2) / 5 + 1;
    unsigned month = shifted < 10 ? shifted + 3 : shifted - 9;
    long year = static_cast<long>(yearOfEra + era * 400) + (month <= 2 ? 1 : 0);
    int offset = offsetMinutes < 0 ? -offsetMinutes : offsetMinutes;
    snprintf(out, size, "%04ld-%02u-%02uT%02ld:%02ld:%02ld%c%02d:%02d", year, month, day, secondOfDay / 3600,
             secondOfDay / 60 % 60, secondOfDay % 60, offsetMinutes < 0 ? '-' : '+', offset / 60, offset % 60);
  }

  bool build() {
    used_ = 0;
    overflow_ = false;
    bool usable = safeCode(device_) && (soil_.valid || air_.valid || humidity_.valid);
    if (usable) {
      append("{\"device_id\":\"");
      append(device_);
      append("\",\"type\":\"reading\"");
      if (epoch_ != 0) {
        char stamp[40];
        isoTime(epoch_, offsetMinutes_, stamp, sizeof stamp);
        append(",\"ts\":\"");
        append(stamp);
        append("\"");
      }
      if (ackId_ != 0) {
        static const char* const RESULTS[] = {"started", "refused", "finished"};
        char ack[80];
        snprintf(ack, sizeof ack, ",\"ack\":{\"command_id\":%lu,\"result\":\"%s\"",
                 static_cast<unsigned long>(ackId_), RESULTS[static_cast<uint8_t>(ackResult_)]);
        append(ack);
        if (ackResult_ == AckResult::Finished) {
          snprintf(ack, sizeof ack, ",\"ran_seconds\":%lu", static_cast<unsigned long>(ackSeconds_));
          append(ack);
        }
        append("}");
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
  int16_t offsetMinutes_ = 0;
  uint32_t ackId_ = 0;
  AckResult ackResult_ = AckResult::Started;
  uint32_t ackSeconds_ = 0;
  Reading soil_ = Reading::bad();
  Reading air_ = Reading::bad();
  Reading humidity_ = Reading::bad();
};

}  // namespace agro
