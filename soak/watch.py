#!/usr/bin/env python3
"""
Soak watchdog: samples the running stack once a minute and appends a JSON line to
soak/results/watch-<timestamp>.jsonl, printing a one-line status and flagging anomalies.

Independent of k6 and Prometheus (it degrades gracefully if the monitoring profile is off),
so it is the simplest "did anything go wrong overnight?" record. Standard library only.

    python3 soak/watch.py                 # every 60s until Ctrl-C
    python3 soak/watch.py --interval 30
    python3 soak/watch.py --once          # single sample, exit code 1 if anomalies

Anomalies flagged:
  container not running / restarted, health endpoint not UP, DLQ depth > 0, queue backlog,
  outbox pending growing, stale PENDING orders, no orders placed in the last 10 min,
  firing Prometheus alerts (when monitoring is up).
"""
import argparse
import base64
import datetime as dt
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("API_URL", "http://localhost:8080")
RABBIT = os.environ.get("RABBITMQ_URL", "http://localhost:15672")
RABBIT_AUTH = (os.environ.get("RABBITMQ_USER", "amazeawn"), os.environ.get("RABBITMQ_PASSWORD", "amazeawn"))
PROM = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")
ADMIN = (os.environ.get("ADMIN_EMAIL", "admin@amazeawn.local"), os.environ.get("ADMIN_PASSWORD", "admin123"))
QUEUE_BACKLOG_LIMIT = int(os.environ.get("WATCH_QUEUE_BACKLOG", "500"))
OUTBOX_LIMIT = int(os.environ.get("WATCH_OUTBOX_LIMIT", "500"))
NO_ORDERS_MINUTES = int(os.environ.get("WATCH_NO_ORDERS_MIN", "10"))


def get(url, headers=None, timeout=10):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def post(url, body, headers=None, timeout=10):
    data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json", **(headers or {})}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def safe(fn, default=None):
    try:
        return fn()
    except Exception as e:  # noqa: BLE001 - a watchdog must never die on one failed probe
        return default if default is not None else {"error": str(e)[:200]}


