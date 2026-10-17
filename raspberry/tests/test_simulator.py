"""The simulator against a local server that plays the event service."""

import json
import random
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
import requests

import simulator
from simulator import DROP, RETRY, SENT, Config, DiskQueue, Sensors

KEY = "device-key"


class FakeService:
    def __init__(self):
        self.received: list[dict] = []
        self.keys: list[str] = []
        self.statuses: list[int] = [200]
        service = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                status = service.statuses.pop(0) if len(service.statuses) > 1 else service.statuses[0]
                if status == 200:
                    service.received.append(body)
                service.keys.append(self.headers.get("X-Device-Key"))
                payload = json.dumps({"status": "stored"} if status == 200 else {"message": "no"}).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, format, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/events"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def service():
    fake = FakeService()
    yield fake
    fake.close()


@pytest.fixture
def config(service, tmp_path):
    return Config(events_url=service.url, device_key=KEY, queue_file=str(tmp_path / "queue.jsonl"), timeout_s=2,
                  attempts=3, first_backoff_s=0.01, max_backoff_s=0.05)


def run(config, sensors=None, sleep=lambda seconds: None):
    with requests.Session() as session:
        return simulator.run_once(session, config, sensors or Sensors(config.readings, random.Random(1)),
                                  DiskQueue(config.queue_file, config.max_queue), sleep)


def test_a_measurement_is_sent_with_the_devices_key_and_its_time(service, config):
    assert run(config) == SENT

    event = service.received[0]
    assert service.keys == [KEY]
    assert event["device_id"] == "RPI-DEMO-01"
    assert event["type"] == "reading"
    assert set(event["data"]) == {"soil_moisture", "air_temperature", "ph"}
    assert event["ts"][-6] in "+-"  # ISO-8601 with the device's offset


def test_a_service_that_fails_for_a_moment_is_tried_again_with_growing_waits(service, config):
    service.statuses = [502, 503, 200]
    waits = []

    assert run(config, sleep=waits.append) == SENT
    assert len(service.received) == 1
    assert len(waits) == 2 and waits[0] < waits[1]


def test_without_connection_the_event_waits_on_disk_and_goes_out_first_when_it_returns(service, config):
    down = Config(**{**config.__dict__, "events_url": "http://127.0.0.1:9/events"})

    assert run(down) == RETRY
    assert run(down) == RETRY
    waiting = DiskQueue(config.queue_file, 100).load()
    assert len(waiting) == 2

    assert run(config) == SENT
    assert [event["ts"] for event in service.received[:2]] == [event["ts"] for event in waiting]
    assert len(service.received) == 3
    assert DiskQueue(config.queue_file, 100).load() == []


def test_an_event_the_service_refuses_is_dropped_and_not_retried(service, config):
    service.statuses = [400]

    assert run(config) == DROP
    assert len(service.keys) == 1
    assert DiskQueue(config.queue_file, 100).load() == []


def test_the_queue_keeps_only_the_newest_events_beyond_its_limit(tmp_path):
    queue = DiskQueue(str(tmp_path / "q.jsonl"), limit=3)
    for number in range(5):
        queue.add({"n": number})

    assert [event["n"] for event in queue.load()] == [2, 3, 4]


def test_the_wait_doubles_up_to_its_limit():
    config = Config(device_key=KEY, first_backoff_s=1, max_backoff_s=5)

    assert [simulator.backoff(config, attempt) for attempt in range(5)] == [1, 2, 4, 5, 5]


def test_simulated_readings_stay_within_their_range():
    sensors = Sensors(("soil_moisture", "ph"), random.Random(7))
    for _ in range(2000):
        values = sensors.measure()
        assert 20 <= values["soil_moisture"] <= 90
        assert 5 <= values["ph"] <= 8


def test_settings_come_from_a_file_and_the_environment_wins(tmp_path):
    file = tmp_path / "device.env"
    file.write_text("# comentario\nEVENTS_URL=http://pi.local:8100/events\nDEVICE_KEY='del-archivo'\nINTERVAL_S=30\n",
                    encoding="utf-8")

    config = simulator.load_config(str(file), {"DEVICE_KEY": "from-the-environment", "READINGS": "ph, soil_moisture"})

    assert config.events_url == "http://pi.local:8100/events"
    assert config.device_key == "from-the-environment"
    assert config.interval_s == 30
    assert config.readings == ("ph", "soil_moisture")
    assert "from-the-environment" not in repr(config)

    with pytest.raises(SystemExit):
        simulator.load_config(str(tmp_path / "missing.env"), {})
    without_key = tmp_path / "no-key.env"
    without_key.write_text("EVENTS_URL=http://x\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        simulator.load_config(str(without_key), {})
