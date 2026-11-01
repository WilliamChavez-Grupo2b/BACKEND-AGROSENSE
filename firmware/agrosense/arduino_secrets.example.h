// Copy this file as arduino_secrets.h, next to it, and fill it in. git ignores arduino_secrets.h.
#pragma once

#define SECRET_WIFI_SSID "my-network"
#define SECRET_WIFI_PASS "my-password"

// The code registered with POST /api/devices and the key that call returned, which is shown only once.
#define SECRET_DEVICE_ID "UNO-R4-01"
#define SECRET_DEVICE_KEY ""

// The event service. In production its Render host, port 443 and TLS on; on a laptop its IP, 8100 and TLS off.
#define SECRET_EVENTS_HOST "agrosense-eventos.onrender.com"
#define SECRET_EVENTS_PORT 443
#define SECRET_EVENTS_TLS true
