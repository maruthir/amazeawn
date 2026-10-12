# Amazeawn — SRE handover

You are taking over the operation of Amazeawn: a two-service Spring Boot e-commerce system built as a
realistic target for load and soak testing. This document is written for an engineer who will work
**with AI agents** (Claude Code or similar): it tells you what the system is, how to bring it up, how to
run and read the soak suite, how to operate it day to day, and what to hand an agent so it can do those
things for you without guessing. Everything here was verified on 2026-10-12.

Read in this order: §1 (10 min) → §2 (do it) → §3 (do it) → §4 (keep open) → §5–§7 (reference).

---

## 1. The system in one page

| | |
| --- | --- |
| Repo | https://github.com/maruthir/amazeawn — Gradle multi-module, Java 21, Spring Boot 3.4 |
| Services | **api** (`:8080`, REST + static UI + Swagger) · **order-processor** (`:8081`, internal; inventory, simulated payments) |
| Infra | **Postgres 16** (one DB, two schemas: `api`, `processor`) · **RabbitMQ 3.13** (exchange `amazeawn`, queues `processor.commands`, `api.events`, each with a `.dlq`) |
| Optional | **Prometheus + Grafana** (`--profile monitoring`) · **k6** soak driver (`--profile soak`) |
| One command | `docker compose up --build` (four containers) |
| Personas | Customer (self-registered, JWT) · Admin (`ADMIN_EMAIL`/`ADMIN_PASSWORD`, seeded at startup) |

**How an order flows** (the thing every alert is really about):

```
POST /orders ──▶ api: orders row PENDING + outbox row ── relay ──▶ RabbitMQ ──▶ processor
                                                                                  reserve stock → charge → outbox
GET /orders/{id} ◀── api applies order.confirmed / order.rejected / inventory.changed ◀── relay ◀──┘
```

Design facts that matter operationally:

* **Transactional outbox on both sides.** A business change and its message commit together; a relay
  polls the `outbox` table and publishes with confirms. If the relay stalls, `amazeawn_outbox_pending`
  grows and orders sit in `PENDING`.
* **Consumers are idempotent** (`processed_messages` table, keyed by message id), use **manual acks**,
  retry `CONSUMER_MAX_ATTEMPTS` (3) times by re-publishing with an attempt header, then **dead-letter**.
  A non-empty DLQ is always a bug or a poison message, never a blip.
* **Stock is owned by the processor.** `products.stock` in the API is a mirror fed by
  `inventory.changed` events — eventually consistent, can lag, cannot oversell.
* **State machines.** Order: `PENDING → CONFIRMED|REJECTED`, `CONFIRMED → SHIPPED → DELIVERED` on
  timers (admin can override), `PENDING|CONFIRMED → CANCEL_REQUESTED → CANCELLED`,
  `CONFIRMED|SHIPPED|DELIVERED → REFUNDED` via refund `REQUESTED → APPROVED → COMPLETED`.
* **Correlation id** (`X-Correlation-Id`, minted if absent) is in every log line of both services and
  on every message. Grep it across `docker compose logs api order-processor`.
* **Processor is stateless** → `docker compose up -d --scale order-processor=N` is safe at any time.

Further reading: [README.md](../README.md) (API surface, knobs), [SOAK-RUNBOOK.md](SOAK-RUNBOOK.md) (soak detail).

---

## 2. Bring it up (first day)

Prerequisites: Docker Desktop (≥ 4 CPUs / 6 GB for the full stack with monitoring), `curl`, `python3`.
No local JDK or Gradle needed for Docker runs; `./gradlew` auto-provisions JDK 21 for local builds/tests.

```bash
git clone https://github.com/maruthir/amazeawn && cd amazeawn
cp .env.example .env          # edit secrets — see §6 before anything public
scripts/soak.sh up            # builds images, starts app + Prometheus + Grafana, waits for health
scripts/smoke.sh              # ~20 s end-to-end: register, order, confirm, decline, cancel, refund, admin
```

Expected result: `docker compose ps` shows api, order-processor, postgres, rabbitmq **healthy**;
smoke prints `Smoke test passed`. If not, see §5.1.

