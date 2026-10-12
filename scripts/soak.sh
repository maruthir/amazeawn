#!/usr/bin/env bash
# Soak-test runner for Amazeawn. See docs/SOAK-RUNBOOK.md.
#
#   scripts/soak.sh up                 build + start app and monitoring stack
#   scripts/soak.sh start [duration]   start the k6 soak in the background (default SOAK_DURATION or 8h)
#   scripts/soak.sh status             containers, k6 progress, queue depths, firing alerts
#   scripts/soak.sh watch              run the watchdog in the foreground (Ctrl-C to stop)
#   scripts/soak.sh logs [service]     follow logs (default: k6)
#   scripts/soak.sh stop               stop the k6 run (app stays up); writes the summary
#   scripts/soak.sh report             print the last k6 summary and watchdog anomalies
#   scripts/soak.sh reset              soft reset: wipe orders/carts/refunds/customers and reseed (stack stays up)
#   scripts/soak.sh clean [--images]   hard reset: stop everything, delete volumes and results (and images)
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILES=(--profile monitoring --profile soak)
compose() { docker compose "${PROFILES[@]}" "$@"; }
API="${API_URL:-http://localhost:8080}"
PROM="${PROMETHEUS_URL:-http://localhost:9090}"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }

# Small formatters for JSON from the RabbitMQ and Prometheus APIs (stdin -> text).
read -r -d '' QUEUES_PY <<'EOF' || true
import json, sys
for q in json.load(sys.stdin):
    print(f"   {q['name']:28} ready={q['messages_ready']:<6} unacked={q['messages_unacknowledged']:<4} consumers={q['consumers']}")
EOF
read -r -d '' ALERTS_PY <<'EOF' || true
import json, sys
a = [x for x in json.load(sys.stdin)["data"]["alerts"] if x["state"] == "firing"]
print("   none" if not a else "\n".join(f"   {x['labels']['severity']:9} {x['labels']['alertname']}: {x['annotations'].get('summary', '')}" for x in a))
EOF

wait_healthy() {
  bold "Waiting for api and order-processor to be healthy..."
  for _ in $(seq 1 60); do
    s=$(docker compose ps --format '{{.Service}}={{.Health}}' | tr '\n' ' ')
    if grep -q 'api=healthy' <<<"$s" && grep -q 'order-processor=healthy' <<<"$s"; then
      echo "   ready"; return 0
    fi
    sleep 5
  done
  echo "stack did not become healthy; see: docker compose ps / docker compose logs" >&2
  return 1
}

cmd_up() {
  bold "Building images and starting app + monitoring"
  docker compose build api order-processor
  # Explicit recreate so a rebuilt image always replaces a running container.
  docker compose up -d --force-recreate api order-processor
  docker compose --profile monitoring up -d
  wait_healthy
  cat <<EOF

  UI / API      $API
  Grafana       http://localhost:${GRAFANA_PORT:-3000}  (dashboards: "Amazeawn soak", "Amazeawn overview")
  Prometheus    $PROM/alerts
  RabbitMQ      http://localhost:${RABBITMQ_MGMT_PORT:-15672}  (amazeawn / amazeawn)

Next: scripts/soak.sh start [duration]
EOF
}

cmd_start() {
  local duration="${1:-${SOAK_DURATION:-8h}}"
  if ! curl -fsS "$API/actuator/health" >/dev/null 2>&1; then
    echo "API is not up at $API — run: scripts/soak.sh up" >&2; exit 1
  fi
  if docker compose --profile soak ps --status running --format '{{.Service}}' 2>/dev/null | grep -qx k6; then
    echo "a k6 run is already in progress — scripts/soak.sh status / stop" >&2; exit 1
  fi
  local extra=""
  if curl -fsS "$PROM/-/ready" >/dev/null 2>&1; then
    extra="-o experimental-prometheus-rw"
    echo "Prometheus is up: k6 metrics will be pushed to it"
  else
    echo "Prometheus not reachable: k6 runs without remote write (start it with: scripts/soak.sh up)"
  fi
  mkdir -p soak/results
  bold "Starting soak for $duration"
  SOAK_DURATION="$duration" K6_EXTRA_ARGS="$extra" docker compose --profile soak up -d --no-deps --force-recreate k6
  echo "   started at $(date). Follow with: scripts/soak.sh logs   |   watchdog: scripts/soak.sh watch"
}

