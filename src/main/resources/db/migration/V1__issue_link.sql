create table if not exists issue_link (
  id bigserial primary key,
  external_key text not null unique,
  issue_id bigint not null
);
