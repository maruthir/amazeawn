# Amazeawn

A two-service Spring Boot e-commerce sample built to be load-tested over plain HTTP. One command brings
up four containers: the **API** (customer and admin REST + a static storefront), the **order processor**
(inventory, simulated payments, cancel/refund execution), **Postgres** and **RabbitMQ**.

```bash
docker compose up --build
```

Then:

| What | Where |
| --- | --- |
| Storefront + admin UI | http://localhost:8080 |
| Swagger UI / OpenAPI | http://localhost:8080/swagger-ui.html · `/v3/api-docs` |
| Health / Prometheus | http://localhost:8080/actuator/health · `/actuator/prometheus` |
| RabbitMQ management | http://localhost:15672 (amazeawn / amazeawn) |
| Postgres (psql) | `localhost:5433`, db/user/password `amazeawn` (schemas `api` and `processor`) |
| Admin login | `admin@amazeawn.local` / `admin123` (from `ADMIN_EMAIL` / `ADMIN_PASSWORD`) |

Customers self-register through the UI or `POST /api/v1/auth/register`. Simulated cards:
`4242424242424242` always approves, `4000000000000002` always declines, anything else is random
with probability `PAYMENT_FAILURE_RATE`.

Smoke-test a running stack end to end (register → order → confirm → decline → cancel → refund → admin):

```bash
scripts/smoke.sh
```

## How an order flows

```
client ── POST /orders (Idempotency-Key) ──▶ API ──┐ same transaction
                                                   ├─ orders row (PENDING)
         202 { orderId }  ◀────────────────────────┘  outbox row (order.place)
                                                   │
                        outbox relay ──▶ RabbitMQ [amazeawn] ── order.place ──▶ processor.commands
                                                                                  │
                                                 processor: reserve stock ─▶ charge ─▶ outbox
                                                                                  │
                         api.events ◀── order.confirmed | order.rejected | inventory.changed
                                                   │
client ── GET /orders/{id} ──▶ API (CONFIRMED / REJECTED + reason)
```

* Both services write messages to an **outbox** table in the same transaction as the business change;
  a relay publishes them with publisher confirms. Nothing is ever published that was not committed.
* Consumers use **manual acks**, dedupe by message id (`processed_messages`), retry up to
  `CONSUMER_MAX_ATTEMPTS`, then **dead-letter** (`processor.commands.dlq`, `api.events.dlq`). Duplicate
  or lost messages under load show up as counters, DLQ depth and stuck `PENDING` orders — all observable.
* A correlation id (`X-Correlation-Id` request header, minted if absent) rides on every message and
  appears in both services' logs.
* **Data ownership:** schema `api` (users, catalog, carts, orders, refunds) and schema `processor`
  (inventory, payments, processed orders) in one Postgres. The processor is the only writer of stock,
  so it cannot oversell; the catalog's `stock` field is a mirror fed by `inventory.changed` events and
  is eventually consistent.
* **Cancel** (`PENDING`/`CONFIRMED` → `CANCEL_REQUESTED` → `CANCELLED`) and **refund** (customer
  requests → admin approves → `refund.execute` → `refund.completed` → order `REFUNDED`) use the same
  command/event pattern. Refunds are full-order only.
* **Fulfilment** advances `CONFIRMED → SHIPPED → DELIVERED` on timers; admins can override.

## API surface (`/api/v1`)

| Group | Auth | Endpoints |
| --- | --- | --- |
| Public | none | `GET /products` (q, categoryId, minPrice, maxPrice, inStock, page, size, sort) · `GET /products/{id}` · `GET /categories` |
| Auth | none | `POST /auth/register` · `POST /auth/login` · `POST /auth/refresh` · `POST /auth/logout` |
| Profile | customer/admin | `GET,PUT /me` · `GET,POST /me/addresses` · `PUT,DELETE /me/addresses/{id}` |
| Cart | customer | `GET /cart` · `POST /cart/items` · `PUT,DELETE /cart/items/{itemId}` · `DELETE /cart` |
| Orders | customer | `POST /orders` (202, needs `Idempotency-Key`) · `GET /orders` · `GET /orders/{id}` · `POST /orders/{id}/cancel` · `POST,GET /orders/{id}/refund` |
| Admin | admin | `GET,POST /admin/products` · `PUT,DELETE /admin/products/{id}` · `POST /admin/products/{id}/inventory` · `POST /admin/inventory/restock` · `POST /admin/categories` · `PUT /admin/categories/{id}` · `GET /admin/orders` · `GET /admin/orders/{id}` · `PUT /admin/orders/{id}/status` · `GET /admin/refunds` · `POST /admin/refunds/{id}/approve|reject` · `GET /admin/customers` · `PUT /admin/customers/{id}/disable|enable` · `GET /admin/stats` |
| Test | none, `loadtest` profile only | `POST /test/reset?catalogSize=&stock=&dropCustomers=` |

Auth is a stateless JWT (`Authorization: Bearer …`, 15 min) plus a rotating refresh token (7 days).
No CSRF, no cookies, no CAPTCHA, rate limiting off by default.

## Load-test knobs

All are environment variables read by `docker-compose.yml`; see [`.env.example`](.env.example) for the full list.

