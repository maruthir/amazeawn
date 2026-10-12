/*
 * Amazeawn soak test: a steady, realistic day of traffic — not a stress test.
 *
 * Three concurrent scenarios run for SOAK_DURATION (default 8h):
 *   browsers  – anonymous catalog browsing at SOAK_BROWSE_RPS requests/s
 *   shoppers  – SOAK_ORDERS_PER_MIN full purchases/min: login, browse, cart, order, poll to
 *               settlement, sometimes cancel or request a refund
 *   admin     – every SOAK_ADMIN_INTERVAL_S: stats, approve/reject refunds, ship/deliver overrides;
 *               every SOAK_RESTOCK_EVERY_MIN: bulk restock so stock stays steady all day
 *
 * The point is the custom metrics: order_settle_time, orders_stuck, orders_by_outcome, plus the
 * thresholds below, which are reported (not aborting) so the run keeps going and the monitoring
 * stack has something to show when things go wrong.
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';
import { randomItem, randomIntBetween, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = (__ENV.BASE_URL || 'http://localhost:8080') + '/api/v1';
const DURATION = __ENV.SOAK_DURATION || '8h';
const USERS = parseInt(__ENV.SOAK_USERS || '50', 10);
const BROWSE_RPS = parseFloat(__ENV.SOAK_BROWSE_RPS || '5');
const ORDERS_PER_MIN = parseFloat(__ENV.SOAK_ORDERS_PER_MIN || '30');
const ADMIN_INTERVAL_S = parseInt(__ENV.SOAK_ADMIN_INTERVAL_S || '30', 10);
const RESTOCK_EVERY_MIN = parseInt(__ENV.SOAK_RESTOCK_EVERY_MIN || '60', 10);
const RESTOCK_LEVEL = parseInt(__ENV.SOAK_RESTOCK_LEVEL || '1000', 10);
const SETTLE_TIMEOUT_S = parseInt(__ENV.SOAK_SETTLE_TIMEOUT_S || '60', 10);
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@amazeawn.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin123';

const APPROVE_CARD = '4242424242424242';
const DECLINE_CARD = '4000000000000002';

// ---- custom metrics -------------------------------------------------------------------------
const settleTime = new Trend('order_settle_time', true);   // ms from 202 to a terminal status
const ordersPlaced = new Counter('orders_placed');
const ordersByOutcome = new Counter('orders_by_outcome');  // tagged outcome=CONFIRMED|REJECTED:*|...
const ordersStuck = new Counter('orders_stuck');           // never settled within SETTLE_TIMEOUT_S
const cancelOk = new Rate('cancel_success');
const refundOk = new Rate('refund_completed');
const loginFailures = new Counter('login_failures');
const adminErrors = new Counter('admin_errors');

export const options = {
  scenarios: {
    browsers: {
      executor: 'constant-arrival-rate',
      exec: 'browse',
      rate: Math.max(1, Math.round(BROWSE_RPS * 60)), timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: 5, maxVUs: 30,
      tags: { scenario: 'browsers' },
    },
    shoppers: {
      executor: 'constant-arrival-rate',
      exec: 'shop',
      rate: Math.max(1, Math.round(ORDERS_PER_MIN)), timeUnit: '1m',
      duration: DURATION,
      // Each iteration may wait up to SETTLE_TIMEOUT_S, so keep enough VUs in reserve.
      preAllocatedVUs: 20, maxVUs: 80,
      tags: { scenario: 'shoppers' },
    },
    admin: {
      executor: 'constant-vus',
      exec: 'admin',
      vus: 1,
      duration: DURATION,
      tags: { scenario: 'admin' },
    },
  },
  // Reported, never aborting: a soak should keep running so the failure is observable.
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
    'http_req_duration{scenario:browsers}': ['p(95)<800'],
    'http_req_duration{scenario:shoppers}': ['p(95)<1000'],
    order_settle_time: ['p(95)<10000'],
    orders_stuck: ['count<1'],
    login_failures: ['count<1'],
    admin_errors: ['count<1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: false,
};

// ---- helpers ---------------------------------------------------------------------------------
const JSON_HEADERS = { 'Content-Type': 'application/json' };

function json(res) {
  try { return res.json(); } catch (e) { return null; }
}

function login(email, password) {
  const res = http.post(`${BASE}/auth/login`, JSON.stringify({ email, password }), { headers: JSON_HEADERS, tags: { name: 'POST /auth/login' } });
  if (res.status !== 200) {
    loginFailures.add(1);
    return null;
  }
  return json(res).accessToken;
}

function authed(token, extra = {}) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}`, ...extra } };
}

// ---- setup: a pool of registered customers with a default address --------------------------
export function setup() {
  const runId = Date.now().toString(36);
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const email = `soak-${runId}-${i}@example.com`;
    const password = 'soak-pass-123';
    const reg = http.post(`${BASE}/auth/register`, JSON.stringify({ email, password, fullName: `Soak User ${i}` }), { headers: JSON_HEADERS, tags: { name: 'POST /auth/register' } });
    if (reg.status !== 201) {
      throw new Error(`setup: register failed for ${email}: ${reg.status} ${reg.body}`);
    }
    const token = login(email, password);
    http.post(`${BASE}/me/addresses`, JSON.stringify({ label: 'Home', line1: `${i + 1} Soak Street`, city: 'Testville', postalCode: '00000', country: 'US', isDefault: true }), { ...authed(token), tags: { name: 'POST /me/addresses' } });
    users.push({ email, password });
  }
  const adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!adminToken) {
    throw new Error('setup: admin login failed; check ADMIN_EMAIL/ADMIN_PASSWORD');
  }
  console.log(`setup: registered ${users.length} customers; soak duration ${DURATION}`);
  return { users };
}

// ---- scenario: anonymous browsing ------------------------------------------------------------
export function browse() {
  group('browse', () => {
    const page = randomIntBetween(0, 10);
    const sort = randomItem(['name,asc', 'price,asc', 'price,desc', 'createdAt,desc']);
    const list = http.get(`${BASE}/products?page=${page}&size=24&sort=${sort}`, { tags: { name: 'GET /products' } });
    check(list, { 'products 200': (r) => r.status === 200 });
    const body = json(list);
    if (body && body.content && body.content.length) {
      const p = randomItem(body.content);
      const detail = http.get(`${BASE}/products/${p.id}`, { tags: { name: 'GET /products/{id}' } });
      check(detail, { 'product 200': (r) => r.status === 200 });
    }
    if (Math.random() < 0.3) {
      const q = randomItem(['kettle', 'lamp', 'classic', 'ultra', 'camera', 'mug']);
      http.get(`${BASE}/products?q=${q}&inStock=true`, { tags: { name: 'GET /products?q' } });
    }
    if (Math.random() < 0.2) {
      http.get(`${BASE}/categories`, { tags: { name: 'GET /categories' } });
    }
  });
}

// ---- scenario: a full purchase ---------------------------------------------------------------
const vuTokens = {}; // per-VU cache: email -> token

export function shop(data) {
  const user = data.users[(__VU + __ITER) % data.users.length];
  let token = vuTokens[user.email] || (vuTokens[user.email] = login(user.email, user.password));
  if (!token) return;

  // A cached token may have expired (15 min TTL); re-login once on 401.
  const withAuth = (fn) => {
    let res = fn(token);
    if (res.status === 401) {
      token = vuTokens[user.email] = login(user.email, user.password);
      if (!token) return res;
      res = fn(token);
    }
    return res;
  };

  // Browse a little, pick 1-3 products in stock.
  const list = http.get(`${BASE}/products?page=${randomIntBetween(0, 5)}&size=24&inStock=true`, { tags: { name: 'GET /products' } });
  const products = (json(list) || {}).content || [];
  if (!products.length) return;

  withAuth((t) => http.del(`${BASE}/cart`, null, { ...authed(t), tags: { name: 'DELETE /cart' } }));
  const n = randomIntBetween(1, 3);
  for (let i = 0; i < n; i++) {
    const p = randomItem(products);
    const res = withAuth((t) => http.post(`${BASE}/cart/items`, JSON.stringify({ productId: p.id, quantity: randomIntBetween(1, 2) }), { ...authed(t), tags: { name: 'POST /cart/items' } }));
    check(res, { 'cart add 200': (r) => r.status === 200 });
  }
  withAuth((t) => http.get(`${BASE}/cart`, { ...authed(t), tags: { name: 'GET /cart' } }));

  // 75% guaranteed approve, 10% guaranteed decline, 15% random (exercises PAYMENT_FAILURE_RATE).
  const roll = Math.random();
  const card = roll < 0.75 ? APPROVE_CARD : roll < 0.85 ? DECLINE_CARD : '4111111111111111';
  const key = uuidv4();
  const placed = withAuth((t) => http.post(`${BASE}/orders`, JSON.stringify({ paymentCard: card }), { ...authed(t, { 'Idempotency-Key': key }), tags: { name: 'POST /orders' } }));
  if (!check(placed, { 'order 202': (r) => r.status === 202 })) return;
  ordersPlaced.add(1);
  const orderId = json(placed).orderId;
  const started = Date.now();

  // Poll until a terminal status, like the UI does.
  let status = 'PENDING';
  let order = null;
  while (Date.now() - started < SETTLE_TIMEOUT_S * 1000) {
    sleep(1);
    const res = withAuth((t) => http.get(`${BASE}/orders/${orderId}`, { ...authed(t), tags: { name: 'GET /orders/{id}' } }));
    order = json(res);
    if (!order) continue;
    status = order.status;
    if (status !== 'PENDING') break;
  }
  if (status === 'PENDING') {
    ordersStuck.add(1);
    console.error(`order ${orderId} still PENDING after ${SETTLE_TIMEOUT_S}s`);
    return;
  }
  settleTime.add(Date.now() - started);
  ordersByOutcome.add(1, { outcome: status === 'REJECTED' ? `REJECTED:${order.rejectionReason}` : status });

  if (status !== 'CONFIRMED') return;

  // Post-purchase behaviour: 10% cancel, 10% ask for a refund, the rest check their order list.
  const after = Math.random();
  if (after < 0.10) {
    const res = withAuth((t) => http.post(`${BASE}/orders/${orderId}/cancel`, null, { ...authed(t), tags: { name: 'POST /orders/{id}/cancel' } }));
    const accepted = res.status === 202 || res.status === 409; // 409 = already shipped by the fulfilment timer
    cancelOk.add(accepted);
  } else if (after < 0.20) {
    const res = withAuth((t) => http.post(`${BASE}/orders/${orderId}/refund`, JSON.stringify({ reason: 'soak' }), { ...authed(t), tags: { name: 'POST /orders/{id}/refund' } }));
    refundOk.add(res.status === 201);
  } else {
    withAuth((t) => http.get(`${BASE}/orders?size=10`, { ...authed(t), tags: { name: 'GET /orders' } }));
  }
}

// ---- scenario: the admin's day ---------------------------------------------------------------
let adminToken = null;
let lastRestock = Date.now();

export function admin() {
  if (!adminToken) adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
  if (!adminToken) { sleep(ADMIN_INTERVAL_S); return; }
  const a = (fn) => {
    let res = fn(adminToken);
    if (res.status === 401) { adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD); res = adminToken ? fn(adminToken) : res; }
    if (res.status >= 500) adminErrors.add(1);
    return res;
  };

  group('admin', () => {
    const stats = a((t) => http.get(`${BASE}/admin/stats`, { ...authed(t), tags: { name: 'GET /admin/stats' } }));
    check(stats, { 'stats 200': (r) => r.status === 200 });

    // Work the refund queue: approve 80%, reject 20%.
    const refunds = a((t) => http.get(`${BASE}/admin/refunds?status=REQUESTED&size=50`, { ...authed(t), tags: { name: 'GET /admin/refunds' } }));
    for (const r of ((json(refunds) || {}).content || [])) {
      const decision = Math.random() < 0.8 ? 'approve' : 'reject';
      a((t) => http.post(`${BASE}/admin/refunds/${r.id}/${decision}`, JSON.stringify({ note: 'soak' }), { ...authed(t), tags: { name: `POST /admin/refunds/{id}/${decision}` } }));
    }

    // Occasionally override fulfilment on a confirmed order, like a warehouse operator would.
    if (Math.random() < 0.3) {
      const confirmed = a((t) => http.get(`${BASE}/admin/orders?status=CONFIRMED&size=5`, { ...authed(t), tags: { name: 'GET /admin/orders' } }));
      const list = (json(confirmed) || {}).content || [];
      if (list.length) {
        const o = randomItem(list);
        a((t) => http.put(`${BASE}/admin/orders/${o.id}/status`, JSON.stringify({ status: 'SHIPPED' }), { ...authed(t), tags: { name: 'PUT /admin/orders/{id}/status' } }));
      }
    }

    a((t) => http.get(`${BASE}/admin/customers?size=20`, { ...authed(t), tags: { name: 'GET /admin/customers' } }));

    if (RESTOCK_EVERY_MIN > 0 && Date.now() - lastRestock > RESTOCK_EVERY_MIN * 60 * 1000) {
      const res = a((t) => http.post(`${BASE}/admin/inventory/restock`, JSON.stringify({ level: RESTOCK_LEVEL }), { ...authed(t), tags: { name: 'POST /admin/inventory/restock' } }));
      if (res.status === 202) lastRestock = Date.now();
      console.log(`admin: bulk restock to ${RESTOCK_LEVEL} -> ${res.status}`);
    }
  });
  sleep(ADMIN_INTERVAL_S);
}

// ---- summary ---------------------------------------------------------------------------------
export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, stat) => (m[name] && m[name].values && m[name].values[stat] !== undefined) ? m[name].values[stat] : 'n/a';
  const fmt = (x) => (typeof x === 'number' ? x.toFixed(x < 10 ? 2 : 0) : x);
  const lines = [
    '',
    '=== Amazeawn soak summary ===',
    `duration:            ${DURATION}`,
    `http requests:       ${v('http_reqs', 'count')} (failed rate ${fmt(v('http_req_failed', 'rate'))})`,
    `http p95 / p99 (ms): ${fmt(v('http_req_duration', 'p(95)'))} / ${fmt(v('http_req_duration', 'p(99)'))}`,
    `orders placed:       ${v('orders_placed', 'count')}`,
    `orders stuck:        ${v('orders_stuck', 'count')}`,
    `settle p95 (ms):     ${fmt(v('order_settle_time', 'p(95)'))}  (avg ${fmt(v('order_settle_time', 'avg'))}, max ${fmt(v('order_settle_time', 'max'))})`,
    `cancel success rate: ${fmt(v('cancel_success', 'rate'))}`,
    `refund request rate: ${fmt(v('refund_completed', 'rate'))}`,
    `login failures:      ${v('login_failures', 'count')}`,
    `admin 5xx:           ${v('admin_errors', 'count')}`,
    '',
    'thresholds:',
  ];
  for (const [name, metric] of Object.entries(m)) {
    if (!metric.thresholds) continue;
    for (const [expr, t] of Object.entries(metric.thresholds)) {
      lines.push(`  ${t.ok ? 'PASS' : 'FAIL'}  ${name}: ${expr}`);
    }
  }
  const text = lines.join('\n') + '\n';
  return { stdout: text, '/results/last-summary.txt': text };
}
