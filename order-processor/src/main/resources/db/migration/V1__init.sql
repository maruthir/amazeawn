-- Order processor schema ("processor"). Owns inventory and payments.

create table inventory (
    product_id uuid primary key,
    sku        varchar(64) not null,
    available  integer     not null check (available >= 0),
    updated_at timestamptz not null
);

create table processed_orders (
    order_id   uuid primary key,
    outcome    varchar(16)    not null,
    lines      jsonb          not null,
    amount     numeric(12, 2) not null,
    payment_id uuid,
    created_at timestamptz    not null,
    updated_at timestamptz    not null
);

create table payments (
    id          uuid primary key,
    order_id    uuid           not null,
    amount      numeric(12, 2) not null,
    currency    varchar(3)     not null,
    card_last4  varchar(4)     not null,
    status      varchar(16)    not null,
    created_at  timestamptz    not null,
    refunded_at timestamptz
);
create index payments_order_idx on payments (order_id);

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