| Where to look | URL |
| --- | --- |
| Storefront / admin UI | http://localhost:8080 |
| Swagger | http://localhost:8080/swagger-ui.html |
| Grafana (anonymous view) | http://localhost:3000 → dashboards **Amazeawn soak**, **Amazeawn overview** |
| Prometheus alerts | http://localhost:9090/alerts |
| RabbitMQ management | http://localhost:15672 (`amazeawn`/`amazeawn`) |
| Postgres | `localhost:5433`, user/db/pass `amazeawn` |
| Health / metrics | `/actuator/health`, `/actuator/prometheus` on both services |

Local development (optional): `./gradlew test` runs the Testcontainers integration tests (needs Docker);
`./gradlew :api:bootRun` with `DB_PORT=5433` runs the API against the compose infra.

---

## 3. Run the soak suite (first week)

The soak is a **steady operational day**, not a stress test: constant light traffic for hours so that
leaks, drifts, lost messages and crashes show up on monitoring.

```bash
scripts/soak.sh start 8h      # k6 in a container, detached; survives closing the terminal
scripts/soak.sh watch         # second terminal: 1 sample/min, ANOMALY lines on stderr (or nohup it)
scripts/soak.sh status        # any time: containers, k6 progress, queue depths, firing alerts
scripts/soak.sh report        # when done: k6 thresholds + every anomaly the watchdog recorded
```

Default traffic: 5 req/s browsing, 30 orders/min (≈14k orders/8 h), an admin loop every 30 s that works
the refund queue and restocks hourly. Tune with `SOAK_*` variables (table in the runbook §2).

**Acceptance for a healthy day** — all of these, for the whole run:

| Signal | Where | Expect |
| --- | --- | --- |
| Firing alerts | Grafana top-left tile / `:9090/alerts` | 0 |
| Stale PENDING orders | tile / `amazeawn_orders_stale_pending` | 0 |
| DLQ depth | tile / RabbitMQ | 0 |
| k6 `orders_stuck`, `login_failures`, `admin_errors` | tile / `report` | 0 |
| k6 `http_req_failed` | tile / `report` | < 1 % (realistically 0) |
| Settle p95 (`order_settle_time`) | panel | ≈ 1–1.5 s, **flat** (polling 1 s + payment latency) |
| Heap, Hikari active, queue depths | panels | sawtooth / low / near zero, **no upward drift** |
| Watchdog | `report` | "no anomalies recorded" |

Keep the first good run's `soak/results/` as the baseline. The runbook's §4 explains what each kind of
drift usually means.

Before a soak, and between soaks: `scripts/soak.sh reset` (soft: wipes orders, carts, refunds,
customers; reseeds catalog; stack stays up). Full teardown: `scripts/soak.sh clean [--images]`.

**Fault injection** is part of the soak's purpose. With a run in progress, try each and watch the
dashboard react and recover:

```bash
docker compose stop order-processor      # → StalePendingOrders, queue backlog; then: docker compose start order-processor
docker compose restart rabbitmq          # → brief k6 failures, consumers reconnect, outbox drains
docker compose kill -s SIGKILL api       # → ApiDown; compose restarts it (restart policy = default "no": start it yourself)
docker compose up -d --scale order-processor=3
```

---

## 4. Operating it: the monitors and what they mean

### 4.1 Alert catalogue ([monitoring/alerts.yml](../monitoring/alerts.yml))

