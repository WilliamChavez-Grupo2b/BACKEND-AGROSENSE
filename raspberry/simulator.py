import argparse
import json
import logging
import os
import random
import sqlite3
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import requests

import hardware
from hardware import ConfigError

log = logging.getLogger("agrosense.raspberry")

DEFAULT_CONFIG_FILE = "simulator.env"

SIMULATED = {
    "soil_moisture": (55.0, 20.0, 90.0, 1.5, 1),
    "air_temperature": (22.0, 8.0, 36.0, 0.4, 1),
    "relative_humidity": (70.0, 30.0, 98.0, 1.0, 1),
    "ph": (6.4, 5.0, 8.0, 0.05, 2),
}
SENSOR_MODES = ("auto", "hardware", "simulated")

SENT, RETRY, DROP, NOTHING = "sent", "retry", "drop", "nothing"


@dataclass
class Config:
    events_url: str = "http://localhost:8100/events"
    device_id: str = "RPI-DEMO-01"
    device_key: str = field(default="", repr=False)
    interval_s: float = 30.0
    readings: tuple[str, ...] = ("soil_moisture", "air_temperature", "relative_humidity")
    queue_file: str = "queue.sqlite3"
    max_queue: int = 10000
    # Longer than the event service may take: one full wait for the backend (30 s) and its quick retries.
    timeout_s: float = 45.0
    attempts: int = 4
    first_backoff_s: float = 1.0
    max_backoff_s: float = 60.0
    sensors: str = "auto"
    dht_gpio: int = 4
    dht_attempts: int = 3
    adc_spi_bus: int = 0
    adc_spi_device: int = 0
    adc_channel: int = 0
    soil_dry_raw: float | None = None
    soil_wet_raw: float | None = None


def read_config_file(path: Path) -> dict:
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def load_config(path: str | None = None, environ: dict | None = None) -> Config:
    env = dict(os.environ if environ is None else environ)
    file = Path(path or DEFAULT_CONFIG_FILE)
    values = read_config_file(file) if file.is_file() else {}
    if path and not file.is_file():
        raise ConfigError(f"No existe el archivo de configuración {file}")
    values.update({key: value for key, value in env.items() if value})

    def get(name, default):
        return values.get(name, default)

    def optional_number(name):
        value = get(name, "")
        return float(value) if value != "" else None

    try:
        config = Config(
            events_url=get("EVENTS_URL", Config.events_url),
            device_id=get("DEVICE_ID", Config.device_id),
            device_key=get("DEVICE_KEY", ""),
            interval_s=float(get("INTERVAL_S", Config.interval_s)),
            readings=tuple(name.strip() for name in get("READINGS", ",".join(Config.readings)).split(",")
                           if name.strip()),
            queue_file=get("QUEUE_FILE", Config.queue_file),
            max_queue=int(get("MAX_QUEUE", Config.max_queue)),
            timeout_s=float(get("TIMEOUT_S", Config.timeout_s)),
            attempts=int(get("ATTEMPTS", Config.attempts)),
            first_backoff_s=float(get("FIRST_BACKOFF_S", Config.first_backoff_s)),
            max_backoff_s=float(get("MAX_BACKOFF_S", Config.max_backoff_s)),
            sensors=get("SENSORS", Config.sensors).strip().lower(),
            dht_gpio=int(get("DHT_GPIO", Config.dht_gpio)),
            dht_attempts=int(get("DHT_ATTEMPTS", Config.dht_attempts)),
            adc_spi_bus=int(get("ADC_SPI_BUS", Config.adc_spi_bus)),
            adc_spi_device=int(get("ADC_SPI_DEVICE", Config.adc_spi_device)),
            adc_channel=int(get("ADC_CHANNEL", Config.adc_channel)),
            soil_dry_raw=optional_number("SOIL_DRY_RAW"),
            soil_wet_raw=optional_number("SOIL_WET_RAW"),
        )
    except ValueError as error:
        raise ConfigError(f"Un valor numérico de la configuración no es válido: {error}") from None
    if not config.device_key:
        raise ConfigError("Falta DEVICE_KEY: la llave que devolvió el alta del dispositivo (POST /api/devices).")
    if config.sensors not in SENSOR_MODES:
        raise ConfigError(f"SENSORS debe ser {', '.join(SENSOR_MODES)}.")
    if config.interval_s <= 0:
        raise ConfigError("INTERVAL_S debe ser mayor que 0.")
    return config


class Sensors:
    def __init__(self, names: tuple[str, ...], rng: random.Random | None = None):
        self.rng = rng or random.Random()
        unknown = [name for name in names if name not in SIMULATED]
        if unknown:
            raise ConfigError(f"Sin simulación para {', '.join(unknown)}. Usa: {', '.join(SIMULATED)}")
        self.values = {name: SIMULATED[name][0] for name in names}

    def measure(self) -> dict[str, float]:
        for name, value in self.values.items():
            _, low, high, step, digits = SIMULATED[name]
            self.values[name] = round(min(high, max(low, value + self.rng.uniform(-step, step))), digits)
        return dict(self.values)

    def close(self) -> None:
        pass


