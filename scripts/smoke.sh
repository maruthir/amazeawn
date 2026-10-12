#!/usr/bin/env bash
# End-to-end smoke test against a running stack: register, shop, order, confirm, cancel, refund, admin.
# Usage: scripts/smoke.sh [BASE_URL]   (default http://localhost:8080)
set -euo pipefail

BASE="${1:-http://localhost:8080}/api/v1"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@amazeawn.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"

json() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {"d": d}))' "$1"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32m✔\033[0m %s\n' "$*"; }
fail() { printf '   \033[31m✘ %s\033[0m\n' "$*"; exit 1; }

wait_status() { # order_id token expected... -> polls until status is one of expected
  local id="$1" token="$2"; shift 2
  for _ in $(seq 1 60); do
    s=$(curl -fsS "$BASE/orders/$id" -H "Authorization: Bearer $token" | json 'd["status"]')
    for want in "$@"; do [[ "$s" == "$want" ]] && { echo "$s"; return 0; }; done
    sleep 0.5
  done
  fail "order $id did not reach $* (last: $s)"
}

step "Health"
curl -fsS "${BASE%/api/v1}/actuator/health" | json 'd["status"]' | grep -q UP && ok "api UP"

step "Register + login customer"
EMAIL="smoke-$(date +%s)@example.com"
curl -fsS -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"secret123\",\"fullName\":\"Smoke Tester\"}" >/dev/null
TOK=$(curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"secret123\"}" | json 'd["accessToken"]')
AUTH=(-H "Authorization: Bearer $TOK")
ok "customer $EMAIL"

step "Admin login"
ATOK=$(curl -fsS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | json 'd["accessToken"]')
AADMIN=(-H "Authorization: Bearer $ATOK")
ok "admin"

step "Catalog"
PRODUCT=$(curl -fsS "$BASE/products?size=1&inStock=true" | json 'd["content"][0]["id"]')
NAME=$(curl -fsS "$BASE/products/$PRODUCT" | json 'd["name"]')
ok "product $NAME ($PRODUCT)"

step "Cart"
curl -fsS -X POST "$BASE/cart/items" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"productId\":\"$PRODUCT\",\"quantity\":2}" | json 'd["itemCount"]' | grep -q 2 && ok "2 items in cart"

step "Place order (approve card) -> expect CONFIRMED"
KEY=$(uuidgen 2>/dev/null || date +%s%N)
RESP=$(curl -sS -w '\n%{http_code}' -X POST "$BASE/orders" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"paymentCard":"4242424242424242","shippingAddress":"1 Smoke St, Testville 00000, US"}')
CODE=$(tail -n1 <<<"$RESP"); BODY=$(sed '$d' <<<"$RESP")
[[ "$CODE" == "202" ]] || fail "expected 202, got $CODE: $BODY"
ORDER=$(json 'd["orderId"]' <<<"$BODY")
ok "accepted $ORDER"
REPLAY=$(curl -fsS -X POST "$BASE/orders" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"paymentCard":"4242424242424242","shippingAddress":"x"}' | json 'd["replayed"]')
[[ "$REPLAY" == "True" ]] && ok "idempotent replay returned the same order"
S=$(wait_status "$ORDER" "$TOK" CONFIRMED REJECTED)
[[ "$S" == "CONFIRMED" ]] || fail "expected CONFIRMED, got $S"
ok "order CONFIRMED"

step "Place order (decline card) -> expect REJECTED / PAYMENT_DECLINED"
curl -fsS -X POST "$BASE/cart/items" "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"productId\":\"$PRODUCT\",\"quantity\":1}" >/dev/null
ORDER2=$(curl -fsS -X POST "$BASE/orders" "${AUTH[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen 2>/dev/null || date +%s%N)" \
  -d '{"paymentCard":"4000000000000002","shippingAddress":"1 Smoke St"}' | json 'd["orderId"]')
S=$(wait_status "$ORDER2" "$TOK" CONFIRMED REJECTED)
R=$(curl -fsS "$BASE/orders/$ORDER2" "${AUTH[@]}" | json 'd["rejectionReason"]')
[[ "$S" == "REJECTED" && "$R" == "PAYMENT_DECLINED" ]] && ok "order REJECTED ($R)" || fail "got $S / $R"

step "Cancel a confirmed order"
curl -fsS -X POST "$BASE/cart/items" "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"productId\":\"$PRODUCT\",\"quantity\":1}" >/dev/null
ORDER3=$(curl -fsS -X POST "$BASE/orders" "${AUTH[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen 2>/dev/null || date +%s%N)" \
  -d '{"paymentCard":"4242424242424242","shippingAddress":"1 Smoke St"}' | json 'd["orderId"]')
wait_status "$ORDER3" "$TOK" CONFIRMED >/dev/null
curl -fsS -X POST "$BASE/orders/$ORDER3/cancel" "${AUTH[@]}" >/dev/null
S=$(wait_status "$ORDER3" "$TOK" CANCELLED)
ok "order $S"

step "Refund flow: customer requests, admin approves, processor executes"
curl -fsS -X POST "$BASE/orders/$ORDER/refund" "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"reason":"smoke"}' >/dev/null
REFUND=$(curl -fsS "$BASE/admin/refunds?status=REQUESTED" "${AADMIN[@]}" | json '[r["id"] for r in d["content"] if r["orderId"]=="'"$ORDER"'"][0]')
curl -fsS -X POST "$BASE/admin/refunds/$REFUND/approve" "${AADMIN[@]}" -H 'Content-Type: application/json' -d '{"note":"ok"}' >/dev/null
S=$(wait_status "$ORDER" "$TOK" REFUNDED)
ok "order $S; refund $(curl -fsS "$BASE/orders/$ORDER/refund" "${AUTH[@]}" | json 'd[0]["status"]')"

step "Admin: stats, inventory adjust, customer list"
curl -fsS "$BASE/admin/stats" "${AADMIN[@]}" | json 'd["ordersByStatus"]["REFUNDED"]' >/dev/null && ok "stats"
curl -fsS -X POST "$BASE/admin/products/$PRODUCT/inventory" "${AADMIN[@]}" -H 'Content-Type: application/json' -d '{"delta":5}' >/dev/null && ok "inventory adjust queued"
curl -fsS "$BASE/admin/customers?size=1" "${AADMIN[@]}" | json 'd["totalElements"]' >/dev/null && ok "customers"
curl -fsS "${BASE%/api/v1}/actuator/prometheus" | grep -c amazeawn_orders_placed_total >/dev/null && ok "prometheus metrics exposed"

printf '\n\033[32mSmoke test passed.\033[0m\n'
