// Copy this file as arduino_secrets.h, next to it, and fill it in. git ignores arduino_secrets.h.
#pragma once

// The ESP32 only joins 2.4 GHz networks.
#define SECRET_WIFI_SSID "my-network"
#define SECRET_WIFI_PASS "my-password"

// The code registered with POST /api/devices and the key that call returned, which is shown only once.
#define SECRET_DEVICE_ID "ESP32-01"
#define SECRET_DEVICE_KEY ""

// The event service. In production its Render host, port 443 and TLS on; on a laptop its IP, 8100 and TLS off.
#define SECRET_EVENTS_HOST "agrosense-eventos.onrender.com"
#define SECRET_EVENTS_PORT 443
#define SECRET_EVENTS_TLS true

// Optional: the PEM of the root certificate of SECRET_EVENTS_HOST, as one string with \n at the end of each line.
// Empty, HTTPS still encrypts but does not check who answers. firmware/README.md says how to get it.
#define SECRET_EVENTS_CA_CERT ""

// The soil sensor's counts (0 to 4095) measured with firmware/calibrate_soil: in dry soil and in water. Two equal
// values mean not calibrated, and then no soil moisture is sent. Do not guess them.
#define AGRO_SOIL_DRY_RAW 0
#define AGRO_SOIL_WET_RAW 0
