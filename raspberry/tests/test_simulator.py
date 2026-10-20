import json
import random
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
import requests

import hardware
import simulator
from hardware import ConfigError
from simulator import DROP, NOTHING, RETRY, SENT, Config, Sensors, SqliteQueue

KEY = "device-key"


class FakeService:
    def __init__(self):
        self.received: list[dict] = []
        self.keys: list[str] = []
        self.statuses: list[int] = [200]
        self.on_request = None
        service = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if service.on_request:
                    service.on_request()
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


class FixedSensors:
    def __init__(self, *readings):
        self.readings = list(readings)

    def measure(self):
        return self.readings.pop(0) if len(self.readings) > 1 else self.readings[0]

    def close(self):
        pass


class Clock:
    """A clock that only moves when the program sleeps or a request takes time."""

    def __init__(self):
        self.now = 1000.0
        self.sleeps: list[float] = []

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += max(0.0, seconds)


@pytest.fixture
def service():
    fake = FakeService()
    yield fake
    fake.close()


@pytest.fixture
def config(service, tmp_path):
    return Config(events_url=service.url, device_key=KEY, queue_file=str(tmp_path / "queue.sqlite3"), timeout_s=2,
                  attempts=3, first_backoff_s=0.01, max_backoff_s=0.05)


@pytest.fixture
def queue(config):
    opened = SqliteQueue(config.queue_file, config.max_queue)
    yield opened
    opened.close()


def run(config, queue, sensors=None, sleep=lambda seconds: None):
    with requests.Session() as session:
        return simulator.run_once(session, config, sensors or Sensors(config.readings, random.Random(1)), queue, sleep)


def test_a_measurement_is_sent_with_the_devices_key_and_its_time(service, config, queue):
    assert run(config, queue) == SENT

    event = service.received[0]
    assert service.keys == [KEY]
    assert event["device_id"] == "RPI-DEMO-01"
    assert event["type"] == "reading"
    assert set(event["data"]) == {"soil_moisture", "air_temperature", "relative_humidity"}
    assert event["ts"][-6] in "+-"
    assert len(queue) == 0


def test_a_service_that_fails_for_a_moment_is_tried_again_with_growing_waits(service, config, queue):
    service.statuses = [502, 503, 200]
    waits = []

    assert run(config, queue, sleep=waits.append) == SENT
    assert len(service.received) == 1
    assert len(waits) == 2 and waits[0] < waits[1]


def test_without_connection_the_event_waits_in_the_queue_and_goes_out_first_when_it_returns(service, config, queue):
    down = Config(**{**config.__dict__, "events_url": "http://127.0.0.1:9/events"})

    assert run(down, queue) == RETRY
    assert run(down, queue) == RETRY
    waiting = queue.load()
    assert len(waiting) == 2

    assert run(config, queue) == SENT
    assert [event["ts"] for event in service.received[:2]] == [event["ts"] for event in waiting]
    assert len(service.received) == 3
    assert len(queue) == 0


def test_an_event_the_service_refuses_is_dropped_and_not_retried(service, config, queue):
    service.statuses = [400]

    assert run(config, queue) == DROP
    assert len(service.keys) == 1
    assert len(queue) == 0


def test_no_value_means_no_event_and_a_sensor_that_failed_is_left_out(service, config, queue):
    assert run(config, queue, FixedSensors({})) == NOTHING
    assert service.keys == []
    assert len(queue) == 0

    assert run(config, queue, FixedSensors({"soil_moisture": 41.0})) == SENT
    assert service.received[0]["data"] == {"soil_moisture": 41.0}


def test_the_queue_keeps_only_the_newest_events_beyond_its_limit(tmp_path):
    queue = SqliteQueue(str(tmp_path / "q.sqlite3"), limit=3)
    for number in range(5):
        queue.add({"n": number})

    assert [event["n"] for event in queue.load()] == [2, 3, 4]
    queue.close()


def test_the_queue_survives_a_restart_and_drops_a_damaged_event(tmp_path):
    path = str(tmp_path / "q.sqlite3")
    queue = SqliteQueue(path, limit=10)
    queue.add({"n": 1})
    queue.db.execute("INSERT INTO events (body) VALUES ('{no es json')")
    queue.add({"n": 2})
    queue.close()

    reopened = SqliteQueue(path, limit=10)
    first = reopened.oldest()
    reopened.remove(first[0])

    assert first[1] == {"n": 1}
    assert reopened.oldest()[1] == {"n": 2}
    assert len(reopened) == 1
    reopened.close()


