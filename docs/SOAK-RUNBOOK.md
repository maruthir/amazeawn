# Soak test runbook

A soak run drives Amazeawn at a **steady, realistic volume for a whole day** so that outages, leaks,
stuck orders, lost messages and slow drifts show up on monitoring. It is deliberately not a stress
test: default rates are light enough for a laptop and stay constant for the entire run.

Everything runs in Docker; nothing needs installing besides Docker Desktop, `curl` and `python3`.

## 1. Quick start

```bash
scripts/soak.sh up              # builds images, starts app + Prometheus + Grafana, waits for health
scripts/soak.sh start 8h        # starts the k6 soak in the background (any k6 duration: 30m, 12h, ...)
scripts/soak.sh watch           # optional: foreground watchdog, logs a sample/minute + anomalies
```

Then open **Grafana → "Amazeawn soak"** at http://localhost:3000 (anonymous access is on).

When the run is over (k6 exits by itself at the end of the duration):

```bash
scripts/soak.sh report          # k6 summary + every anomaly the watchdog recorded
scripts/soak.sh clean           # back to a clean slate (see §6)
```

## 2. What the traffic looks like

Three k6 scenarios ([soak/k6/soak.js](../soak/k6/soak.js)) run concurrently for `SOAK_DURATION`:

| Scenario | Default rate | Behaviour |
| --- | --- | --- |
| `browsers` | 5 req/s | Anonymous: product list pages with sorting, product detail, text search, categories. |
| `shoppers` | 30 orders/min | Pool of `SOAK_USERS` (50) pre-registered customers. Each purchase: login (token cached, re-login on 401), browse, clear cart, add 1–3 items, `POST /orders`, poll `GET /orders/{id}` every second until settled. Cards: 75 % always-approve, 10 % always-decline, 15 % random (hits `PAYMENT_FAILURE_RATE`). After a confirmed order: 10 % cancel, 10 % request a refund, 80 % list their orders. |
| `admin` | every 30 s | Stats, approve (80 %) / reject (20 %) every requested refund, occasionally ship a confirmed order by hand, list customers. Every `SOAK_RESTOCK_EVERY_MIN` (60) bulk-restocks every product to `SOAK_RESTOCK_LEVEL` so stock stays steady all day. |

At the defaults an 8-hour run places ≈14,400 orders, ≈145k catalog requests, and keeps roughly
15–25 VUs busy. Meanwhile the app's own fulfilment timers move confirmed orders to `SHIPPED` (30 s)
and `DELIVERED` (60 s), so every state in the order lifecycle is exercised continuously.

### Tuning

All knobs are environment variables (put them in `.env` or prefix the command):

| Variable | Default | Meaning |
| --- | --- | --- |
| `SOAK_DURATION` | `8h` | Run length (k6 duration syntax). `scripts/soak.sh start 12h` overrides it. |
| `SOAK_USERS` | `50` | Customers registered in `setup()`. |
| `SOAK_BROWSE_RPS` | `5` | Anonymous browsing rate. |
| `SOAK_ORDERS_PER_MIN` | `30` | Purchase rate. |
| `SOAK_ADMIN_INTERVAL_S` | `30` | Admin loop interval. |
| `SOAK_RESTOCK_EVERY_MIN` / `SOAK_RESTOCK_LEVEL` | `60` / `1000` | Bulk restock cadence and level (`0` disables). |
| `SOAK_SETTLE_TIMEOUT_S` | `60` | How long a shopper waits for an order to leave `PENDING` before counting it as **stuck**. |
| `PAYMENT_FAILURE_RATE`, `PAYMENT_LATENCY_MS` | `0.05`, `50` | Processor-side (see `.env.example`); change needs `scripts/soak.sh up` to restart the processor. |

A heavier "busy day" that is still steady: `SOAK_BROWSE_RPS=20 SOAK_ORDERS_PER_MIN=120 scripts/soak.sh start 8h`.

## 3. What to watch

### Grafana "Amazeawn soak" (http://localhost:3000/d/amazeawn-soak)

Top row is the at-a-glance health: **firing alerts, stale PENDING orders, DLQ depth, k6 orders stuck,
k6 failure rate, targets up** — all should read 0 / green all day. Below: traffic sent vs served,
latency (k6 and server-side p95), orders placed vs outcomes, settlement time, orders by status,
queue depths, outbox backlog + retries, heap and DB-pool usage, and a table of alert history.

The "Amazeawn overview" dashboard has the per-endpoint and JVM detail.

### Prometheus alerts (http://localhost:9090/alerts)

Rules live in [monitoring/alerts.yml](../monitoring/alerts.yml). Each one names a failure mode:

| Alert | Signals |
| --- | --- |
| `ApiDown`, `ProcessorDown`, `RabbitMqDown` | Outage / crash loop. |
| `DeadLetteredMessages`, `DeadLetterQueueNotEmpty` | A command or event failed 3 times — a bug, not a blip. Inspect the DLQ in RabbitMQ management. |
| `ConsumerRetries` | Sustained transient failures (DB hiccups, deadlocks). |
| `QueueBacklog`, `QueueWithoutConsumers`, `OutboxBacklog` | Consumers or the outbox relay fell behind or stopped. |
| `StalePendingOrders` | Orders waiting > 2 min — the signature of a lost message or stuck relay. |
| `NoOrdersPlaced` | The soak itself died (k6 crashed, login broken). |
| `OutOfStockRejections` | Restock not keeping up — raise `SOAK_RESTOCK_LEVEL` or lower the order rate. |
| `HttpServerErrors`, `HttpLatencyHigh` | 5xx above 1 %, p95 above 1 s. |
| `DbPoolStarvation`, `JvmHeapHigh`, `GcPressure` | Resource leaks and slow drifts — the main reason to run for hours. |