cmd_status() {
  bold "Containers"; compose ps --format 'table {{.Service}}\t{{.Status}}'
  bold "k6"; docker compose --profile soak logs --tail 3 k6 2>/dev/null | sed 's/^/   /' || echo "   not running"
  bold "Queues"
  curl -s -u "${RABBITMQ_USER:-amazeawn}:${RABBITMQ_PASSWORD:-amazeawn}" "http://localhost:${RABBITMQ_MGMT_PORT:-15672}/api/queues" \
    | python3 -I -c "$QUEUES_PY" 2>/dev/null || echo "   rabbitmq not reachable"
  bold "Firing alerts"
  curl -s "$PROM/api/v1/alerts" | python3 -I -c "$ALERTS_PY" 2>/dev/null || echo "   prometheus not running"
  bold "Watchdog snapshot"; python3 -I soak/watch.py --once --out /dev/null || true
}

cmd_watch() { python3 -I soak/watch.py "$@"; }

cmd_logs() { docker compose --profile soak --profile monitoring logs -f "${1:-k6}"; }

cmd_stop() {
  bold "Stopping k6 (summary will be written to soak/results)"
  docker compose --profile soak stop -t 30 k6 || true
  docker compose --profile soak rm -f k6 >/dev/null 2>&1 || true
}

cmd_report() {
  bold "Last k6 summary"
  if [[ -f soak/results/last-summary.txt ]]; then cat soak/results/last-summary.txt; else echo "   none yet"; fi
  bold "Watchdog anomalies"
  local f; f=$(ls -t soak/results/watch-*.jsonl 2>/dev/null | head -1 || true)
  if [[ -n "$f" ]]; then
    echo "   file: $f"
    python3 -I - "$f" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print(f"   samples: {len(rows)}  from {rows[0]['ts'] if rows else '-'} to {rows[-1]['ts'] if rows else '-'}")
hits = [(r['ts'], a) for r in rows for a in r.get('anomalies', [])]
print("   no anomalies recorded" if not hits else f"   {len(hits)} anomaly samples:")
for ts, a in hits[:50]:
    print(f"     {ts}  {a}")
if len(hits) > 50: print(f"     ... {len(hits)-50} more")
EOF
  else
    echo "   no watchdog log yet (scripts/soak.sh watch)"
  fi
  bold "Summaries on disk"; ls -1 soak/results/ 2>/dev/null | sed 's/^/   /'
}

cmd_reset() {
  bold "Soft reset: wiping orders, carts, refunds and customers; reseeding catalog"
  if docker compose --profile soak ps --status running --format '{{.Service}}' 2>/dev/null | grep -qx k6; then
    echo "stop the soak first: scripts/soak.sh stop" >&2; exit 1
  fi
  curl -fsS -X POST "$API/api/v1/test/reset?dropCustomers=true" && echo
  echo "   done (requires API_PROFILES=loadtest, the default)"
}

cmd_clean() {
  bold "Hard reset: stopping all containers and deleting volumes${1:+ and images}"
  compose down -v --remove-orphans ${1:+--rmi local}
  rm -f soak/results/*.json soak/results/*.jsonl soak/results/*.txt
  echo "   clean slate. Start again with: scripts/soak.sh up"
}

case "${1:-}" in
  up) cmd_up ;;
  start) cmd_start "${2:-}" ;;
  status) cmd_status ;;
  watch) shift; cmd_watch "$@" ;;
  logs) cmd_logs "${2:-}" ;;
  stop) cmd_stop ;;
  report) cmd_report ;;
  reset) cmd_reset ;;
  clean) [[ "${2:-}" == "--images" ]] && cmd_clean images || cmd_clean ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
