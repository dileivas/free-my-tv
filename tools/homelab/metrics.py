#!/usr/bin/env python3
"""severino-metrics — JSON de saúde do servidor para o Free My TV (e qualquer outro cliente).

Sem dependências: só a stdlib. Lê /proc, /sys e (opcional) o docker.
Escuta em 0.0.0.0:PORT, pensado para a LAN da casa.

GET /metrics  -> JSON
GET /health   -> {"ok": true}
"""

import json
import os
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "9123"))
NAME = os.environ.get("NAME", "Severino")
DISK_PATH = os.environ.get("DISK_PATH", "/")
TEMP_ZONE = os.environ.get("TEMP_ZONE", "/sys/class/thermal/thermal_zone0/temp")
DISK_DEV = os.environ.get("DISK_DEV", "sda")   # /proc/diskstats: linhas de partições somam com a do disco

_lock = threading.Lock()
_prev = {"at": 0.0, "cpu": None, "io": None}
_docker = {"at": 0.0, "running": -1}


def read(path):
    with open(path, "r") as f:
        return f.read()


def cpu_times():
    parts = read("/proc/stat").splitlines()[0].split()[1:]
    vals = [int(x) for x in parts]
    idle = vals[3] + (vals[4] if len(vals) > 4 else 0)   # idle + iowait
    return sum(vals), idle


def disk_sectors():
    """(setores lidos, setores escritos) do disco principal e suas partições."""
    out = [0, 0]
    for line in read("/proc/diskstats").splitlines():
        f = line.split()
        if len(f) < 10:
            continue
        dev = f[2]
        if dev == DISK_DEV or dev.startswith(DISK_DEV):
            try:
                out[0] += int(f[5])
                out[1] += int(f[9])
            except ValueError:
                pass
    return tuple(out)   # setores de 512 bytes


def meminfo():
    info = {}
    for line in read("/proc/meminfo").splitlines():
        key, _, rest = line.partition(":")
        info[key] = int(rest.split()[0])   # kB
    total = info.get("MemTotal", 0)
    avail = info.get("MemAvailable", info.get("MemFree", 0))
    used = max(0, total - avail)
    return total, used


def temperature():
    try:
        raw = int(read(TEMP_ZONE).strip())
        return round(raw / 1000.0, 1)
    except Exception:
        return None


def containers():
    now = time.time()
    with _lock:
        if now - _docker["at"] < 10:
            return _docker["running"]
    try:
        res = subprocess.run(["docker", "ps", "-q"], capture_output=True, timeout=3, text=True)
        n = len([x for x in res.stdout.split("\n") if x.strip()])
    except Exception:
        n = -1
    with _lock:
        _docker.update(at=now, running=n)
    return n


def snapshot():
    now = time.time()
    total, idle = cpu_times()
    rd, wr = disk_sectors()
    with _lock:
        prev, _prev["at"], _prev["cpu"], _prev["io"] = (
            dict(_prev), now, (total, idle), (rd, wr))
    dt = now - prev["at"]
    cpu_pct = None
    io = None
    if prev["cpu"] and dt > 0:
        d_total = total - prev["cpu"][0]
        d_idle = idle - prev["cpu"][1]
        if d_total > 0:
            cpu_pct = round(100.0 * (d_total - d_idle) / d_total, 1)
    if prev["io"] and dt > 0:
        io = {
            "read_kbs": round((rd - prev["io"][0]) * 512 / 1024 / dt, 1),
            "write_kbs": round((wr - prev["io"][1]) * 512 / 1024 / dt, 1),
        }

    load1 = float(read("/proc/loadavg").split()[0])
    cores = os.cpu_count() or 1
    mem_total, mem_used = meminfo()
    st = os.statvfs(DISK_PATH)
    disk_total = st.f_blocks * st.f_frsize
    disk_free = st.f_bavail * st.f_frsize
    disk_used = disk_total - disk_free
    uptime = float(read("/proc/uptime").split()[0])

    return {
        "name": NAME,
        "uptime_s": int(uptime),
        "cpu": {"pct": cpu_pct, "load1": round(load1, 2), "cores": cores},
        "mem": {
            "total_mb": round(mem_total / 1024),
            "used_mb": round(mem_used / 1024),
            "pct": round(100.0 * mem_used / mem_total, 1) if mem_total else None,
        },
        "disk": {
            "total_gb": round(disk_total / 1e9, 1),
            "used_gb": round(disk_used / 1e9, 1),
            "pct": round(100.0 * disk_used / disk_total, 1) if disk_total else None,
        },
        "temp_c": temperature(),
        "io": io,
        "containers": {"running": containers()},
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            return self._send(200, {"ok": True})
        if self.path.startswith("/metrics"):
            try:
                return self._send(200, snapshot())
            except Exception as e:   # nunca derruba o servidor por uma leitura ruim
                return self._send(500, {"error": str(e)})
        return self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass   # silencioso: é consultado a cada 30 s


if __name__ == "__main__":
    print(f"{NAME} metrics on :{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