### The watchdog (`scripts/soak.sh watch`)

[soak/watch.py](../soak/watch.py) samples every minute, independent of Prometheus: container state
and restart counts, `/actuator/health`, queue and DLQ depths, outbox backlog, stale orders, "no orders
placed for 10 min", and any firing alerts. One line per minute on stdout, `ANOMALY:` lines on stderr,
and a full JSON record per sample in `soak/results/watch-<ts>.jsonl`. Run it in a second terminal, or
with `nohup scripts/soak.sh watch > soak/results/watch.out 2>&1 &` for an unattended day.
`scripts/soak.sh status` includes one watchdog sample.

### k6's own view

`scripts/soak.sh logs` follows k6. It prints threshold results at the end and writes
`soak/results/summary-<ts>.json` (full metrics) and `soak/results/last-summary.txt` (human summary).
Thresholds are **reported, not aborting**: a soak should keep running through a failure so that the
monitoring captures it. Custom k6 metrics (also pushed to Prometheus as `k6_*`):

| Metric | Meaning |
| --- | --- |
| `order_settle_time` | ms from `202 Accepted` to a terminal status. p95 should sit a few hundred ms above the payment latency. |
| `orders_stuck` | Orders still `PENDING` after `SOAK_SETTLE_TIMEOUT_S`. Must be 0. |
| `orders_by_outcome{outcome}` | `CONFIRMED`, `REJECTED:PAYMENT_DECLINED`, `REJECTED:OUT_OF_STOCK`, … Expect ≈ 75–85 % confirmed with the default card mix. |
| `cancel_success`, `refund_completed` | Rate of accepted cancel / refund requests. |
| `login_failures`, `adminErrors` | Must stay 0. |

## 4. Reading the results

A healthy day looks like: flat request and order rates; settle p95 stable (no upward drift); orders by
status growing linearly with `PENDING` hovering near zero; queue depths near zero; outbox pending 0;
heap sawtooth with a flat baseline; zero alerts; watchdog `ANOMALY` count 0.

Things worth a closer look even without alerts:

* **Settle time creeping up** over hours → DB bloat on `orders`/`outbox`, or the outbox relay batch
  falling behind. Check `amazeawn_outbox_pending` and Postgres autovacuum.
* **Heap baseline rising** → leak (the per-VU token cache in k6 is bounded; look at the app).
* **Hikari active pinned at max** with latency up → pool too small for the rate (`API_DB_POOL_SIZE`).
* **REJECTED:OUT_OF_STOCK appearing** → the restock cadence is too slow for the order rate.
* **Bursty `api.events` depth** → the API's consumer concurrency is below the processor's output.

Keep `soak/results/` from a good run as a baseline to diff later runs against (`summary-*.json` has
every metric with percentiles).

## 5. Operating the run

| Need | Command |
| --- | --- |
| Is it alive? | `scripts/soak.sh status` |
| Logs | `scripts/soak.sh logs` (k6) · `scripts/soak.sh logs api` · `... order-processor` |
| Stop early | `scripts/soak.sh stop` (k6 finishes the in-flight iterations and writes its summary; the app keeps running) |
| Inject a fault mid-run | `docker compose stop order-processor` (watch `StalePendingOrders`, queue backlog, then `docker compose start order-processor` and watch it drain) · `docker compose restart rabbitmq` · `docker compose kill -s SIGKILL api` |
| Scale the processor mid-run | `docker compose up -d --scale order-processor=3` |
| Keep the laptop awake | macOS: `caffeinate -i scripts/soak.sh watch` |

The k6 container runs detached, so closing the terminal does not stop the soak. It survives a
`docker compose restart api` (requests fail, then recover — that is the point) but not
`docker compose down`.

## 6. Cleanup: back to a clean slate

Two levels:

**Soft reset** — keep the containers, wipe the data (the `loadtest` profile's reset endpoint):

```bash
scripts/soak.sh reset
```

Truncates orders, order items, refunds, carts, idempotency keys and processed-message dedup tables,
deletes all customers (the admin stays), reseeds the catalog at `CATALOG_SIZE`/`INITIAL_STOCK`, and
tells the processor to drop its inventory, payments and order records (the reseed re-announces every
product so inventory rows are recreated). Takes a couple of seconds; the stack stays up.

**Hard reset** — stop everything and delete all state:

```bash
scripts/soak.sh clean            # containers + volumes (postgres, rabbitmq, prometheus) + soak/results
scripts/soak.sh clean --images   # additionally removes the built api / order-processor images
```

After `clean`, `scripts/soak.sh up` rebuilds and starts from nothing: fresh database with migrations,
fresh queues, fresh admin and catalog seed, empty Prometheus and Grafana history.

To keep a run's evidence before cleaning, copy `soak/results/` somewhere, and optionally snapshot
Prometheus: `docker run --rm -v amazeawn_promdata:/p -v "$PWD":/out alpine tar czf /out/promdata.tgz -C /p .`.

## 7. Running without the helper script

```bash
docker compose --profile monitoring up -d --build
SOAK_DURATION=8h K6_EXTRA_ARGS="-o experimental-prometheus-rw" docker compose --profile soak up -d k6
docker compose --profile soak logs -f k6
python3 soak/watch.py
docker compose --profile soak --profile monitoring down -v
```

Or with a locally installed k6 against the host port:
`BASE_URL=http://localhost:8080 SOAK_DURATION=1h k6 run soak/k6/soak.js`.
