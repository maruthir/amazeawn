-- API service schema. Lives in schema "api"; the processor owns schema "processor".

create table users (
    id            uuid primary key,
    email         varchar(255) not null unique,
    password_hash varchar(255) not null,
    full_name     varchar(255) not null,
    role          varchar(16)  not null,
    enabled       boolean      not null default true,
    created_at    timestamptz  not null
);
create index users_role_created_idx on users (role, created_at desc);

create table refresh_tokens (
    id         uuid primary key,
    user_id    uuid         not null references users (id) on delete cascade,
    token_hash varchar(64)  not null unique,
    expires_at timestamptz  not null,
    revoked    boolean      not null default false,
    created_at timestamptz  not null
);

create table addresses (
    id          uuid primary key,
    user_id     uuid         not null references users (id) on delete cascade,
    label       varchar(64)  not null,
    line1       varchar(255) not null,
    line2       varchar(255),
    city        varchar(128) not null,
    region      varchar(128),
    postal_code varchar(32)  not null,
    country     varchar(64)  not null,
    is_default  boolean      not null default false
);
create index addresses_user_idx on addresses (user_id);

create table categories (
    id   uuid primary key,
    name varchar(128) not null unique,
    slug varchar(128) not null unique
);

create table products (
    id          uuid primary key,
    sku         varchar(64)    not null unique,
    name        varchar(255)   not null,
    description text,
    price       numeric(12, 2) not null,
    currency    varchar(3)     not null,
    category_id uuid           not null references categories (id),
    stock       integer        not null default 0,
    active      boolean        not null default true,
    created_at  timestamptz    not null,
    updated_at  timestamptz    not null
);
create index products_category_idx on products (category_id);
create index products_name_lower_idx on products (lower(name));
create index products_price_idx on products (price);

create table carts (
    id         uuid primary key,
    user_id    uuid        not null unique references users (id) on delete cascade,
    updated_at timestamptz not null
);

create table cart_items (
    id         uuid primary key,
    cart_id    uuid        not null references carts (id) on delete cascade,
    product_id uuid        not null references products (id) on delete cascade,
    quantity   integer     not null check (quantity > 0),
    created_at timestamptz not null,
    unique (cart_id, product_id)
);

create table orders (
    id               uuid primary key,
    user_id          uuid           not null references users (id),
    status           varchar(32)    not null,
    total_amount     numeric(12, 2) not null,
    currency         varchar(3)     not null,
    card_last4       varchar(4)     not null,
    shipping_address text           not null,
    rejection_reason varchar(255),
    payment_id       uuid,
    created_at       timestamptz    not null,
    updated_at       timestamptz    not null,
    confirmed_at     timestamptz,
    shipped_at       timestamptz,
    delivered_at     timestamptz,
    cancelled_at     timestamptz
);
create index orders_user_created_idx on orders (user_id, created_at desc);
create index orders_status_idx on orders (status);
create index orders_status_confirmed_idx on orders (status, confirmed_at);
create index orders_status_shipped_idx on orders (status, shipped_at);

create table order_items (
    id           uuid primary key,
    order_id     uuid           not null references orders (id) on delete cascade,
    product_id   uuid           not null,
    product_name varchar(255)   not null,
    sku          varchar(64)    not null,
    unit_price   numeric(12, 2) not null,
    quantity     integer        not null check (quantity > 0)
);
create index order_items_order_idx on order_items (order_id);

create table idempotency_keys (
    idem_key   varchar(128) not null,
    user_id    uuid         not null,
    order_id   uuid         not null,
    created_at timestamptz  not null,
    primary key (idem_key, user_id)
);

create table refunds (
    id             uuid primary key,
    order_id       uuid           not null references orders (id) on delete cascade,
    user_id        uuid           not null,
    amount         numeric(12, 2) not null,
    status         varchar(16)    not null,
    reason         text,
    admin_note     text,
    failure_reason varchar(255),
    created_at     timestamptz    not null,
    decided_at     timestamptz,
    completed_at   timestamptz
);
create index refunds_order_idx on refunds (order_id);
create index refunds_status_idx on refunds (status, created_at desc);

-- Shared messaging infrastructure (same shape in the processor schema).
create table outbox (
    id             uuid primary key,
    routing_key    varchar(64)  not null,
    message_type   varchar(255) not null,
    correlation_id varchar(128) not null,
    payload        jsonb        not null,
    created_at     timestamptz  not null,
    published_at   timestamptz
);
create index outbox_unpublished_idx on outbox (created_at) where published_at is null;

create table processed_messages (
    message_id   uuid primary key,
    processed_at timestamptz not null
);
