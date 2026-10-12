-- Lets a test reset drop only the inventory rows that existed before it was issued.
alter table inventory add column created_at timestamptz not null default now();
