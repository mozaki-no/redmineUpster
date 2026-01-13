create table if not exists configs (
  id bigserial primary key,
  config_key text not null unique,
  config_value text
);

create table if not exists issue_link (
  id bigserial primary key,
  external_key text not null unique,
  issue_id bigint not null
);

create table if not exists diffs (
  id bigserial primary key,
  filename text,
  created_at timestamp not null default now()
);

create table if not exists diff_items (
  id bigserial primary key,
  diff_id bigint not null references diffs(id) on delete cascade,
  external_key text not null,
  subject text,
  parent_key text,
  level_path text,
  action text not null,
  status text,
  created_at timestamp not null default now()
);

create table if not exists runs (
  id bigserial primary key,
  diff_id bigint not null references diffs(id) on delete cascade,
  dry_run boolean not null,
  status text not null,
  started_at timestamp not null default now(),
  finished_at timestamp
);

create table if not exists run_logs (
  id bigserial primary key,
  run_id bigint not null references runs(id) on delete cascade,
  level text not null,
  message text not null,
  created_at timestamp not null default now()
);
