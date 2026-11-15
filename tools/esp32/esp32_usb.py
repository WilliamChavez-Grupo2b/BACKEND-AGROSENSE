"""Puente USB para run-demo.ps1 -Usb: lee el ESP32 REAL por su puerto serie y lo sirve por TCP como lo hace su Wi-Fi.

Sirve cuando el PC no puede unirse a la red PLANTICAS. El sketch (firmware/esp32-tcp/planticas_proyecto) imprime por el
puerto serie, a 115200 baudios, las mismas variables que envía por TCP:

    Temperatura exterior: 21.25
    Humedad relativa: 40.00
    Humedad de la tierra: 28.12
    ------------------------------------------

Este puente guarda la última medición completa y, como el ESP32, atiende un solo cliente en 127.0.0.1 y cada segundo le
escribe el bloque con el formato exacto del Wi-Fi (println: \\r\\n; dos decimales; línea vacía al final). Los valores son
los de la placa, sin tocar: el backend les aplica SENSOR_CALIBRATION igual que por Wi-Fi. Si la placa deja de medir
durante STALE_S segundos, el puente deja de enviar, así el backend ve que no hay datos en vez de repetir el último.
Necesita pyserial (pip install pyserial). Abrir el puerto no reinicia la placa: DTR y RTS quedan apagados.
"""
import argparse
import re
import socket
import sys
import threading
import time

STALE_S = 15.0
LINE = re.compile(r"^\s*(Temperatura exterior|Humedad relativa|Humedad de la tierra|Humedad del suelo)\s*:\s*(-?\d+(?:\.\d+)?)")
FIELDS = {
    "Temperatura exterior": "temperature",
    "Humedad relativa": "humidity",
    "Humedad de la tierra": "soil",
    "Humedad del suelo": "soil",
}


class Reading:
    """The three values of the board; complete once each has been read at least once."""

    def __init__(self) -> None:
        self.values: dict[str, float] = {}
        self.at = 0.0

    def feed(self, line: str, now: float) -> bool:
        """Takes one serial line; True when it carried a value."""
        match = LINE.match(line)
        if not match:
            return False
        self.values[FIELDS[match.group(1)]] = float(match.group(2))
        self.at = now
        return True

    def complete(self) -> bool:
        return all(key in self.values for key in ("temperature", "humidity", "soil"))

    def fresh(self, now: float) -> bool:
        return self.complete() and now - self.at <= STALE_S


def block(reading: Reading) -> bytes:
    values = reading.values
    return ("===== PLANTICAS =====\r\n"
            f"Temperatura exterior: {values['temperature']:.2f} C\r\n"
            f"Humedad relativa: {values['humidity']:.2f} %\r\n"
            f"Humedad del suelo: {values['soil']:.2f} %\r\n"
            "\r\n").encode("ascii")


def read_serial(port: str, baud: int, reading: Reading, lock: threading.Lock) -> None:
    import serial  # pyserial; imported here so the tests need no board and no library

    while True:
        try:
            device = serial.Serial()
            device.port, device.baudrate, device.timeout = port, baud, 1
            device.dtr = False
            device.rts = False
            device.open()
            print(f"Leyendo el ESP32 por {port} a {baud} baudios (DATOS REALES POR USB)", flush=True)
            with device:
                while True:
                    line = device.readline().decode("utf-8", errors="replace")
                    if line:
                        with lock:
                            reading.feed(line, time.monotonic())
        except Exception as failure:  # unplugged, busy (PuTTY, Arduino IDE) or not there: retry
            print(f"No se pudo leer {port}: {failure}. Reintento en 3 s", flush=True)
            time.sleep(3)


def serve(client: socket.socket, reading: Reading, lock: threading.Lock) -> None:
    with client:
        while True:
            with lock:
                data = block(reading) if reading.fresh(time.monotonic()) else None
            if data:
                client.sendall(data)
            time.sleep(1.0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", default="COM3", help="Puerto serie del ESP32 (COM3, /dev/ttyUSB0...)")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    reading, lock = Reading(), threading.Lock()
    threading.Thread(target=read_serial, args=(args.serial, args.baud, reading, lock), daemon=True).start()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(1)
    print(f"Puente USB del ESP32 escuchando en {args.host}:{args.port}", flush=True)
    while True:
        client, address = server.accept()
        print(f"Cliente TCP conectado: {address[0]}:{address[1]}", flush=True)
        try:
            serve(client, reading, lock)
        except OSError:
            print("Cliente TCP desconectado", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