| Alert | Severity | Likely cause → first action |
| --- | --- | --- |
| `ApiDown` / `ProcessorDown` / `RabbitMqDown` | critical | Container crashed or OOM-killed. `docker compose ps -a`, `docker compose logs --tail 200 <svc>`, `docker inspect -f '{{.State.OOMKilled}}'`. Restart; if repeating, raise `API_MEMORY`/`PROCESSOR_MEMORY`. |
| `DeadLetteredMessages` / `DeadLetterQueueNotEmpty` | critical | A message failed 3×. Inspect it in RabbitMQ → Queues → `*.dlq` → Get messages (payload is JSON with `orderId`, `correlationId`). Grep both logs for the correlation id. Fix the cause, then replay (§5.4) or purge. |
| `ConsumerRetries` | warning | Transient DB errors/deadlocks under load. Check Postgres logs, Hikari pending. |
| `QueueBacklog` / `QueueWithoutConsumers` | warning/critical | Consumers slow or gone. Processor scaled to 0? Listener container stopped after a broker outage? `docker compose restart` the consumer service. |
| `OutboxBacklog` | warning | Relay cannot publish (broker down/refusing confirms) or DB locks. Check `rabbitmq` health and `docker compose logs api | grep -i outbox`. |
| `StalePendingOrders` | critical | **The** lost-message signal. Order placed, no outcome in 2 min. Trace one: `SELECT id, created_at FROM api.orders WHERE status='PENDING' ORDER BY created_at LIMIT 5;` → is its `order.place` still in `api.outbox` unpublished? in `processor.commands`? in the DLQ? in `processor.processed_orders`? |
| `NoOrdersPlaced` | warning | Only meaningful during a soak: k6 died or logins fail. `scripts/soak.sh logs`. |
| `OutOfStockRejections` | warning | Restock cadence < consumption. `POST /admin/inventory/restock {"level":N}` or raise `SOAK_RESTOCK_LEVEL`. |
| `HttpServerErrors` / `HttpLatencyHigh` | critical/warning | Look at `/actuator/prometheus` `http_server_requests_seconds_count{status="500"}` by `uri`; `GlobalExceptionHandler` logs every 500 with a stack trace. |
| `DbPoolStarvation` | warning | `hikaricp_connections_pending > 0`: pool too small for the rate → `API_DB_POOL_SIZE` / `PROCESSOR_DB_POOL_SIZE`, keep `PG_MAX_CONNECTIONS` above the sum. |
| `JvmHeapHigh` / `GcPressure` | warning | Leak or undersized heap. Compare heap baseline at hour 1 vs hour 6 on the overview dashboard. `jcmd` via `docker compose exec api jcmd 1 GC.heap_info`. |

### 4.2 Dashboards

* **Amazeawn soak** (`/d/amazeawn-soak`): the operations view. Top tiles are the go/no-go. Open it with
  `?from=now-24h` for a whole day.
* **Amazeawn overview** (`/d/amazeawn-overview`): per-endpoint latency, outbox, retries, payment timing,
  Hikari, JVM — the drill-down view.

### 4.3 Key metrics (Prometheus names)

`amazeawn_orders_placed_total`, `amazeawn_orders_outcome_total{outcome}`, `amazeawn_orders_by_status{status}`,
`amazeawn_orders_stale_pending`, `amazeawn_outbox_pending{application}`,
`amazeawn_consumer_{handled,retried,dead_lettered}_total{queue,type}`, `amazeawn_payment_charge_seconds`,
`amazeawn_payment_result_total{outcome}`, `rabbitmq_queue_messages_ready{queue}`, `rabbitmq_queue_consumers{queue}`,
plus Spring's `http_server_requests_seconds_*`, `hikaricp_*`, `jvm_*`, and k6's `k6_*` during a soak.

### 4.4 Logs

```bash
docker compose logs -f --tail 100 api order-processor          # format: time level [correlationId] logger - msg
docker compose logs api order-processor | grep <correlation-id> # one request's full journey
LOG_LEVEL=DEBUG docker compose up -d api                        # outbox relay batches, fulfilment ticks
```

---

## 5. Runbooks

### 5.1 Stack will not come up
1. `docker compose ps -a` — which one is unhealthy? `docker compose logs <svc>`.
2. Port clash: `lsof -nP -iTCP:8080 -sTCP:LISTEN` (also 5433, 5672, 15672, 9090, 3000). Change `*_PORT` in `.env`.
3. `relation ... does not exist` / Flyway validation error: schema drift. For a dev box, `scripts/soak.sh clean` and start again; never edit an applied `V*.sql`, add a new version.
4. API healthy but processor not: both must reach RabbitMQ; `docker compose logs rabbitmq`.

### 5.2 Orders stuck in PENDING (no alert yet, or alert fired)
```bash
# 1. how many, how old
docker compose exec -T postgres psql -U amazeawn -d amazeawn -c \
 "select status, count(*), min(created_at) from api.orders where status in ('PENDING','CANCEL_REQUESTED') group by 1"
# 2. is the API outbox draining?
docker compose exec -T postgres psql -U amazeawn -d amazeawn -c "select count(*) from api.outbox where published_at is null"
# 3. queue/DLQ state
scripts/soak.sh status
# 4. did the processor see it?
docker compose exec -T postgres psql -U amazeawn -d amazeawn -c "select * from processor.processed_orders where order_id='<id>'"
```
Outbox not draining → RabbitMQ or relay problem (restart `api` after fixing the broker). Processor saw
it but no event back → check `processor.outbox` and `api.events`. Nothing anywhere → the command is in
the DLQ or was lost; see 5.4.

