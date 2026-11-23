# Homelab card (server health on the home screen)

The home screen can show a second drawer card with a server's health: RAM and disk bars, CPU, temperature, IO and how
many containers are running. It is **optional** and **silent**: without `homelab_url` nothing is fetched and nothing is
drawn; when the server stops answering the card disappears (the weather card takes its room back) — no error, no stale
numbers.

## 1. Serve the JSON

`metrics.py` is a stdlib-only HTTP server (Python 3, no dependencies) that reads `/proc`, `/sys` and `docker ps`:

```sh
sudo mkdir -p /opt/severino-metrics
sudo install -m 755 metrics.py /opt/severino-metrics/metrics.py
sudo install -m 644 severino-metrics.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now severino-metrics
curl -s http://<server>:9123/metrics
```

| Env | Default | |
|---|---|---|
| `PORT` | `9123` | |
| `NAME` | `Severino` | shown as the card title |
| `DISK_PATH` | `/` | which filesystem's usage to report |
| `DISK_DEV` | `sda` | `/proc/diskstats` device for the IO rates |
| `TEMP_ZONE` | `thermal_zone0` | `/sys/class/thermal/.../temp` |

Answers with `cpu.pct`, `mem.{used,total}_mb`, `disk.{used,total}_gb`, `temp_c`, `io.{read,write}_kbs`,
`containers.running`, `uptime_s`. The first call has `cpu.pct`/`io` null (no previous sample to compare with); the
second one, called 30 s later, has both.

## 2. Point the app at it

`config.properties`:

```ini
homelab_url=http://192.168.1.20:9123/metrics
```

Plain `http://` is only allowed for private addresses (the build checks it, like `immich_server`). `https://` works
anywhere.

## 3. What you get

The card sits on the right of the weather card (where the crypto prices would be) and is drawn with the same glass,
the same type scale and the same bar style as the rest of the drawer. It refreshes every 30 s, and retries a failed
server after 60 s.
