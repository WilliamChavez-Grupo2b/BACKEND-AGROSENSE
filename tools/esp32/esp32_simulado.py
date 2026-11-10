"""ESP32 falso para run-demo.ps1 -Simulado: imita firmware/esp32-tcp/planticas_proyecto.

Escucha en 127.0.0.1 y, como el ESP32 real, atiende un solo cliente a la vez, no lee nada de lo que el cliente envía y
cada segundo escribe el bloque con el mismo formato exacto (println: \\r\\n; dos decimales con punto; línea vacía al final):

    ===== PLANTICAS =====
    Temperatura exterior: 32.50 C
    Humedad relativa: 66.00 %
    Humedad del suelo: 47.30 %

Los valores son crudos, como los del sketch (el backend les aplica SENSOR_CALIBRATION), y cambian despacio: temperatura de
unos 30 a 35 °C y humedad del aire de 60 a 72 %. La humedad del suelo baja poco a poco por debajo del 40 % y vuelve a
subir en ciclos de 10 minutos, así se ve también una alerta de humedad baja. Solo biblioteca estándar.
"""
import argparse
import math
import random
import socket
import sys
import time

CYCLE_S = 600.0


def block(elapsed: float) -> bytes:
    phase = 2 * math.pi * elapsed / CYCLE_S
    temperature = 32.5 + 2.0 * math.sin(phase / 2) + random.uniform(-0.2, 0.2)
    humidity = 66.0 + 5.0 * math.cos(phase / 3) + random.uniform(-0.5, 0.5)
    soil = 45.0 + 10.0 * math.cos(phase) + random.uniform(-0.3, 0.3)
    return ("===== PLANTICAS =====\r\n"
            f"Temperatura exterior: {temperature:.2f} C\r\n"
            f"Humedad relativa: {humidity:.2f} %\r\n"
            f"Humedad del suelo: {soil:.2f} %\r\n"
            "\r\n").encode("ascii")


def serve(client: socket.socket, started: float) -> None:
    with client:
        while True:
            client.sendall(block(time.monotonic() - started))
            time.sleep(1.0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.host, args.port))
    server.listen(1)
    print(f"ESP32 simulado escuchando en {args.host}:{args.port} (DATOS SIMULADOS)", flush=True)
    started = time.monotonic()
    while True:
        client, address = server.accept()
        print(f"Cliente TCP conectado: {address[0]}:{address[1]}", flush=True)
        try:
            serve(client, started)
        except OSError:
            print("Cliente TCP desconectado", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