| Area | Variables |
| --- | --- |
| Payment simulator | `PAYMENT_LATENCY_MS`, `PAYMENT_LATENCY_JITTER_MS`, `PAYMENT_FAILURE_RATE` |
| Seed data | `CATALOG_SIZE`, `INITIAL_STOCK`, `CATALOG_CATEGORIES`; bulk restock via `POST /admin/inventory/restock {"level": N}` |
| Password hashing | `AUTH_BCRYPT_COST` (drop to 4–6 so signup/login is not the CPU bottleneck) |
| Rate limiting | `RATE_LIMIT_ENABLED`, `RATE_LIMIT_RPS`, `RATE_LIMIT_BURST` |
| Fulfilment timers | `FULFILMENT_ENABLED`, `FULFILMENT_SHIP_AFTER`, `FULFILMENT_DELIVER_AFTER` (ISO-8601 durations) |
| Pools & concurrency | `API_DB_POOL_SIZE`, `API_HTTP_MAX_THREADS`, `API_CONSUMER_CONCURRENCY`, `PROCESSOR_DB_POOL_SIZE`, `PROCESSOR_CONSUMER_CONCURRENCY`, `CONSUMER_PREFETCH`, `OUTBOX_POLL_MS`, `OUTBOX_BATCH_SIZE`, `PG_MAX_CONNECTIONS` |
| Memory | `API_MEMORY`, `PROCESSOR_MEMORY`, `API_JAVA_OPTS`, `PROCESSOR_JAVA_OPTS` |

Defaults are sized for a laptop (8 cores / 8 GB). For a server, raise the pool, thread and
concurrency numbers together with `PG_MAX_CONNECTIONS`.

**Scale the processor** (it is stateless; `SKIP LOCKED` keeps the outbox relays from colliding):

```bash
docker compose up --scale order-processor=4
```

**Reset between runs** (keeps customers unless `dropCustomers=true`):

```bash
curl -X POST "http://localhost:8080/api/v1/test/reset?stock=1000"
```

**Metrics worth watching** (`/actuator/prometheus` on both services):
`amazeawn_orders_placed_total`, `amazeawn_orders_outcome_total{outcome}`, `amazeawn_outbox_pending`,
`amazeawn_consumer_retried_total`, `amazeawn_consumer_dead_lettered_total`,
`amazeawn_payment_charge_seconds`, plus the standard `http_server_requests_seconds`, `hikaricp_*` and JVM metrics.

**Prometheus + Grafana** (optional profile; Grafana at http://localhost:3000 with a pre-provisioned dashboard):

```bash
docker compose --profile monitoring up --build
```

## Taking over this system?

Start with **[docs/SRE-HANDOVER.md](docs/SRE-HANDOVER.md)**: bring-up, soak acceptance criteria, alert
catalogue, runbooks, production gaps, and prompts for operating it with AI agents.

## Soak test (a steady operational day)

A k6-driven, all-day steady-traffic run with Prometheus alert rules, a Grafana soak dashboard and a
watchdog that records anomalies — see **[docs/SOAK-RUNBOOK.md](docs/SOAK-RUNBOOK.md)**.

```bash
scripts/soak.sh up          # app + monitoring
scripts/soak.sh start 8h    # k6 soak in the background
scripts/soak.sh watch       # anomaly watchdog
scripts/soak.sh report      # afterwards
scripts/soak.sh clean       # clean slate (containers, volumes, results)
```

## Project layout

```
common/           message contracts, AMQP topology, outbox relay, idempotent consumer support
api/              Spring Boot API + static UI (src/main/resources/static)
order-processor/  Spring Boot worker: inventory, payment simulator, command handlers
soak/             k6 soak scenarios (k6/soak.js), watchdog (watch.py), results/
monitoring/       Prometheus config + alert rules, Grafana dashboards, RabbitMQ plugin list
docs/             SRE-HANDOVER.md, SOAK-RUNBOOK.md
docker-compose.yml, .env.example, scripts/smoke.sh, scripts/soak.sh
```

Build system: Gradle (wrapper included), Java 21 (auto-provisioned by the toolchain resolver if you only
have an older JDK locally), Spring Boot 3.4, Flyway, Postgres 16, RabbitMQ 3.13.

## Developing

```bash
./gradlew build            # compiles and runs the Testcontainers integration tests (needs Docker)
./gradlew :api:bootRun     # run the API against docker-compose's postgres/rabbitmq (DB_PORT=5433)
./gradlew :order-processor:bootRun
```

The integration tests spin up their own Postgres and RabbitMQ:
[`ApiIntegrationTest`](api/src/test/java/com/amazeawn/api/ApiIntegrationTest.java) drives the API over
HTTP and plays the processor's part on the queue;
[`ProcessorIntegrationTest`](order-processor/src/test/java/com/amazeawn/processor/ProcessorIntegrationTest.java)
sends commands and asserts the events (stock reservation, out-of-stock, declined card, duplicate
delivery, cancel, refund).

## Decisions taken from the build plan

| # | Decision |
| --- | --- |
| 1 | Minimal static UI served from the API container (four containers). |
| 2 | Schema per service, queue-only communication; catalog stock is eventually consistent, overselling prevented at the processor. |
| 3 | `POST /orders` returns `202` and the client polls. |
| 4 | Finite stock with rejection on shortfall, plus bulk restock. |
| 5 | Customer requests, admin approves, processor executes; full-order refunds only. |
| 6 | Shipped/delivered advance on timers with admin override. |
| 7 | Sized for a laptop; every pool/concurrency/catalog knob is an env var. |
| 8 | Java 21, Gradle, Postgres, JWT; Prometheus + Grafana as an optional compose profile. |
| 9 | Delivered as this folder. |
