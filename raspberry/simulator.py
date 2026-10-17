"""AgroSense field device: measures every few seconds and sends the readings to the event service.

On a test PC it simulates the sensors; on a Raspberry Pi, replace ``measure`` with the code that reads yours.
Events that cannot be sent (no wifi, service asleep) wait in a file on disk and go out, oldest first, as soon
as the service answers again. Each event carries the time it was measured, so a late one keeps its time.

    python simulator.py                    # every INTERVAL_S seconds, until Ctrl+C
    python simulator.py --once             # a single measurement
    python simulator.py --config my.env    # settings from a file (environment variables win)
"""

import argparse
import json
import logging
import os
import random
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import requests

log = logging.getLogger("agrosense.raspberry")

DEFAULT_CONFIG_FILE = "simulator.env"

# What the simulated sensors start at and the range they wander within.
SIMULATED = {
    "soil_moisture": (55.0, 20.0, 90.0, 1.5, 1),
    "air_temperature": (22.0, 8.0, 36.0, 0.4, 1),
    "ph": (6.4, 5.0, 8.0, 0.05, 2),
}

SENT, RETRY, DROP = "sent", "retry", "drop"


@dataclass
class Config:
    events_url: str = "http://localhost:8100/events"
    device_id: str = "RPI-DEMO-01"
    device_key: str = field(default="", repr=False)
    interval_s: float = 10.0
    readings: tuple[str, ...] = ("soil_moisture", "air_temperature", "ph")
    queue_file: str = "queue.jsonl"
    max_queue: int = 10000
    timeout_s: float = 10.0
    attempts: int = 4
    first_backoff_s: float = 1.0
    max_backoff_s: float = 60.0


def read_config_file(path: Path) -> dict:
    """KEY=VALUE lines; blank lines and those starting with # are ignored."""
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
        raise SystemExit(f"No existe el archivo de configuración {file}")
    values.update({key: value for key, value in env.items() if value})

    def get(name, default):
        return values.get(name, default)

    config = Config(
        events_url=get("EVENTS_URL", Config.events_url),
        device_id=get("DEVICE_ID", Config.device_id),
        device_key=get("DEVICE_KEY", ""),
        interval_s=float(get("INTERVAL_S", Config.interval_s)),
        readings=tuple(name.strip() for name in get("READINGS", ",".join(Config.readings)).split(",") if name.strip()),
        queue_file=get("QUEUE_FILE", Config.queue_file),
        max_queue=int(get("MAX_QUEUE", Config.max_queue)),
        timeout_s=float(get("TIMEOUT_S", Config.timeout_s)),
        attempts=int(get("ATTEMPTS", Config.attempts)),
        first_backoff_s=float(get("FIRST_BACKOFF_S", Config.first_backoff_s)),
        max_backoff_s=float(get("MAX_BACKOFF_S", Config.max_backoff_s)),
    )
    if not config.device_key:
        raise SystemExit("Falta DEVICE_KEY: la llave que devolvió el alta del dispositivo (POST /api/devices).")
    return config


class Sensors:
    """Simulated sensors that drift slowly, like real ones. Replace ``measure`` on a real Raspberry Pi."""

    def __init__(self, names: tuple[str, ...], rng: random.Random | None = None):
        self.rng = rng or random.Random()
        unknown = [name for name in names if name not in SIMULATED]
        if unknown:
            raise SystemExit(f"Sin simulación para {', '.join(unknown)}. Usa: {', '.join(SIMULATED)}")
        self.values = {name: SIMULATED[name][0] for name in names}

    def measure(self) -> dict[str, float]:
        for name, value in self.values.items():
            _, low, high, step, digits = SIMULATED[name]
            self.values[name] = round(min(high, max(low, value + self.rng.uniform(-step, step))), digits)
        return dict(self.values)


