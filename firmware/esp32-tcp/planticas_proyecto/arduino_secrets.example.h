// Copia este archivo como arduino_secrets.h, junto a él, y complétalo. Git ignora arduino_secrets.h.
#pragma once

// La red Wi-Fi que crea el ESP32 (modo AP). El backend y los docs esperan el nombre PLANTICAS.
#define SECRET_AP_SSID "PLANTICAS"

// La clave de la red: MÍNIMO 12 CARACTERES (WPA2 admite hasta 63) y DISTINTA DEL NOMBRE DE LA RED.
// Reemplaza el marcador: el sketch no compila mientras siga así, ni con una clave corta o igual al nombre.
#define SECRET_AP_PASS "CAMBIA_ESTA_CLAVE"