### 5.3 Processor crash-looping / OOM
`docker inspect -f '{{.State.OOMKilled}} {{.RestartCount}}' amazeawn-order-processor-1`. Raise
`PROCESSOR_MEMORY`, or lower `PROCESSOR_CONSUMER_CONCURRENCY`/`CONSUMER_PREFETCH` (each in-flight
message holds a DB connection and a payment-latency sleep). Messages in flight are redelivered on
restart; idempotency makes that safe.

### 5.4 Replaying a dead-lettered message
In RabbitMQ management → Queues → `processor.commands.dlq` → *Move messages* to `processor.commands`
(shovel plugin is in the management image) — or *Get messages* with ack mode "reject requeue", copy the
payload and headers, and *Publish* to exchange `amazeawn` with the original routing key (`order.place`
etc.). The consumer dedupes by `messageId`, so a message that *was* processed but failed to ack is a
no-op; one that genuinely failed is retried from attempt 1.

### 5.5 Scaling for a bigger box
Raise together: `API_DB_POOL_SIZE`, `PROCESSOR_DB_POOL_SIZE`, `PG_MAX_CONNECTIONS` (> sum of pools × replicas),
`API_HTTP_MAX_THREADS`, `PROCESSOR_CONSUMER_CONCURRENCY`, `CONSUMER_PREFETCH`, memory limits, and
`--scale order-processor=N`. Watch `hikaricp_connections_pending` and `rabbitmq_queue_messages_ready`
as the first saturation signals. `OUTBOX_POLL_MS`/`OUTBOX_BATCH_SIZE` trade latency for DB load.

### 5.6 Data reset
* Soft, keeps containers: `scripts/soak.sh reset` → `POST /api/v1/test/reset?dropCustomers=true`
  (requires `API_PROFILES=loadtest`; **turn that profile off in production**, §6).
* Hard, everything: `scripts/soak.sh clean [--images]`.

### 5.7 Upgrading / deploying a change
```bash
git pull
./gradlew test                                   # integration tests with Testcontainers
scripts/soak.sh up                               # build + force-recreate api and processor; infra untouched
scripts/smoke.sh                                 # 20 s regression
```
Schema changes are Flyway migrations under `*/src/main/resources/db/migration/`; each service migrates
its own schema at startup, so deploy order between api and processor does not matter, but a message
contract change in `common/` must be deployed to both before traffic depends on it (records are
deserialised by class name; adding a field is backward compatible, renaming is not).

### 5.8 Backups
Dev setup has none. For anything that matters: `docker compose exec -T postgres pg_dump -U amazeawn amazeawn > backup.sql`
on a schedule; RabbitMQ holds only in-flight messages and is rebuilt from the outbox tables.

---

## 6. Before calling it "production"

This is a sample system. What it deliberately does **not** have, and what to change first:

| Gap | Action |
| --- | --- |
| Default secrets in `.env.example` (`JWT_SECRET`, admin password, DB/MQ passwords) | Generate real ones in `.env`; the JWT secret must be ≥ 32 bytes. |
| `API_PROFILES=loadtest` exposes an unauthenticated `POST /api/v1/test/reset` | Set `API_PROFILES=` (empty) in production. |
| No TLS, no reverse proxy, all ports on `0.0.0.0` | Put nginx/Traefik with TLS in front of `:8080`; bind infra ports to `127.0.0.1` or remove the host mappings for Postgres/RabbitMQ/Prometheus/Grafana. |
| Grafana anonymous access, admin/admin | `GF_AUTH_ANONYMOUS_ENABLED=false`, real admin password. |
| Rate limiting off (`RATE_LIMIT_ENABLED=false`) — on purpose for load tests | Decide per environment. |
| Single Postgres, single RabbitMQ, no persistence of alerts | Managed Postgres/RabbitMQ or at least backups (§5.8); add Alertmanager with a notification route if alerts must page someone. |
| Containers do not auto-restart | Add `restart: unless-stopped` to the services in `docker-compose.yml` (or run on an orchestrator). |
| Payment is simulated; emails are not sent; refunds are full-order only | By design — see the README's decisions table. |