class Watch:
    def __init__(self):
        self.token = None
        self.prev_placed = None
        self.prev_placed_at = None
        self.prev_restarts = {}
        self.prev_outbox = None

    def admin_token(self):
        if not self.token:
            self.token = post(f"{API}/api/v1/auth/login", {"email": ADMIN[0], "password": ADMIN[1]})["accessToken"]
        return self.token

    def stats(self):
        try:
            return get(f"{API}/api/v1/admin/stats", {"Authorization": f"Bearer {self.admin_token()}"})
        except urllib.error.HTTPError as e:
            if e.code == 401:
                self.token = None
                return get(f"{API}/api/v1/admin/stats", {"Authorization": f"Bearer {self.admin_token()}"})
            raise

    @staticmethod
    def containers():
        out = subprocess.run(["docker", "compose", "ps", "--all", "--format", "json"], capture_output=True, text=True, timeout=30).stdout
        rows = []
        for line in out.splitlines():
            line = line.strip()
            if line:
                rows.append(json.loads(line))
        if not rows and out.strip().startswith("["):
            rows = json.loads(out)
        result = {}
        for c in rows:
            name = c.get("Name")
            inspect = subprocess.run(["docker", "inspect", "-f", "{{.RestartCount}}", name], capture_output=True, text=True, timeout=30).stdout.strip()
            result[c.get("Service")] = {"state": c.get("State"), "health": c.get("Health", ""), "restarts": int(inspect or 0)}
        return result

    @staticmethod
    def queues():
        auth = base64.b64encode(f"{RABBIT_AUTH[0]}:{RABBIT_AUTH[1]}".encode()).decode()
        data = get(f"{RABBIT}/api/queues", {"Authorization": f"Basic {auth}"})
        return {q["name"]: {"ready": q.get("messages_ready", 0), "unacked": q.get("messages_unacknowledged", 0), "consumers": q.get("consumers", 0)} for q in data}

    @staticmethod
    def metric(text, name):
        for line in text.splitlines():
            if line.startswith(name + " ") or line.startswith(name + "{"):
                try:
                    return float(line.rsplit(" ", 1)[1])
                except ValueError:
                    pass
        return None

    def api_metrics(self):
        req = urllib.request.Request(f"{API}/actuator/prometheus")
        with urllib.request.urlopen(req, timeout=10) as r:
            text = r.read().decode()
        return {
            "orders_placed_total": self.metric(text, "amazeawn_orders_placed_total"),
            "stale_pending": self.metric(text, "amazeawn_orders_stale_pending"),
            "outbox_pending": self.metric(text, "amazeawn_outbox_pending"),
            "dead_lettered_total": sum(float(l.rsplit(" ", 1)[1]) for l in text.splitlines() if l.startswith("amazeawn_consumer_dead_lettered_total")),
            "retried_total": sum(float(l.rsplit(" ", 1)[1]) for l in text.splitlines() if l.startswith("amazeawn_consumer_retried_total")),
        }

    @staticmethod
    def alerts():
        data = get(f"{PROM}/api/v1/alerts", timeout=5)
        return [{"name": a["labels"].get("alertname"), "severity": a["labels"].get("severity"), "summary": a.get("annotations", {}).get("summary")}
                for a in data.get("data", {}).get("alerts", []) if a.get("state") == "firing"]

    def sample(self):
        now = dt.datetime.now(dt.timezone.utc)
        s = {
            "ts": now.isoformat(timespec="seconds"),
            "api_health": safe(lambda: get(f"{API}/actuator/health").get("status"), "UNREACHABLE"),
            "containers": safe(self.containers, {}),
            "queues": safe(self.queues, {}),
            "metrics": safe(self.api_metrics, {}),
            "stats": safe(self.stats, {}),
            "alerts": safe(self.alerts, []),  # [] when monitoring profile is not running
        }
        anomalies = []

        if s["api_health"] != "UP":
            anomalies.append(f"api health {s['api_health']}")
        for svc, c in (s["containers"] or {}).items():
            if svc in ("k6",):
                continue
            if c.get("state") != "running":
                anomalies.append(f"container {svc} is {c.get('state')}")
            elif c.get("health") not in ("", "healthy"):
                anomalies.append(f"container {svc} health={c.get('health')}")
            prev = self.prev_restarts.get(svc)
            if prev is not None and c.get("restarts", 0) > prev:
                anomalies.append(f"container {svc} restarted ({prev}->{c['restarts']})")
            self.prev_restarts[svc] = c.get("restarts", 0)
        for name, q in (s["queues"] or {}).items():
            if isinstance(q, dict):
                if name.endswith(".dlq") and q["ready"] > 0:
                    anomalies.append(f"{name} has {q['ready']} dead letters")
                if not name.endswith(".dlq") and q["ready"] > QUEUE_BACKLOG_LIMIT:
                    anomalies.append(f"{name} backlog {q['ready']}")
                if not name.endswith(".dlq") and q["consumers"] == 0:
                    anomalies.append(f"{name} has no consumers")
        m = s["metrics"] or {}
        if m.get("stale_pending"):
            anomalies.append(f"{int(m['stale_pending'])} stale PENDING orders")
        if m.get("outbox_pending") is not None:
            if m["outbox_pending"] > OUTBOX_LIMIT:
                anomalies.append(f"outbox pending {int(m['outbox_pending'])}")
            self.prev_outbox = m["outbox_pending"]
        placed = m.get("orders_placed_total")
        if placed is not None:
            if self.prev_placed is not None and placed == self.prev_placed:
                idle = (now - self.prev_placed_at).total_seconds() / 60
                if idle >= NO_ORDERS_MINUTES:
                    anomalies.append(f"no orders placed for {int(idle)} min")
            else:
                self.prev_placed, self.prev_placed_at = placed, now
        for a in s["alerts"] or []:
            if isinstance(a, dict) and a.get("name"):
                anomalies.append(f"alert {a['name']}: {a.get('summary')}")
        s["anomalies"] = anomalies
        return s


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--interval", type=int, default=60)
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--out", default=None, help="JSONL file (default soak/results/watch-<ts>.jsonl)")
    args = ap.parse_args()

    results_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results")
    os.makedirs(results_dir, exist_ok=True)
    out = args.out or os.path.join(results_dir, f"watch-{dt.datetime.now():%Y%m%d-%H%M%S}.jsonl")
    print(f"watch: writing {out} every {args.interval}s (Ctrl-C to stop)", flush=True)

    w = Watch()
    exit_code = 0
    while True:
        s = w.sample()
        with open(out, "a") as f:
            f.write(json.dumps(s) + "\n")
        st = s.get("stats") or {}
        by = st.get("ordersByStatus", {}) if isinstance(st, dict) else {}
        q = s.get("queues") or {}
        line = (f"{s['ts']} api={s['api_health']} orders(P/C/R/X/S/D/F)="
                f"{by.get('PENDING', '?')}/{by.get('CONFIRMED', '?')}/{by.get('REJECTED', '?')}/{by.get('CANCELLED', '?')}/"
                f"{by.get('SHIPPED', '?')}/{by.get('DELIVERED', '?')}/{by.get('REFUNDED', '?')} "
                f"q.cmd={q.get('processor.commands', {}).get('ready', '?')} q.evt={q.get('api.events', {}).get('ready', '?')} "
                f"dlq={q.get('processor.commands.dlq', {}).get('ready', '?')}+{q.get('api.events.dlq', {}).get('ready', '?')} "
                f"outbox={s.get('metrics', {}).get('outbox_pending', '?')} alerts={len(s.get('alerts') or [])}")
        print(line, flush=True)
        for a in s["anomalies"]:
            print(f"   ANOMALY: {a}", file=sys.stderr, flush=True)
            exit_code = 1
        if args.once:
            sys.exit(exit_code)
        time.sleep(args.interval)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
