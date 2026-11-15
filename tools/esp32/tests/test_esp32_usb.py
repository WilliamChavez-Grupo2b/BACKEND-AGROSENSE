"""Pruebas del puente USB sin placa: python -m unittest discover -s tools/esp32/tests -p "test_*.py"."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import esp32_usb  # noqa: E402


class ReadingTests(unittest.TestCase):

    def test_the_serial_lines_of_the_sketch_become_the_tcp_block_of_its_wifi(self):
        reading = esp32_usb.Reading()
        for line in ["Red WiFi iniciada\r\n", "Temperatura exterior: 21.25\r\n", "Humedad relativa: 40.00\r\n",
                     "Humedad de la tierra: 28.12\r\n", "------------------------------------------\r\n"]:
            reading.feed(line, 100.0)

        self.assertTrue(reading.fresh(100.0))
        self.assertEqual(esp32_usb.block(reading), b"===== PLANTICAS =====\r\n"
                                                   b"Temperatura exterior: 21.25 C\r\n"
                                                   b"Humedad relativa: 40.00 %\r\n"
                                                   b"Humedad del suelo: 28.12 %\r\n"
                                                   b"\r\n")

    def test_nothing_is_sent_until_all_three_values_arrive_or_once_the_board_goes_quiet(self):
        reading = esp32_usb.Reading()
        reading.feed("Temperatura exterior: 21.25", 0.0)
        reading.feed("Humedad relativa: 40.00", 0.0)
        self.assertFalse(reading.fresh(0.0), "the soil is still missing")

        reading.feed("Humedad de la tierra: 28.12", 0.0)
        self.assertTrue(reading.fresh(esp32_usb.STALE_S))
        self.assertFalse(reading.fresh(esp32_usb.STALE_S + 0.1), "a board that stopped measuring sends nothing")

    def test_boot_noise_and_other_lines_are_ignored(self):
        reading = esp32_usb.Reading()
        for line in ["ets Jul 29 2019 12:21:46", "IP del ESP32: 192.168.4.1", "Cliente TCP conectado", "\x00\xff"]:
            self.assertFalse(reading.feed(line, 0.0))
        self.assertEqual(reading.values, {})


if __name__ == "__main__":
    unittest.main()