class DiskQueue:
    """Events waiting to be sent, one JSON per line. Rewritten whole each time: it stays small."""

    def __init__(self, path: str, limit: int):
        self.path = Path(path)
        self.limit = limit

    def load(self) -> list[dict]:
        if not self.path.is_file():
            return []
        events = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            try:
                events.append(json.loads(line))
            except ValueError:
                log.warning("Damaged line in the queue, dropped")
        return events

    def save(self, events: list[dict]) -> None:
        if len(events) > self.limit:
            log.warning("The queue went over %s events: dropping the %s oldest", self.limit,
                        len(events) - self.limit)
            events = events[-self.limit:]
        if not events:
            self.path.unlink(missing_ok=True)
            return
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text("".join(json.dumps(event, ensure_ascii=False) + "\n" for event in events),
                             encoding="utf-8")
        # Replacing in one step, so a power cut leaves either the old queue or the new one.
        os.replace(temporary, self.path)

    def add(self, event: dict) -> None:
        self.save([*self.load(), event])


def send(session: requests.Session, config: Config, event: dict) -> str:
    """One try: sent, worth trying again later, or refused for a reason retrying will not change."""
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


def deliver(session: requests.Session, config: Config, event: dict, sleep=time.sleep,
            rng: random.Random | None = None) -> str:
    """Tries a few times, waiting longer after each failure (1 s, 2 s, 4 s... up to MAX_BACKOFF_S)."""
    rng = rng or random.Random()
    outcome = RETRY
    for attempt in range(config.attempts):
        outcome = send(session, config, event)
        if outcome != RETRY:
            return outcome
        if attempt + 1 < config.attempts:
            # A little randomness keeps many devices from retrying at the same instant.
            sleep(backoff(config, attempt) * rng.uniform(0.8, 1.2))
    return outcome


def backoff(config: Config, attempt: int) -> float:
    return min(config.first_backoff_s * 2 ** attempt, config.max_backoff_s)


def flush(session: requests.Session, config: Config, queue: DiskQueue) -> int:
    """Sends what is waiting, oldest first; stops at the first one the service cannot take yet."""
    waiting = queue.load()
    sent = 0
    while waiting:
        outcome = send(session, config, waiting[0])
        if outcome == RETRY:
            break
        waiting.pop(0)
        sent += outcome == SENT
    queue.save(waiting)
    return sent


def reading_event(config: Config, values: dict[str, float]) -> dict:
    return {"device_id": config.device_id, "type": "reading",
            "ts": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"), "data": values}


def run_once(session: requests.Session, config: Config, sensors: Sensors, queue: DiskQueue, sleep=time.sleep) -> str:
    if queue.load():
        log.info("Sent %s events that were waiting in the queue", flush(session, config, queue))
    event = reading_event(config, sensors.measure())
    # While older events still wait, the new one waits behind them, so they arrive in order.
    outcome = RETRY if queue.load() else deliver(session, config, event, sleep)
    if outcome == RETRY:
        queue.add(event)
        log.info("Event saved in the queue (%s waiting)", len(queue.load()))
    elif outcome == SENT:
        log.info("Sent: %s", event["data"])
    return outcome


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Dispositivo de campo de AgroSense (simulado).")
    parser.add_argument("--config", help=f"archivo KEY=VALUE (por defecto {DEFAULT_CONFIG_FILE}, si existe)")
    parser.add_argument("--once", action="store_true", help="una sola medición")
    args = parser.parse_args(argv)
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO").upper(),
                        format="%(asctime)s %(levelname)s %(message)s")
    config = load_config(args.config)
    log.info("Device %s sending to %s every %s s", config.device_id, config.events_url, config.interval_s)
    sensors = Sensors(config.readings)
    queue = DiskQueue(config.queue_file, config.max_queue)
    with requests.Session() as session:
        try:
            while True:
                run_once(session, config, sensors, queue)
                if args.once:
                    return
                time.sleep(config.interval_s)
        except KeyboardInterrupt:
            log.info("Stopped")


def _message_of(answer: requests.Response) -> str:
    try:
        body = answer.json()
        return str(body.get("message", "")) if isinstance(body, dict) else ""
    except ValueError:
        return answer.text[:200]


if __name__ == "__main__":
    main(sys.argv[1:])
