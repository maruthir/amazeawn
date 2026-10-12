/* Amazeawn storefront + admin: a thin client over /api/v1. No framework, no build step. */
(() => {
  const API = '/api/v1';
  const $ = (sel, root = document) => root.querySelector(sel);
  const view = $('#view');

  // ---------- auth + fetch ----------
  const auth = {
    get access() { return localStorage.getItem('access'); },
    get refresh() { return localStorage.getItem('refresh'); },
    set(tokens) { localStorage.setItem('access', tokens.accessToken); localStorage.setItem('refresh', tokens.refreshToken); },
    clear() { localStorage.removeItem('access'); localStorage.removeItem('refresh'); state.me = null; },
    get role() { try { return JSON.parse(atob(this.access.split('.')[1])).role; } catch { return null; } }
  };
  const state = { me: null, cart: null, catalog: { q: '', categoryId: '', page: 0, sort: 'name,asc', inStock: false }, categories: [] };

  async function api(path, { method = 'GET', body, headers = {}, retry = true } = {}) {
    const h = { 'Content-Type': 'application/json', ...headers };
    if (auth.access) h.Authorization = 'Bearer ' + auth.access;
    const res = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
    if (res.status === 401 && retry && auth.refresh) {
      const r = await fetch(API + '/auth/refresh', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ refreshToken: auth.refresh }) });
      if (r.ok) { auth.set(await r.json()); return api(path, { method, body, headers, retry: false }); }
      auth.clear(); renderWho();
    }
    if (res.status === 204) return null;
    const data = res.headers.get('content-type')?.includes('json') ? await res.json() : await res.text();
    if (!res.ok) throw Object.assign(new Error(data?.message || res.statusText), { status: res.status, data });
    return data;
  }

  function toast(msg, ms = 2500) { const t = $('#toast'); t.textContent = msg; t.hidden = false; clearTimeout(t._t); t._t = setTimeout(() => t.hidden = true, ms); }
  const money = (n, c = 'USD') => new Intl.NumberFormat(undefined, { style: 'currency', currency: c }).format(n);
  const when = (iso) => iso ? new Date(iso).toLocaleString() : '—';
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const status = (s) => `<span class="status ${esc(s)}">${esc(s)}</span>`;
  const uuid = () => crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random();

  // ---------- header ----------
  async function loadMe() {
    if (!auth.access) { state.me = null; return; }
    try { state.me = await api('/me'); } catch { state.me = null; }
  }
  function renderWho() {
    $('#who').innerHTML = state.me
      ? `${esc(state.me.fullName)} <span class="muted">(${esc(state.me.role.toLowerCase())})</span> · <a href="#" id="logout">Sign out</a>`
      : `<a href="#/account">Sign in</a>`;
    $('#nav-admin').hidden = !(state.me && state.me.role === 'ADMIN');
    $('#logout')?.addEventListener('click', async (e) => { e.preventDefault(); try { await api('/auth/logout', { method: 'POST', body: { refreshToken: auth.refresh } }); } catch {} auth.clear(); renderWho(); refreshCartCount(); location.hash = '#/shop'; });
  }
  async function refreshCartCount() {
    if (!state.me || state.me.role !== 'CUSTOMER') { $('#cart-count').textContent = '0'; return; }
    try { state.cart = await api('/cart'); $('#cart-count').textContent = state.cart.itemCount; } catch { $('#cart-count').textContent = '0'; }
  }

  // ---------- shop ----------
  async function renderShop() {
    if (!state.categories.length) state.categories = await api('/categories');
    const c = state.catalog;
    const params = new URLSearchParams({ page: c.page, size: 24, sort: c.sort, inStock: c.inStock });
    if (c.q) params.set('q', c.q);
    if (c.categoryId) params.set('categoryId', c.categoryId);
    const page = await api('/products?' + params);
    view.innerHTML = `
      <h1>Shop</h1>
      <div class="toolbar">
        <input id="q" placeholder="Search products" value="${esc(c.q)}">
        <select id="cat"><option value="">All categories</option>${state.categories.map(x => `<option value="${x.id}" ${x.id === c.categoryId ? 'selected' : ''}>${esc(x.name)}</option>`).join('')}</select>
        <select id="sort">
          <option value="name,asc" ${c.sort === 'name,asc' ? 'selected' : ''}>Name</option>
          <option value="price,asc" ${c.sort === 'price,asc' ? 'selected' : ''}>Price: low to high</option>
          <option value="price,desc" ${c.sort === 'price,desc' ? 'selected' : ''}>Price: high to low</option>
          <option value="createdAt,desc" ${c.sort === 'createdAt,desc' ? 'selected' : ''}>Newest</option>
        </select>
        <label class="row"><input type="checkbox" id="instock" ${c.inStock ? 'checked' : ''}> In stock only</label>
        <span class="muted">${page.totalElements} products</span>
      </div>
      <div class="grid">${page.content.map(p => `
        <div class="card product">
          <div class="name"><a href="#/product/${p.id}">${esc(p.name)}</a></div>
          <div class="meta">${esc(p.categoryName)} · ${esc(p.sku)}</div>
          <div class="price">${money(p.price, p.currency)}</div>
          <div class="meta">${p.stock > 0 ? p.stock + ' in stock' : '<b>Out of stock</b>'}</div>
          <button class="primary add" data-id="${p.id}" ${p.stock > 0 ? '' : 'disabled'}>Add to cart</button>
        </div>`).join('')}</div>
      <div class="pager">
        <button id="prev" ${page.first ? 'disabled' : ''}>Previous</button>
        <span class="muted">Page ${page.number + 1} of ${Math.max(page.totalPages, 1)}</span>
        <button id="next" ${page.last ? 'disabled' : ''}>Next</button>
      </div>`;
    let t; $('#q').addEventListener('input', (e) => { clearTimeout(t); t = setTimeout(() => { c.q = e.target.value; c.page = 0; renderShop(); }, 300); });
    $('#cat').addEventListener('change', (e) => { c.categoryId = e.target.value; c.page = 0; renderShop(); });
    $('#sort').addEventListener('change', (e) => { c.sort = e.target.value; c.page = 0; renderShop(); });
    $('#instock').addEventListener('change', (e) => { c.inStock = e.target.checked; c.page = 0; renderShop(); });
    $('#prev').addEventListener('click', () => { c.page--; renderShop(); });
    $('#next').addEventListener('click', () => { c.page++; renderShop(); });
    view.querySelectorAll('.add').forEach(b => b.addEventListener('click', () => addToCart(b.dataset.id, 1)));
  }

  async function renderProduct(id) {
    const p = await api('/products/' + id);
    view.innerHTML = `
      <a href="#/shop" class="muted">← Back to shop</a>
      <div class="card" style="margin-top:12px">
        <h1>${esc(p.name)}</h1>
        <p class="muted">${esc(p.categoryName)} · ${esc(p.sku)}</p>
        <p>${esc(p.description)}</p>
        <p class="price" style="font-size:22px;font-weight:700">${money(p.price, p.currency)}</p>
        <p class="muted">${p.stock > 0 ? p.stock + ' in stock' : 'Out of stock'}</p>
        <div class="row"><input type="number" id="qty" min="1" value="1"><button class="primary" id="add" ${p.stock > 0 ? '' : 'disabled'}>Add to cart</button></div>
      </div>`;
    $('#add').addEventListener('click', () => addToCart(p.id, parseInt($('#qty').value, 10) || 1));
  }

  async function addToCart(productId, quantity) {
    if (!state.me) { toast('Sign in to shop'); location.hash = '#/account'; return; }
    if (state.me.role !== 'CUSTOMER') { toast('Admins cannot shop — register a customer account'); return; }
    try { state.cart = await api('/cart/items', { method: 'POST', body: { productId, quantity } }); $('#cart-count').textContent = state.cart.itemCount; toast('Added to cart'); }
    catch (e) { toast(e.message); }
  }

  // ---------- cart + checkout ----------
  async function renderCart() {
    if (!requireCustomer()) return;
    const cart = state.cart = await api('/cart');
    const addresses = await api('/me/addresses');
    view.innerHTML = `
      <h1>Cart</h1>
      ${cart.items.length === 0 ? '<p class="muted">Your cart is empty. <a href="#/shop">Go shopping</a>.</p>' : `
      <div class="card"><table>
        <thead><tr><th>Item</th><th>Price</th><th>Qty</th><th>Total</th><th></th></tr></thead>
        <tbody>${cart.items.map(i => `<tr>
          <td>${esc(i.name)}<br><span class="muted">${esc(i.sku)} · ${i.stock} in stock</span></td>
          <td>${money(i.unitPrice, cart.currency)}</td>
          <td><input type="number" min="0" value="${i.quantity}" data-item="${i.itemId}" class="qty"></td>
          <td>${money(i.lineTotal, cart.currency)}</td>
          <td><button class="small rm" data-item="${i.itemId}">Remove</button></td></tr>`).join('')}</tbody>
      </table>
      <div class="row between" style="margin-top:12px"><button id="clear">Clear cart</button><b>Subtotal: ${money(cart.subtotal, cart.currency)}</b></div></div>
      <h2>Checkout</h2>
      <form class="card stack" id="checkout">
        <label>Shipping address
          <select name="addressId">${addresses.map(a => `<option value="${a.id}" ${a.isDefault ? 'selected' : ''}>${esc(a.label)}: ${esc(a.line1)}, ${esc(a.city)}</option>`).join('')}<option value="">— type one below —</option></select></label>
        <label>Or enter an address<input name="shippingAddress" placeholder="1 Main St, Springfield 12345, US"></label>
        <label>Card number <span class="muted">(simulated: 4242 4242 4242 4242 approves, 4000 0000 0000 0002 declines, anything else is random)</span>
          <input name="paymentCard" value="4242424242424242" required></label>
        <div><button class="primary" type="submit">Place order</button></div>
      </form>`}`;
    view.querySelectorAll('.qty').forEach(el => el.addEventListener('change', async () => { try { await api('/cart/items/' + el.dataset.item, { method: 'PUT', body: { quantity: parseInt(el.value, 10) || 0 } }); renderCart(); refreshCartCount(); } catch (e) { toast(e.message); } }));
    view.querySelectorAll('.rm').forEach(el => el.addEventListener('click', async () => { await api('/cart/items/' + el.dataset.item, { method: 'DELETE' }); renderCart(); refreshCartCount(); }));
    $('#clear')?.addEventListener('click', async () => { await api('/cart', { method: 'DELETE' }); renderCart(); refreshCartCount(); });
    $('#checkout')?.addEventListener('submit', async (e) => {
      e.preventDefault();
      const f = new FormData(e.target);
      const body = { paymentCard: f.get('paymentCard'), addressId: f.get('addressId') || null, shippingAddress: f.get('shippingAddress') || null };
      try {
        const r = await api('/orders', { method: 'POST', body, headers: { 'Idempotency-Key': uuid() } });
        toast('Order accepted — settling…'); refreshCartCount(); location.hash = '#/orders/' + r.orderId;
      } catch (err) { toast(err.message, 4000); }
    });
  }

  // ---------- orders ----------
  async function renderOrders() {
    if (!requireCustomer()) return;
    const page = await api('/orders?size=50');
    view.innerHTML = `<h1>Your orders</h1>
      ${page.content.length === 0 ? '<p class="muted">No orders yet.</p>' : `<div class="card"><table>
        <thead><tr><th>Placed</th><th>Order</th><th>Status</th><th>Total</th></tr></thead>
        <tbody>${page.content.map(o => `<tr><td>${when(o.createdAt)}</td><td><a href="#/orders/${o.id}"><code>${o.id.slice(0, 8)}</code></a></td><td>${status(o.status)}</td><td>${money(o.totalAmount, o.currency)}</td></tr>`).join('')}</tbody></table></div>`}`;
  }

  let pollTimer;
  async function renderOrder(id) {
    if (!requireCustomer()) return;
    clearInterval(pollTimer);
    const draw = async () => {
      const o = await api('/orders/' + id);
      const refunds = await api(`/orders/${id}/refund`);
      const openRefund = refunds.find(r => r.status === 'REQUESTED' || r.status === 'APPROVED');
      view.innerHTML = `
        <a href="#/orders" class="muted">← All orders</a>
        <div class="card" style="margin-top:12px">
          <div class="row between"><h1 style="margin:0">Order <code>${o.id.slice(0, 8)}</code></h1>${status(o.status)}</div>
          <p class="muted">Placed ${when(o.createdAt)} · card ending ${esc(o.cardLast4)} · ships to ${esc(o.shippingAddress)}</p>
          ${o.rejectionReason ? `<p><b>Rejected:</b> ${esc(o.rejectionReason)}</p>` : ''}
          <table><thead><tr><th>Item</th><th>Price</th><th>Qty</th><th>Total</th></tr></thead>
          <tbody>${o.items.map(i => `<tr><td>${esc(i.name)}<br><span class="muted">${esc(i.sku)}</span></td><td>${money(i.unitPrice, o.currency)}</td><td>${i.quantity}</td><td>${money(i.lineTotal, o.currency)}</td></tr>`).join('')}</tbody></table>
          <p style="text-align:right"><b>Total ${money(o.totalAmount, o.currency)}</b></p>
          <p class="muted">Confirmed ${when(o.confirmedAt)} · Shipped ${when(o.shippedAt)} · Delivered ${when(o.deliveredAt)}</p>
          <div class="row">
            ${['PENDING', 'CONFIRMED'].includes(o.status) ? '<button id="cancel">Cancel order</button>' : ''}
            ${['CONFIRMED', 'SHIPPED', 'DELIVERED'].includes(o.status) && !openRefund ? '<button id="refund">Request refund</button>' : ''}
            ${o.status === 'PENDING' || o.status === 'CANCEL_REQUESTED' ? '<span class="muted">Waiting for the processor… (auto-refreshing)</span>' : ''}
          </div>
        </div>
        ${refunds.length ? `<h2>Refunds</h2><div class="card"><table><thead><tr><th>Requested</th><th>Amount</th><th>Status</th><th>Reason</th><th>Admin note</th></tr></thead>
          <tbody>${refunds.map(r => `<tr><td>${when(r.createdAt)}</td><td>${money(r.amount, o.currency)}</td><td>${status(r.status)}</td><td>${esc(r.reason || '')}</td><td>${esc(r.adminNote || r.failureReason || '')}</td></tr>`).join('')}</tbody></table></div>` : ''}`;
      $('#cancel')?.addEventListener('click', async () => { try { await api(`/orders/${id}/cancel`, { method: 'POST' }); toast('Cancel requested'); draw(); } catch (e) { toast(e.message); } });
      $('#refund')?.addEventListener('click', async () => { const reason = prompt('Reason for refund?') ?? ''; try { await api(`/orders/${id}/refund`, { method: 'POST', body: { reason } }); toast('Refund requested'); draw(); } catch (e) { toast(e.message); } });
      if (!['PENDING', 'CANCEL_REQUESTED'].includes(o.status) && !openRefund) clearInterval(pollTimer);
    };
    await draw();
    pollTimer = setInterval(() => { if (location.hash === '#/orders/' + id) draw().catch(() => clearInterval(pollTimer)); else clearInterval(pollTimer); }, 2000);
  }

  // ---------- account ----------
  async function renderAccount() {
    if (!state.me) {
      view.innerHTML = `<h1>Account</h1><div class="two">
        <form class="card stack" id="login"><h2 style="margin-top:0">Sign in</h2>
          <label>Email<input name="email" type="email" required value=""></label>
          <label>Password<input name="password" type="password" required></label>
          <div><button class="primary">Sign in</button></div>
          <p class="muted">Admin: the email/password from <code>ADMIN_EMAIL</code> / <code>ADMIN_PASSWORD</code>.</p></form>
        <form class="card stack" id="register"><h2 style="margin-top:0">Create a customer account</h2>
          <label>Full name<input name="fullName" required></label>
          <label>Email<input name="email" type="email" required></label>
          <label>Password<input name="password" type="password" minlength="6" required></label>
          <div><button class="primary">Register</button></div></form></div>`;
      $('#login').addEventListener('submit', async (e) => { e.preventDefault(); const f = Object.fromEntries(new FormData(e.target)); try { auth.set(await api('/auth/login', { method: 'POST', body: f })); await loadMe(); renderWho(); refreshCartCount(); toast('Signed in'); location.hash = state.me.role === 'ADMIN' ? '#/admin' : '#/shop'; } catch (err) { toast(err.message); } });
      $('#register').addEventListener('submit', async (e) => { e.preventDefault(); const f = Object.fromEntries(new FormData(e.target)); try { await api('/auth/register', { method: 'POST', body: f }); auth.set(await api('/auth/login', { method: 'POST', body: { email: f.email, password: f.password } })); await loadMe(); renderWho(); toast('Welcome!'); location.hash = '#/shop'; } catch (err) { toast(err.message); } });
      return;
    }
    const addresses = state.me.role === 'CUSTOMER' ? await api('/me/addresses') : [];
    view.innerHTML = `<h1>Account</h1>
      <form class="card stack" id="profile"><label>Full name<input name="fullName" value="${esc(state.me.fullName)}"></label>
        <p class="muted">${esc(state.me.email)} · role ${esc(state.me.role)} · joined ${when(state.me.createdAt)}</p>
        <div><button class="primary">Save</button></div></form>
      ${state.me.role !== 'CUSTOMER' ? '' : `<h2>Addresses</h2>
      <div class="card">${addresses.length ? `<table><tbody>${addresses.map(a => `<tr><td><b>${esc(a.label)}</b>${a.isDefault ? ' <span class="status">default</span>' : ''}<br>${esc(a.line1)}${a.line2 ? ', ' + esc(a.line2) : ''}, ${esc(a.city)}${a.region ? ', ' + esc(a.region) : ''} ${esc(a.postalCode)}, ${esc(a.country)}</td><td style="text-align:right"><button class="small del" data-id="${a.id}">Delete</button></td></tr>`).join('')}</tbody></table>` : '<p class="muted">No addresses yet.</p>'}</div>
      <form class="card stack" id="addr" style="margin-top:12px"><h2 style="margin:0">Add address</h2>
        <div class="two"><label>Label<input name="label" value="Home" required></label><label>Country<input name="country" value="US" required></label></div>
        <label>Line 1<input name="line1" required></label><label>Line 2<input name="line2"></label>
        <div class="two"><label>City<input name="city" required></label><label>Region<input name="region"></label></div>
        <label>Postal code<input name="postalCode" required></label>
        <label class="row"><input type="checkbox" name="isDefault" checked> Make default</label>
        <div><button class="primary">Add</button></div></form>`}`;
    $('#profile').addEventListener('submit', async (e) => { e.preventDefault(); try { state.me = await api('/me', { method: 'PUT', body: Object.fromEntries(new FormData(e.target)) }); renderWho(); toast('Saved'); } catch (err) { toast(err.message); } });
    $('#addr')?.addEventListener('submit', async (e) => { e.preventDefault(); const f = Object.fromEntries(new FormData(e.target)); f.isDefault = !!f.isDefault; try { await api('/me/addresses', { method: 'POST', body: f }); renderAccount(); } catch (err) { toast(err.message); } });
    view.querySelectorAll('.del').forEach(b => b.addEventListener('click', async () => { await api('/me/addresses/' + b.dataset.id, { method: 'DELETE' }); renderAccount(); }));
  }

  function requireCustomer() {
    if (!state.me) { location.hash = '#/account'; return false; }
    if (state.me.role !== 'CUSTOMER') { view.innerHTML = '<p class="muted">This area is for customers. Admins manage the store under <a href="#/admin">Admin</a>.</p>'; return false; }
    return true;
  }

  // ---------- admin ----------
  const adminState = { tab: 'stats', orderStatus: '', refundStatus: 'REQUESTED' };
  async function renderAdmin() {
    if (!state.me || state.me.role !== 'ADMIN') { view.innerHTML = '<p class="muted">Sign in as an admin to use this area.</p>'; return; }
    const tabs = ['stats', 'orders', 'refunds', 'products', 'customers'];
    view.innerHTML = `<h1>Admin</h1><div class="tabs">${tabs.map(t => `<button data-tab="${t}" class="${adminState.tab === t ? 'active' : ''}">${t[0].toUpperCase() + t.slice(1)}</button>`).join('')}</div><div id="admin-body"></div>`;
    view.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => { adminState.tab = b.dataset.tab; renderAdmin(); }));
    const body = $('#admin-body');
    try { await ({ stats: adminStats, orders: adminOrders, refunds: adminRefunds, products: adminProducts, customers: adminCustomers })[adminState.tab](body); }
    catch (e) { body.innerHTML = `<p class="muted">${esc(e.message)}</p>`; }
  }

  async function adminStats(body) {
    const s = await api('/admin/stats');
    body.innerHTML = `<div class="stats">
      ${Object.entries(s.ordersByStatus).map(([k, v]) => `<div class="card stat"><div class="k">${k}</div><div class="v">${v}</div></div>`).join('')}
      <div class="card stat"><div class="k">Revenue (confirmed+)</div><div class="v">${money(s.revenue)}</div></div>
      <div class="card stat"><div class="k">Customers</div><div class="v">${s.customers}</div></div>
      <div class="card stat"><div class="k">Active products</div><div class="v">${s.activeProducts}</div></div>
      <div class="card stat"><div class="k">Out of stock</div><div class="v">${s.outOfStockProducts}</div></div>
      <div class="card stat"><div class="k">Refunds awaiting</div><div class="v">${s.refundsRequested}</div></div>
      <div class="card stat"><div class="k">Outbox pending</div><div class="v">${s.outboxPending}</div></div></div>
      <p class="muted" style="margin-top:12px">Metrics: <a href="/actuator/prometheus" target="_blank">/actuator/prometheus</a> · Health: <a href="/actuator/health" target="_blank">/actuator/health</a></p>`;
  }

  async function adminOrders(body) {
    const statuses = ['', 'PENDING', 'CONFIRMED', 'REJECTED', 'CANCEL_REQUESTED', 'CANCELLED', 'SHIPPED', 'DELIVERED', 'REFUNDED'];
    const page = await api('/admin/orders?size=50' + (adminState.orderStatus ? '&status=' + adminState.orderStatus : ''));
    body.innerHTML = `<div class="toolbar"><select id="st">${statuses.map(s => `<option value="${s}" ${s === adminState.orderStatus ? 'selected' : ''}>${s || 'All statuses'}</option>`).join('')}</select><span class="muted">${page.totalElements} orders</span></div>
      <div class="card"><table><thead><tr><th>Placed</th><th>Order</th><th>Customer</th><th>Status</th><th>Total</th><th>Override</th></tr></thead>
      <tbody>${page.content.map(o => `<tr><td>${when(o.createdAt)}</td><td><code>${o.id.slice(0, 8)}</code></td><td><code>${o.userId.slice(0, 8)}</code></td><td>${status(o.status)}${o.rejectionReason ? '<br><span class="muted">' + esc(o.rejectionReason) + '</span>' : ''}</td><td>${money(o.totalAmount, o.currency)}</td>
        <td>${o.status === 'CONFIRMED' ? `<button class="small tr" data-id="${o.id}" data-s="SHIPPED">Ship</button> ` : ''}${['CONFIRMED', 'SHIPPED'].includes(o.status) ? `<button class="small tr" data-id="${o.id}" data-s="DELIVERED">Deliver</button>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
    $('#st').addEventListener('change', (e) => { adminState.orderStatus = e.target.value; renderAdmin(); });
    body.querySelectorAll('.tr').forEach(b => b.addEventListener('click', async () => { try { await api(`/admin/orders/${b.dataset.id}/status`, { method: 'PUT', body: { status: b.dataset.s } }); renderAdmin(); } catch (e) { toast(e.message); } }));
  }

  async function adminRefunds(body) {
    const statuses = ['', 'REQUESTED', 'APPROVED', 'REJECTED', 'COMPLETED', 'FAILED'];
    const page = await api('/admin/refunds?size=50' + (adminState.refundStatus ? '&status=' + adminState.refundStatus : ''));
    body.innerHTML = `<div class="toolbar"><select id="st">${statuses.map(s => `<option value="${s}" ${s === adminState.refundStatus ? 'selected' : ''}>${s || 'All statuses'}</option>`).join('')}</select><span class="muted">${page.totalElements} refunds</span></div>
      <div class="card"><table><thead><tr><th>Requested</th><th>Order</th><th>Amount</th><th>Reason</th><th>Status</th><th></th></tr></thead>
      <tbody>${page.content.map(r => `<tr><td>${when(r.createdAt)}</td><td><code>${r.orderId.slice(0, 8)}</code></td><td>${money(r.amount)}</td><td>${esc(r.reason || '')}</td><td>${status(r.status)}${r.failureReason ? '<br><span class="muted">' + esc(r.failureReason) + '</span>' : ''}</td>
        <td>${r.status === 'REQUESTED' ? `<button class="small primary dec" data-id="${r.id}" data-a="approve">Approve</button> <button class="small dec" data-id="${r.id}" data-a="reject">Reject</button>` : ''}</td></tr>`).join('')}</tbody></table></div>`;
    $('#st').addEventListener('change', (e) => { adminState.refundStatus = e.target.value; renderAdmin(); });
    body.querySelectorAll('.dec').forEach(b => b.addEventListener('click', async () => { const note = prompt('Note (optional)') ?? ''; try { await api(`/admin/refunds/${b.dataset.id}/${b.dataset.a}`, { method: 'POST', body: { note } }); renderAdmin(); } catch (e) { toast(e.message); } }));
  }

  async function adminProducts(body) {
    if (!state.categories.length) state.categories = await api('/categories');
    const page = await api('/admin/products?size=100');
    body.innerHTML = `
      <div class="two">
        <form class="card stack" id="np"><h2 style="margin:0">New product</h2>
          <div class="two"><label>SKU<input name="sku" required></label><label>Price<input name="price" type="number" step="0.01" min="0" required></label></div>
          <label>Name<input name="name" required></label><label>Description<input name="description"></label>
          <div class="two"><label>Category<select name="categoryId">${state.categories.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('')}</select></label><label>Initial stock<input name="initialStock" type="number" min="0" value="100"></label></div>
          <div><button class="primary">Create</button></div></form>
        <div class="stack">
          <form class="card stack" id="restock"><h2 style="margin:0">Bulk restock</h2><p class="muted" style="margin:0">Sets every product's stock to this level (applied by the processor).</p>
            <div class="row"><input name="level" type="number" min="0" value="1000"><button class="primary">Restock all</button></div></form>
          <form class="card stack" id="nc"><h2 style="margin:0">New category</h2>
            <div class="row"><input name="name" placeholder="Name" required><input name="slug" placeholder="slug" required><button class="primary">Create</button></div></form>
        </div>
      </div>
      <h2>Products (${page.totalElements})</h2>
      <div class="card"><table><thead><tr><th>SKU</th><th>Name</th><th>Category</th><th>Price</th><th>Stock</th><th>Active</th><th>Adjust stock</th></tr></thead>
      <tbody>${page.content.map(p => `<tr><td>${esc(p.sku)}</td><td>${esc(p.name)}</td><td>${esc(p.categoryName)}</td><td>${money(p.price, p.currency)}</td><td>${p.stock}</td>
        <td><button class="small tog" data-id="${p.id}" data-a="${!p.active}">${p.active ? 'Deactivate' : 'Activate'}</button></td>
        <td><input type="number" value="10" class="delta" data-id="${p.id}" style="width:70px"> <button class="small adj" data-id="${p.id}">Apply</button></td></tr>`).join('')}</tbody></table></div>`;
    $('#np').addEventListener('submit', async (e) => { e.preventDefault(); const f = Object.fromEntries(new FormData(e.target)); f.price = parseFloat(f.price); f.initialStock = parseInt(f.initialStock, 10); try { await api('/admin/products', { method: 'POST', body: f }); toast('Created'); renderAdmin(); } catch (err) { toast(err.message); } });
    $('#restock').addEventListener('submit', async (e) => { e.preventDefault(); const level = parseInt(new FormData(e.target).get('level'), 10); try { await api('/admin/inventory/restock', { method: 'POST', body: { level } }); toast('Restock queued'); } catch (err) { toast(err.message); } });
    $('#nc').addEventListener('submit', async (e) => { e.preventDefault(); try { await api('/admin/categories', { method: 'POST', body: Object.fromEntries(new FormData(e.target)) }); state.categories = []; toast('Category created'); renderAdmin(); } catch (err) { toast(err.message); } });
    body.querySelectorAll('.tog').forEach(b => b.addEventListener('click', async () => { await api('/admin/products/' + b.dataset.id, { method: 'PUT', body: { active: b.dataset.a === 'true' } }); renderAdmin(); }));
    body.querySelectorAll('.adj').forEach(b => b.addEventListener('click', async () => { const delta = parseInt(body.querySelector(`.delta[data-id="${b.dataset.id}"]`).value, 10); try { await api(`/admin/products/${b.dataset.id}/inventory`, { method: 'POST', body: { delta } }); toast('Adjustment queued'); } catch (err) { toast(err.message); } }));
  }

  async function adminCustomers(body) {
    const page = await api('/admin/customers?size=100');
    body.innerHTML = `<div class="card"><table><thead><tr><th>Joined</th><th>Name</th><th>Email</th><th>Status</th><th></th></tr></thead>
      <tbody>${page.content.map(c => `<tr><td>${when(c.createdAt)}</td><td>${esc(c.fullName)}</td><td>${esc(c.email)}</td><td>${c.enabled ? '<span class="status CONFIRMED">enabled</span>' : '<span class="status REJECTED">disabled</span>'}</td>
        <td><button class="small en" data-id="${c.id}" data-a="${c.enabled ? 'disable' : 'enable'}">${c.enabled ? 'Disable' : 'Enable'}</button></td></tr>`).join('')}</tbody></table></div>`;
    body.querySelectorAll('.en').forEach(b => b.addEventListener('click', async () => { await api(`/admin/customers/${b.dataset.id}/${b.dataset.a}`, { method: 'PUT' }); renderAdmin(); }));
  }

  // ---------- router ----------
  const routes = [
    [/^#\/shop$/, () => renderShop()],
    [/^#\/product\/([0-9a-f-]+)$/, (m) => renderProduct(m[1])],
    [/^#\/cart$/, () => renderCart()],
    [/^#\/orders$/, () => renderOrders()],
    [/^#\/orders\/([0-9a-f-]+)$/, (m) => renderOrder(m[1])],
    [/^#\/account$/, () => renderAccount()],
    [/^#\/admin$/, () => renderAdmin()],
  ];
  async function route() {
    const hash = location.hash || '#/shop';
    document.querySelectorAll('[data-nav]').forEach(a => a.classList.toggle('active', hash.startsWith('#/' + a.dataset.nav)));
    for (const [re, fn] of routes) { const m = hash.match(re); if (m) { try { await fn(m); } catch (e) { view.innerHTML = `<p class="muted">${esc(e.message)}</p>`; } return; } }
    location.hash = '#/shop';
  }
  window.addEventListener('hashchange', route);
  (async () => { await loadMe(); renderWho(); await refreshCartCount(); route(); })();
})();
