// Reads an HTTP answer one byte at a time as the bytes arrive, so waiting for it never holds the loop. Headers are
// not kept: only the status, Content-Length and as much of the body as fits.
#pragma once
#include <stdlib.h>
#include <string.h>

#include "Core.h"

namespace agro {

class HttpResponseReader {
 public:
  static constexpr size_t BODY_BYTES = 384;

  void reset() {
    status_ = 0;
    line_ = 0;
    used_ = 0;
    received_ = 0;
    contentLength_ = -1;
    inBody_ = false;
    firstLine_ = true;
    body_[0] = '\0';
  }

  void feed(char c) {
    if (inBody_) {
      received_++;
      if (used_ < BODY_BYTES - 1) {
        body_[used_++] = c;
        body_[used_] = '\0';
      }
      return;
    }
    if (c == '\r') return;
    if (c != '\n') {
      if (line_ < sizeof lineBuffer_ - 1) lineBuffer_[line_++] = c;
      return;
    }
    lineBuffer_[line_] = '\0';
    if (line_ == 0) {
      inBody_ = true;
    } else if (firstLine_) {
      const char* space = strchr(lineBuffer_, ' ');
      status_ = space == nullptr ? 0 : atoi(space + 1);
      firstLine_ = false;
    } else if (startsWithLength(lineBuffer_)) {
      contentLength_ = atol(lineBuffer_ + 15);
    }
    line_ = 0;
  }

  bool complete() const { return inBody_ && contentLength_ >= 0 && received_ >= contentLength_; }
  // The status of an answer known to be whole, else 0, which is what "nothing came back" means to the proxy. A length
  // says when the answer ended; without one only the closed connection does.
  int finalStatus(bool closed) const {
    bool whole = complete() || (closed && inBody_ && contentLength_ < 0);
    return whole ? status_ : 0;
  }
  int status() const { return status_; }
  const char* body() const { return body_; }

 private:
  static bool startsWithLength(const char* line) {
    static const char NAME[] = "content-length:";
    for (size_t i = 0; i < sizeof NAME - 1; i++) {
      char c = line[i];
      if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + 32);
      if (c != NAME[i]) return false;
    }
    return true;
  }

  int status_ = 0;
  size_t line_ = 0;
  size_t used_ = 0;
  long received_ = 0;
  long contentLength_ = -1;
  bool inBody_ = false;
  bool firstLine_ = true;
  char lineBuffer_[64];
  char body_[BODY_BYTES];
};

}  // namespace agro