def choose_sensors(config: Config, model_file: Path = hardware.PI_MODEL_FILE):
    """The real sensors on a Raspberry Pi that has them; the simulated ones on any other machine."""
    if config.sensors == "simulated":
        log.info("Simulated sensors (SENSORS=simulated): %s", ", ".join(config.readings))
        return Sensors(config.readings)
    model = hardware.raspberry_pi_model(model_file)
    if not model:
        if config.sensors == "hardware":
            raise ConfigError("SENSORS=hardware, pero esto no es una Raspberry Pi.")
        log.info("Not a Raspberry Pi: simulated sensors (%s)", ", ".join(config.readings))
        return Sensors(config.readings)
    # On a Raspberry Pi, simulating because a library is missing would send invented values as the farm's.
    missing = hardware.missing_hardware(config.adc_spi_bus, config.adc_spi_device)
    if missing:
        raise ConfigError(f"{model}: faltan {', '.join(missing)} para leer los sensores. Instálalo (README.md, "
                          "«En la Raspberry Pi») o, para probar sin sensores, pon SENSORS=simulated.")
    if config.soil_dry_raw is None or config.soil_wet_raw is None:
        raise ConfigError("Faltan SOIL_DRY_RAW y SOIL_WET_RAW: ejecuta calibrate_soil.py y copia sus valores "
                          "en simulator.env.")
    log.info("%s: DHT22 on GPIO%d, MCP3008 on SPI%d CE%d channel %d (dry %g, wet %g)", model, config.dht_gpio,
             config.adc_spi_bus, config.adc_spi_device, config.adc_channel, config.soil_dry_raw, config.soil_wet_raw)
    adc = hardware.Mcp3008(config.adc_spi_bus, config.adc_spi_device, config.adc_channel)
    return hardware.HardwareSensors(hardware.Dht22(config.dht_gpio, config.dht_attempts),
                                    hardware.SoilMoisture(adc, config.soil_dry_raw, config.soil_wet_raw))


class SqliteQueue:
    """Every event waits here until the service takes it or refuses it, so a power cut loses none."""

    def __init__(self, path: str, limit: int):
        self.path = Path(path)
        self.limit = limit
        self.db = sqlite3.connect(self.path, isolation_level=None)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL)")

    def add(self, event: dict) -> int:
        with self.db:
            self.db.execute("BEGIN IMMEDIATE")
            event_id = self.db.execute("INSERT INTO events (body) VALUES (?)",
                                       (json.dumps(event, ensure_ascii=False),)).lastrowid
            over = len(self) - self.limit
            if over > 0:
                log.warning("The queue went over %s events: dropping the %s oldest", self.limit, over)
                self.db.execute("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY id LIMIT ?)", (over,))
        return event_id

    def oldest(self) -> tuple[int, dict] | None:
        while True:
            row = self.db.execute("SELECT id, body FROM events ORDER BY id LIMIT 1").fetchone()
            if row is None:
                return None
            try:
                return row[0], json.loads(row[1])
            except ValueError:
                log.warning("Damaged event in the queue, dropped")
                self.remove(row[0])

    def remove(self, event_id: int) -> None:
        self.db.execute("DELETE FROM events WHERE id = ?", (event_id,))

    def __len__(self) -> int:
        return self.db.execute("SELECT COUNT(*) FROM events").fetchone()[0]

    def load(self) -> list[dict]:
        return [json.loads(body) for (body,) in self.db.execute("SELECT body FROM events ORDER BY id")]

    def import_jsonl(self, path: Path) -> int:
        """Moves the events of the earlier queue file (one JSON per line) into this one, oldest first."""
        events = []
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                events.append(json.loads(line))
            except ValueError:
                log.warning("Damaged line in %s, dropped", path)
        for event in events:
            self.add(event)
        path.unlink()
        return len(events)

    def close(self) -> None:
        self.db.close()


def open_queue(config: Config) -> SqliteQueue:
    path = Path(config.queue_file)
    earlier = path if path.suffix == ".jsonl" else Path("queue.jsonl")
    if path.suffix == ".jsonl":
        path = path.with_suffix(".sqlite3")
        log.warning("QUEUE_FILE names the earlier text queue; the queue is now %s", path)
    queue = SqliteQueue(str(path), config.max_queue)
    if earlier.is_file():
        log.info("Moved %d waiting events from %s into %s", queue.import_jsonl(earlier), earlier, path)
    return queue


class SingleInstance:
    """Holds a lock beside the queue: two programs sending the same queue would send each event twice."""

    def __init__(self, queue_file: str):
        self.file = open(Path(queue_file).with_suffix(".lock"), "a+")
        try:
            if os.name == "nt":
                import msvcrt
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise ConfigError("Ya hay otro programa del dispositivo usando esta cola (¿el servicio agrosense?). "
                              "Páralo antes: sudo systemctl stop agrosense") from None

    def release(self) -> None:
        self.file.close()


