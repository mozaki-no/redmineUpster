alter table diff_items
  add column if not exists payload_json text;
