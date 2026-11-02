"""The whole road without hardware: event -> event service -> backend -> alert, prediction and irrigation order.

Standard library only. It needs the services running, and fails with a message at the first step that does not hold:

  BACKEND_URL (http://localhost:9000)   EVENTS_URL (http://localhost:8100)
  API_EMAIL (demo@agrosense.co)         API_PASSWORD
  DEVICE_ID (RPI-DEMO-01)               DEVICE_KEY
  CROP_ID (1, the crop of that device)

The backend must run with IRRIGATION_ACTUATOR=relay for the order to reach the device; EXPECT_MODEL=rules also requires
the prediction to come from the AI service and not from the backend's local fallback.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BACKEND = os.environ.get("BACKEND_URL", "http://localhost:9000").rstrip("/")
EVENTS = os.environ.get("EVENTS_URL", "http://localhost:8100").rstrip("/")
EMAIL = os.environ.get("API_EMAIL", "demo@agrosense.co")
PASSWORD = os.environ.get("API_PASSWORD", "")
DEVICE = os.environ.get("DEVICE_ID", "RPI-DEMO-01")
KEY = os.environ.get("DEVICE_KEY", "")
EXPECT_MODEL = os.environ.get("EXPECT_MODEL", "")
LOCAL_MODEL = "agrosense-reglas-locales"
WAIT_S = float(os.environ.get("E2E_WAIT_S", "60"))


def call(url: str, body=None, headers=None, method=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Accept": "application/json", **({} if body is None else {"Content-Type": "application/json"}), **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    try:
        return status, json.loads(raw.decode("utf-8")) if raw else None
    except ValueError:
        return status, None


def step(name: str, ok: bool, detail: object = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {name}{f' - {detail}' if detail != '' else ''}")
    if not ok:
        sys.exit(1)


def wait_for(name: str, check):
    limit = time.monotonic() + WAIT_S
    while time.monotonic() < limit:
        try:
            result = check()
        except OSError:
            result = None
        if result:
            print(f"ok   {name}")
            return result
        time.sleep(1)
    step(name, False, f"nothing after {WAIT_S:g} s")


def event(data: dict, **extra) -> tuple[int, dict]:
    body = {"device_id": DEVICE, "type": "reading", "ts": int(time.time()), "data": data, **extra}
    return call(f"{EVENTS}/events", body, {"X-Device-Key": KEY})


def main() -> None:
    wait_for("the backend answers", lambda: call(f"{BACKEND}/api/health")[0] == 200)
    wait_for("the event service answers", lambda: call(f"{EVENTS}/health")[0] == 200)

    status, session = call(f"{BACKEND}/api/auth/login", {"email": EMAIL, "password": PASSWORD})
    step("sign in", status == 200, status)
    auth = {"Authorization": f"Bearer {session['token']}"}

    status, _ = call(f"{EVENTS}/events", {"device_id": DEVICE, "type": "reading", "data": {"soil_moisture": 30}})
    step("no key: 401", status == 401, status)
    status, _ = call(f"{EVENTS}/events", {"device_id": DEVICE, "type": "reading", "data": {"soil_moisture": 30}},
                     {"X-Device-Key": "wrong"})
    step("wrong key: 403", status == 403, status)
    status, _ = call(f"{EVENTS}/events", {"device_id": DEVICE, "type": "reading", "data": {"nothing": 1}},
                     {"X-Device-Key": KEY})
    step("unknown measure: 400", status == 400, status)

    # The demo device's crop: its id depends on the order the seed happened to insert the crops.
    crops = call(f"{BACKEND}/api/cultivos", headers=auth)[1]
    crop = int(os.environ.get("CROP_ID") or next(item["idCrop"] for item in crops if item["name"] == "Café"))
    seen = call(f"{BACKEND}/api/crops/{crop}/predictions?limit=1", headers=auth)[1]
    before = seen[0]["idPrediction"] if seen else 0

    stamp = int(time.time()) - 60
    first = call(f"{EVENTS}/events", {"device_id": DEVICE, "type": "reading", "ts": stamp,
                                      "data": {"soil_moisture": 12.5}}, {"X-Device-Key": KEY})
    step("a dry reading is stored", first[0] == 200 and first[1]["readings"] == 1, first)
    retry = call(f"{EVENTS}/events", {"device_id": DEVICE, "type": "reading", "ts": stamp,
                                      "data": {"soil_moisture": 12.5}}, {"X-Device-Key": KEY})
    step("the same event again stores nothing", retry[0] == 200 and retry[1]["readings"] == 0, retry)

    status, alerts = call(f"{BACKEND}/api/alerts", headers=auth)
    step("the crop has an open low moisture alert", status == 200 and any(
        alert["alertType"] == "LOW_HUMIDITY" and alert["idCrop"] == crop for alert in alerts), status)

    def prediction():
        answered = call(f"{BACKEND}/api/crops/{crop}/predictions?limit=1", headers=auth)[1]
        return answered[0] if answered and answered[0]["idPrediction"] > before else None
    made = wait_for("the event produced a prediction", prediction)
    print(f"     {made['modelUsed']}: {made['recommendation']}")
    if EXPECT_MODEL == "rules":
        step("it came from the AI service", made["modelUsed"] != LOCAL_MODEL, made["modelUsed"])

    status, _ = call(f"{BACKEND}/api/riego", {"cropId": crop, "waterLiters": 10, "durationMinutes": 3,
                                              "reason": "Prueba de extremo a extremo"}, auth)
    step("an irrigation is registered", status == 201, status)
    status, answer = event({"soil_moisture": 13})
    command = answer.get("command") if isinstance(answer, dict) else None
    step("the next event carries the order", status == 200 and command is not None, answer)
    status, answer = event({"soil_moisture": 13.5}, ack={"command_id": command["id"], "result": "started"})
    step("the order comes only once and the device confirms it", status == 200 and "command" not in answer, answer)
    print("All the road holds.")


if __name__ == "__main__":
    main()