def send(session: requests.Session, config: Config, event: dict) -> str:
    try:
        answer = session.post(config.events_url, json=event, headers={"X-Device-Key": config.device_key},
                              timeout=config.timeout_s)
    except requests.RequestException as error:
        log.warning("No connection to the event service (%s)", type(error).__name__)
        return RETRY
    if answer.status_code == 200:
        return SENT
    message = _message_of(answer)
    if answer.status_code in (408, 429) or answer.status_code >= 500:
        log.warning("The service answered %s: %s", answer.status_code, message)
        return RETRY
    log.error("Event rejected (%s): %s", answer.status_code, message)
    return DROP


def backoff(config: Config, attempt: int) -> float:
    return min(config.first_backoff_s * 2 ** attempt, config.max_backoff_s)


def flush(session: requests.Session, config: Config, queue: SqliteQueue, deadline: float, sleep=time.sleep,
          clock=time.monotonic, rng: random.Random | None = None) -> dict[int, str]:
    """Sends the queue oldest first, one event at a time, until it is empty, the service fails `attempts`
    times in a row or the cycle's time is up. Returns what happened to each event it sent."""
    rng = rng or random.Random()
    outcomes = {}
    failures = 0
    while (head := queue.oldest()) is not None:
        event_id, event = head
        outcome = send(session, config, event)
        if outcome == RETRY:
            wait = backoff(config, failures) * rng.uniform(0.8, 1.2)
            failures += 1
            if failures >= config.attempts or clock() + wait >= deadline:
                break
            sleep(wait)
            continue
        queue.remove(event_id)
        outcomes[event_id] = outcome
        failures = 0
        if clock() >= deadline:
            break
    return outcomes


def reading_event(config: Config, values: dict[str, float]) -> dict:
    return {"device_id": config.device_id, "type": "reading",
            "ts": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"), "data": values}


def run_once(session: requests.Session, config: Config, sensors, queue: SqliteQueue, sleep=time.sleep,
             clock=time.monotonic, deadline: float | None = None) -> str:
    """One cycle: measure, keep the event in the queue, send what waits. Ends by `deadline`, unless a single
    send is still waiting for its answer."""
    deadline = clock() + config.interval_s if deadline is None else deadline
    values = sensors.measure()
    if not values:
        log.error("No sensor could be read: nothing is sent for this measurement")
        event_id = None
    else:
        event_id = queue.add(reading_event(config, values))
    waiting_before = len(queue) - (event_id is not None)
    outcomes = flush(session, config, queue, deadline, sleep, clock)
    sent_from_before = sum(1 for key, outcome in outcomes.items() if key != event_id and outcome == SENT)
    if sent_from_before:
        log.info("Sent %s of the %s events that were waiting in the queue", sent_from_before, waiting_before)
    if event_id is None:
        return NOTHING
    outcome = outcomes.get(event_id, RETRY)
    if outcome == RETRY:
        log.info("Event kept in the queue (%s waiting)", len(queue))
    elif outcome == SENT:
        log.info("Sent: %s", values)
    return outcome


def run_forever(session: requests.Session, config: Config, sensors, queue: SqliteQueue, sleep=time.sleep,
                clock=time.monotonic, cycles: int | None = None) -> None:
    """A cycle every INTERVAL_S on a fixed schedule. A cycle that runs late skips the measurements it ran
    over instead of starting them together, so two sends never overlap and the queue keeps its order."""
    next_tick = clock()
    done = 0
    while cycles is None or done < cycles:
        run_once(session, config, sensors, queue, sleep, clock, deadline=next_tick + config.interval_s)
        done += 1
        next_tick += config.interval_s
        now = clock()
        if now > next_tick:
            skipped = int((now - next_tick) // config.interval_s) + 1
            log.warning("The cycle took %.0f s, longer than INTERVAL_S: %d measurement(s) skipped",
                        now - next_tick + config.interval_s, skipped)
            next_tick += skipped * config.interval_s
        if cycles is None or done < cycles:
            sleep(next_tick - now)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Dispositivo de campo de AgroSense: sensores reales en una "
                                                 "Raspberry Pi, simulados en cualquier otro equipo.")
    parser.add_argument("--config", help=f"archivo KEY=VALUE (por defecto {DEFAULT_CONFIG_FILE}, si existe)")
    parser.add_argument("--once", action="store_true", help="una sola medición")
    args = parser.parse_args(argv)
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO").upper(),
                        format="%(asctime)s %(levelname)s %(message)s")
    try:
        config = load_config(args.config)
        lock = SingleInstance(config.queue_file)
        sensors = choose_sensors(config)
    except ConfigError as error:
        log.error("%s", error.code)
        raise SystemExit(2) from None
    log.info("Device %s sending to %s every %s s", config.device_id, config.events_url, config.interval_s)
    queue = open_queue(config)
    try:
        with requests.Session() as session:
            run_forever(session, config, sensors, queue, cycles=1 if args.once else None)
    except KeyboardInterrupt:
        log.info("Stopped")
    finally:
        sensors.close()
        queue.close()
        lock.release()


def _message_of(answer: requests.Response) -> str:
    try:
        body = answer.json()
        return str(body.get("message", "")) if isinstance(body, dict) else ""
    except ValueError:
        return answer.text[:200]


if __name__ == "__main__":
    main(sys.argv[1:])