def test_the_events_of_the_earlier_text_queue_move_into_sqlite_in_their_order(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    (tmp_path / "queue.jsonl").write_text('{"n": 1}\n{roto\n{"n": 2}\n', encoding="utf-8")

    queue = simulator.open_queue(Config(device_key=KEY, queue_file="queue.jsonl"))

    assert queue.path.name == "queue.sqlite3"
    assert [event["n"] for event in queue.load()] == [1, 2]
    assert not (tmp_path / "queue.jsonl").exists()
    queue.close()


def test_a_cycle_stops_sending_the_queue_when_its_time_is_up(service, config, queue):
    clock = Clock()
    for number in range(5):
        queue.add({"device_id": "RPI-DEMO-01", "type": "reading", "data": {"soil_moisture": number}})
    service.on_request = lambda: setattr(clock, "now", clock.now + 10)

    with requests.Session() as session:
        outcomes = simulator.flush(session, config, queue, deadline=clock() + 25, sleep=clock.sleep, clock=clock)

    assert list(outcomes.values()) == [SENT, SENT, SENT]
    assert len(queue) == 2


def test_cycles_follow_a_fixed_schedule_and_a_slow_one_skips_measurements_instead_of_overlapping(service, config,
                                                                                                    queue):
    clock = Clock()
    every_30 = Config(**{**config.__dict__, "interval_s": 30})
    measured_at = []

    class Timed(FixedSensors):
        def measure(self):
            measured_at.append(clock() - 1000)
            return super().measure()

    durations = iter([5, 70, 5, 5])
    service.on_request = lambda: setattr(clock, "now", clock.now + next(durations))

    with requests.Session() as session:
        simulator.run_forever(session, every_30, Timed({"soil_moisture": 40.0}), queue, sleep=clock.sleep,
                              clock=clock, cycles=4)

    # 0 s sends in 5 s; 30 s takes 70 s and ends at 100 s, so 60 s and 90 s are skipped; then 120 s and 150 s.
    assert measured_at == [0, 30, 120, 150]
    assert len(service.received) == 4


def test_the_wait_doubles_up_to_its_limit():
    config = Config(device_key=KEY, first_backoff_s=1, max_backoff_s=5)

    assert [simulator.backoff(config, attempt) for attempt in range(5)] == [1, 2, 4, 5, 5]


def test_simulated_readings_stay_within_their_range():
    sensors = Sensors(("soil_moisture", "relative_humidity", "ph"), random.Random(7))
    for _ in range(2000):
        values = sensors.measure()
        assert 20 <= values["soil_moisture"] <= 90
        assert 30 <= values["relative_humidity"] <= 98
        assert 5 <= values["ph"] <= 8


def test_settings_come_from_a_file_and_the_environment_wins(tmp_path):
    file = tmp_path / "device.env"
    file.write_text("# comentario\nEVENTS_URL=http://pi.local:8100/events\nDEVICE_KEY='del-archivo'\nINTERVAL_S=60\n"
                    "SOIL_DRY_RAW=820\nSOIL_WET_RAW=410\nSOIL_EMPTY=\n", encoding="utf-8")

    config = simulator.load_config(str(file), {"DEVICE_KEY": "from-the-environment", "READINGS": "ph, soil_moisture",
                                               "SENSORS": "Simulated"})

    assert config.events_url == "http://pi.local:8100/events"
    assert config.device_key == "from-the-environment"
    assert config.interval_s == 60
    assert config.readings == ("ph", "soil_moisture")
    assert (config.soil_dry_raw, config.soil_wet_raw, config.sensors) == (820, 410, "simulated")
    assert "from-the-environment" not in repr(config)

    with pytest.raises(SystemExit):
        simulator.load_config(str(tmp_path / "missing.env"), {})
    without_key = tmp_path / "no-key.env"
    without_key.write_text("EVENTS_URL=http://x\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        simulator.load_config(str(without_key), {})
    for wrong in ({"SENSORS": "real"}, {"INTERVAL_S": "0"}, {"SOIL_DRY_RAW": "seco"}):
        with pytest.raises(ConfigError):
            simulator.load_config(str(without_key), {"DEVICE_KEY": KEY, **wrong})


def test_the_defaults_measure_every_30_s_and_wait_45_s_for_an_answer(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    config = simulator.load_config(None, {"DEVICE_KEY": KEY})

    assert (config.interval_s, config.timeout_s, config.sensors) == (30, 45, "auto")
    assert (config.dht_gpio, config.adc_spi_bus, config.adc_spi_device, config.adc_channel) == (4, 0, 0, 0)
    assert config.queue_file == "queue.sqlite3"


def test_a_second_program_on_the_same_queue_is_refused(tmp_path):
    first = simulator.SingleInstance(str(tmp_path / "queue.sqlite3"))
    try:
        with pytest.raises(ConfigError):
            simulator.SingleInstance(str(tmp_path / "queue.sqlite3"))
    finally:
        first.release()
    simulator.SingleInstance(str(tmp_path / "queue.sqlite3")).release()


def test_a_setting_to_fix_exits_with_status_2_so_systemd_does_not_restart_it(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    monkeypatch.delenv("DEVICE_KEY", raising=False)

    with pytest.raises(SystemExit) as stopped:
        simulator.main([])

    assert stopped.value.code == 2


def pi_model(tmp_path, text="Raspberry Pi 4 Model B Rev 1.5\x00"):
    model = tmp_path / "model"
    model.write_text(text, encoding="utf-8")
    return model


def test_a_machine_that_is_not_a_raspberry_pi_simulates(tmp_path):
    config = Config(device_key=KEY)

    assert isinstance(simulator.choose_sensors(config, tmp_path / "no-model"), Sensors)
    assert isinstance(simulator.choose_sensors(config, pi_model(tmp_path, "Some PC")), Sensors)
    with pytest.raises(ConfigError):
        simulator.choose_sensors(Config(device_key=KEY, sensors="hardware"), tmp_path / "no-model")


def test_a_raspberry_pi_never_simulates_on_its_own(tmp_path, monkeypatch):
    model = pi_model(tmp_path)
    monkeypatch.setattr(hardware, "missing_hardware", lambda bus, device: ["la biblioteca spidev"])

    with pytest.raises(ConfigError) as refused:
        simulator.choose_sensors(Config(device_key=KEY), model)
    assert "spidev" in refused.value.code and "SENSORS=simulated" in refused.value.code
    assert isinstance(simulator.choose_sensors(Config(device_key=KEY, sensors="simulated"), model), Sensors)

    monkeypatch.setattr(hardware, "missing_hardware", lambda bus, device: [])
    with pytest.raises(ConfigError) as uncalibrated:
        simulator.choose_sensors(Config(device_key=KEY), model)
    assert "calibrate_soil.py" in uncalibrated.value.code


class FakeDht:
    def __init__(self, *answers):
        self.answers = list(answers)
        self.closed = False

    @property
    def temperature(self):
        answer = self.answers[0]
        if isinstance(answer, Exception):
            self.answers.pop(0)
            raise answer
        return answer[0]

    @property
    def humidity(self):
        return self.answers.pop(0)[1]

    def exit(self):
        self.closed = True


def test_the_dht22_is_read_again_after_a_misread_and_waits_two_seconds_between_reads():
    waits = []
    dht = hardware.Dht22(4, attempts=3, sleep=waits.append,
                         device=FakeDht(RuntimeError("Checksum did not validate"), (None, 61.2), (24.46, 60.04)))

    assert dht.read() == {"air_temperature": 24.5, "relative_humidity": 60.0}
    assert waits == [2.0, 2.0]


def test_a_dht22_that_never_answers_or_answers_nonsense_gives_no_values():
    assert hardware.Dht22(4, attempts=2, sleep=lambda s: None,
                          device=FakeDht(RuntimeError("a"), RuntimeError("b"))).read() == {}
    assert hardware.Dht22(4, attempts=2, sleep=lambda s: None,
                          device=FakeDht((25.0, 140.0), (float("nan"), 60.0))).read() == {}


class FakeSpi:
    def __init__(self, *values):
        self.values = list(values)
        self.sent = []

    def xfer2(self, frame):
        self.sent.append(frame)
        value = self.values.pop(0) if len(self.values) > 1 else self.values[0]
        return [0, (value >> 8) & 3, value & 0xFF]

    def close(self):
        pass


def test_the_mcp3008_asks_for_its_channel_and_the_reading_is_the_median():
    spi = FakeSpi(700, 702, 1023, 699, 701)
    adc = hardware.Mcp3008(0, 0, channel=3, spi=spi)

    assert adc.read_raw() == 700
    assert spi.sent[0] == [1, (8 + 3) << 4, 0]
    assert adc.median_raw(samples=4, sleep=lambda s: None) == 701
    with pytest.raises(ConfigError):
        hardware.Mcp3008(0, 0, channel=8, spi=spi)


def test_the_soil_percentage_follows_the_calibration_whichever_way_the_sensor_counts():
    assert hardware.soil_percent(820, dry_raw=820, wet_raw=410) == 0
    assert hardware.soil_percent(410, dry_raw=820, wet_raw=410) == 100
    assert hardware.soil_percent(615, dry_raw=820, wet_raw=410) == 50
    assert hardware.soil_percent(300, dry_raw=100, wet_raw=500) == 50
    assert hardware.soil_percent(900, dry_raw=820, wet_raw=410) == 0
    assert hardware.soil_percent(200, dry_raw=820, wet_raw=410) == 100
    with pytest.raises(ConfigError):
        hardware.SoilMoisture(hardware.Mcp3008(0, 0, 0, spi=FakeSpi(500)), 500, 500)


def test_the_real_sensors_send_what_they_read_and_leave_out_a_sensor_that_fails():
    soil = hardware.SoilMoisture(hardware.Mcp3008(0, 0, 0, spi=FakeSpi(615)), 820, 410)
    soil.adc.median_raw = lambda: 615
    sensors = hardware.HardwareSensors(hardware.Dht22(4, 1, device=FakeDht((24.5, 60.0))), soil)

    assert sensors.measure() == {"soil_moisture": 50.0, "air_temperature": 24.5, "relative_humidity": 60.0}

    def broken():
        raise OSError("SPI")
    soil.adc.median_raw = broken
    sensors.dht = hardware.Dht22(4, 1, device=FakeDht((24.5, 60.0)))
    assert sensors.measure() == {"air_temperature": 24.5, "relative_humidity": 60.0}
