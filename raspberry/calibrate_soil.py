"""Calibrates the soil moisture sensor read through the MCP3008: measures it dry (in the air) and wet (in
water) and prints the SOIL_DRY_RAW and SOIL_WET_RAW to copy into simulator.env. With --watch it shows the raw
reading and the percentage it means, to check a calibration in the soil."""
import argparse
import os
import statistics
import sys
import time
from pathlib import Path

import hardware
from simulator import DEFAULT_CONFIG_FILE, read_config_file

SAMPLES = 20
# Fewer counts than this between dry and wet: the sensor is not reaching the water, or it is wired wrong.
MIN_SPAN = 100


def settings(path: str) -> dict:
    file = Path(path)
    values = read_config_file(file) if file.is_file() else {}
    values.update({key: value for key, value in os.environ.items() if value})
    return values


def measure(adc: hardware.Mcp3008, label: str) -> int:
    readings = []
    for number in range(1, SAMPLES + 1):
        readings.append(adc.median_raw())
        print(f"\r  {label}: lectura {number}/{SAMPLES} = {readings[-1]:4d}", end="", flush=True)
        time.sleep(0.25)
    value = int(statistics.median(readings))
    print(f"\r  {label}: {value} (entre {min(readings)} y {max(readings)})          ")
    return value


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Calibra el sensor de humedad del suelo (MCP3008).")
    parser.add_argument("--config", default=DEFAULT_CONFIG_FILE, help="archivo KEY=VALUE con ADC_SPI_BUS, "
                                                                      "ADC_SPI_DEVICE, ADC_CHANNEL")
    parser.add_argument("--watch", action="store_true", help="muestra la lectura y su porcentaje cada segundo")
    args = parser.parse_args(argv)
    values = settings(args.config)
    bus, device, channel = (int(values.get(name, "0")) for name in ("ADC_SPI_BUS", "ADC_SPI_DEVICE", "ADC_CHANNEL"))
    if not hardware.raspberry_pi_model():
        print("Esto no es una Raspberry Pi: la calibración necesita el sensor conectado.")
        return 2
    missing = hardware.missing_hardware(bus, device)
    if missing:
        print(f"Faltan {', '.join(missing)}.")
        return 2
    adc = hardware.Mcp3008(bus, device, channel)
    try:
        if args.watch:
            dry, wet = values.get("SOIL_DRY_RAW"), values.get("SOIL_WET_RAW")
            if not dry or not wet:
                print("Sin SOIL_DRY_RAW y SOIL_WET_RAW en la configuración: se muestra solo la lectura.")
            print("Ctrl+C para salir.")
            while True:
                raw = adc.median_raw()
                percent = f" = {hardware.soil_percent(raw, float(dry), float(wet)):5.1f} %" if dry and wet else ""
                print(f"\r  SPI{bus} CE{device} canal {channel}: {raw:4d}{percent}   ", end="", flush=True)
                time.sleep(1)
        print(f"Sensor de suelo en SPI{bus} CE{device}, canal {channel} del MCP3008.\n")
        input("1. Saca el sensor de la tierra, sécalo y déjalo al aire. Pulsa Enter...")
        dry = measure(adc, "Seco")
        input("2. Mételo en un vaso de agua hasta la línea de máximo (sin mojar la electrónica). Pulsa Enter...")
        wet = measure(adc, "Mojado")
    except KeyboardInterrupt:
        print()
        return 0 if args.watch else 1
    finally:
        adc.close()
    if abs(dry - wet) < MIN_SPAN:
        print(f"\nSeco y mojado solo se diferencian en {abs(dry - wet)} (menos de {MIN_SPAN}): revisa el cableado "
              "y que el sensor toque el agua, y repite.")
        return 1
    print("\nCopia estas dos líneas en simulator.env y reinicia el servicio (sudo systemctl restart agrosense):\n")
    print(f"SOIL_DRY_RAW={dry}")
    print(f"SOIL_WET_RAW={wet}")
    print("\nPara comprobarlo en la tierra: .venv/bin/python calibrate_soil.py --watch")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