---

## 7. Working with AI agents on this system

The repo is agent-friendly: every capability is an HTTP endpoint, every knob is an env var, every
check is a script with a non-zero exit on failure, and the docs above are the context an agent needs.
Point the agent at this file plus the runbook and README; the project already has a memory note that
the build is Gradle (not Maven) and that the plan's defaults were taken.

Ground rules that have worked:

* **Give the agent the acceptance criteria, not just the task** (§3's table, §4.1's alert catalogue).
  "Run an 8 h soak and tell me if it passes" is answerable; "run the tests" is not.
* **Let it verify with the scripts** — `scripts/smoke.sh`, `scripts/soak.sh status|report`,
  `python3 soak/watch.py --once` — all exit non-zero on problems.
* **Destructive actions need a human**: `scripts/soak.sh clean`, `reset`, anything that touches `.env`
  secrets, `git push`. Tell the agent explicitly when it may do these.
* **Long runs**: the k6 container and `nohup scripts/soak.sh watch` are detached, so an agent can start
  a soak, end its session, and a later session can `report`. Don't have an agent poll every minute for
  8 hours; have it check at the end, or schedule a check.

Prompts you can paste (adjust durations):

> **Bring-up.** Clone https://github.com/maruthir/amazeawn, read docs/SRE-HANDOVER.md §1–2, create `.env`
> from `.env.example` with fresh secrets, run `scripts/soak.sh up` then `scripts/smoke.sh`, and report
> container health and the smoke output. Don't change code.

> **Soak.** With the stack up, run `scripts/soak.sh reset`, then `scripts/soak.sh start 8h` and
> `nohup scripts/soak.sh watch > soak/results/watch.out 2>&1 &`. Come back in 8 h (or when I ask) and
> evaluate against the acceptance table in docs/SRE-HANDOVER.md §3: give me pass/fail per row, the k6
> summary, every watchdog anomaly, and screenshots of the "Amazeawn soak" Grafana dashboard over the full
> window. Do not clean or reset afterwards.

> **Fault drill.** While a soak is running, stop `order-processor` for 3 minutes, then start it. Report
> which alerts fired and when, how long PENDING orders took to drain after restart, whether any order was
> stuck or dead-lettered, and whether k6 thresholds still pass.

> **Incident.** `StalePendingOrders` is firing. Follow docs/SRE-HANDOVER.md §5.2, trace one stuck order
> through api.outbox → processor.commands → processor.processed_orders → processor.outbox → api.events,
> tell me where it stopped and why (with the correlation id and log lines), and propose a fix. Do not
> replay or purge anything without asking.

> **Capacity.** Run three 30-minute soaks at `SOAK_ORDERS_PER_MIN` 30, 120, 300 (reset between runs),
> and report settle p95, API p95, Hikari pending, queue depth and heap for each; tell me which knob from
> docs/SRE-HANDOVER.md §5.5 saturates first.

> **Hardening.** Implement the §6 table for a single-host production deployment: real secrets, profile
> off, restart policies, infra ports bound to localhost, Grafana auth. Keep `docker compose up --build`
> working, update docs, run `./gradlew test` and `scripts/smoke.sh`, and open a PR.

---

## Appendix: file map

```
scripts/soak.sh          up | start | status | watch | logs | stop | report | reset | clean
scripts/smoke.sh         20-second end-to-end regression
soak/k6/soak.js          traffic scenarios + thresholds + custom metrics
soak/watch.py            watchdog (JSONL samples, anomaly detection)
soak/results/            k6 summaries, watchdog logs (git-ignored)
monitoring/alerts.yml    Prometheus alert rules
monitoring/prometheus.yml, monitoring/grafana/dashboards/{soak,amazeawn}.json
docker-compose.yml       services, profiles (monitoring, soak), every env knob with its default
.env.example             all knobs, documented
docs/SOAK-RUNBOOK.md     soak detail: traffic profile, tuning, reading results, fault injection
README.md                architecture, API surface, load-test knobs, decisions
api/, order-processor/, common/   source; db/migration/ holds Flyway migrations
```
