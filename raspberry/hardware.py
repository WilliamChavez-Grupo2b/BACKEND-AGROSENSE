"""The real sensors of the field device: a DHT22 (air temperature and humidity) and a capacitive soil
moisture sensor read through an MCP3008 ADC. The hardware libraries are imported only here and only when
they are used, so the rest of the program runs on a PC without them."""
import logging
import math
import statistics
import time
from pathlib import Path

log = logging.getLogger("agrosense.raspberry")

PI_MODEL_FILE = Path("/proc/device-tree/model")
ADC_MAX = 1023
# What a DHT22 can measure; anything outside is a misread, not a reading.
AIR_TEMPERATURE_RANGE = (-40.0, 80.0)
HUMIDITY_RANGE = (0.0, 100.0)
# The DHT22 needs two seconds between reads.
DHT_PAUSE_S = 2.0


class ConfigError(SystemExit):
    """A setting the device cannot run without. Its message is in `code`; main() exits with status 2 for it,
    which the systemd unit does not restart (RestartPreventExitStatus=2)."""


def raspberry_pi_model(path: Path = PI_MODEL_FILE) -> str:
    try:
        model = path.read_text(encoding="utf-8", errors="replace").strip("\x00\n ")
    except OSError:
        return ""
    return model if "Raspberry Pi" in model else ""


def missing_hardware(spi_bus: int, spi_device: int) -> list[str]:
    """What this machine lacks to read the sensors; empty when everything is in place."""
    missing = []
    for module, package in (("board", "adafruit-blinka"), ("adafruit_dht", "adafruit-circuitpython-dht"),
                            ("spidev", "spidev")):
        try:
            __import__(module)
        except Exception:  # Blinka raises more than ImportError on a machine it does not know
            missing.append(f"la biblioteca {package}")
    if not Path(f"/dev/spidev{spi_bus}.{spi_device}").exists():
        missing.append(f"/dev/spidev{spi_bus}.{spi_device} (activa SPI con raspi-config)")
    return missing


def soil_percent(raw: float, dry_raw: float, wet_raw: float) -> float:
    """0 % at the dry reading, 100 % at the wet one, whichever way the sensor counts."""
    return round(min(100.0, max(0.0, (raw - dry_raw) / (wet_raw - dry_raw) * 100)), 1)


def plausible(value: object, low: float, high: float) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) \
        and low <= value <= high


class Dht22:
    def __init__(self, gpio: int, attempts: int, sleep=time.sleep, device=None):
        self.attempts = max(1, attempts)
        self.sleep = sleep
        if device is None:
            import adafruit_dht
            import board
            device = adafruit_dht.DHT22(getattr(board, f"D{gpio}"))
        self.device = device

    def read(self) -> dict[str, float]:
        """Temperature and humidity, each only if it was read and is plausible; tried again on a misread."""
        for attempt in range(1, self.attempts + 1):
            try:
                temperature, humidity = self.device.temperature, self.device.humidity
            except RuntimeError as error:  # checksum or timing error: common with the DHT22, worth a retry
                log.warning("DHT22 read failed (%s), attempt %d of %d", error, attempt, self.attempts)
            else:
                values = {}
                if plausible(temperature, *AIR_TEMPERATURE_RANGE):
                    values["air_temperature"] = round(float(temperature), 1)
                if plausible(humidity, *HUMIDITY_RANGE):
                    values["relative_humidity"] = round(float(humidity), 1)
                if len(values) == 2:
                    return values
                log.warning("DHT22 gave no plausible value (temperature %r, humidity %r), attempt %d of %d",
                            temperature, humidity, attempt, self.attempts)
            if attempt < self.attempts:
                self.sleep(DHT_PAUSE_S)
        log.error("DHT22 not read after %d attempts: this measurement goes without air values", self.attempts)
        return {}

    def close(self) -> None:
        exit_device = getattr(self.device, "exit", None)
        if exit_device:
            exit_device()


class Mcp3008:
    def __init__(self, bus: int, device: int, channel: int, spi=None):
        if not 0 <= channel <= 7:
            raise ConfigError("ADC_CHANNEL debe estar entre 0 y 7 (el MCP3008 tiene 8 canales).")
        self.channel = channel
        if spi is None:
            import spidev
            spi = spidev.SpiDev()
            spi.open(bus, device)
            spi.max_speed_hz = 1_350_000
            spi.mode = 0
        self.spi = spi

    def read_raw(self) -> int:
        # Start bit, single-ended mode and the channel; the 10-bit answer is in the last two bytes.
        answer = self.spi.xfer2([1, (8 + self.channel) << 4, 0])
        return ((answer[1] & 3) << 8) | answer[2]

    def median_raw(self, samples: int = 9, pause_s: float = 0.01, sleep=time.sleep) -> int:
        values = []
        for _ in range(samples):
            values.append(self.read_raw())
            sleep(pause_s)
        return int(statistics.median(values))

    def close(self) -> None:
        self.spi.close()


class SoilMoisture:
    def __init__(self, adc: Mcp3008, dry_raw: float, wet_raw: float):
        if dry_raw == wet_raw:
            raise ConfigError("SOIL_DRY_RAW y SOIL_WET_RAW no pueden ser iguales; vuelve a calibrar.")
        self.adc, self.dry_raw, self.wet_raw = adc, dry_raw, wet_raw

    def read(self) -> dict[str, float]:
        raw = self.adc.median_raw()
        if raw in (0, ADC_MAX):
            log.warning("The soil sensor reads %d, the end of the ADC's range: check its wiring", raw)
        return {"soil_moisture": soil_percent(raw, self.dry_raw, self.wet_raw)}


class HardwareSensors:
    """The measure() of the simulated sensors, read from the real ones."""

    def __init__(self, dht: Dht22, soil: SoilMoisture):
        self.dht, self.soil = dht, soil

    def measure(self) -> dict[str, float]:
        """Whatever was read; a sensor that fails is left out, never sent empty."""
        values = {}
        try:
            values.update(self.soil.read())
        except OSError as error:
            log.error("The soil sensor could not be read (%s): this measurement goes without it", error)
        values.update(self.dht.read())
        return values

    def close(self) -> None:
        self.dht.close()
        self.soil.adc.close()
