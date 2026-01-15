-- スケジュール実行管理テーブル
create table if not exists schedules (
    id bigserial primary key,
    name text not null,
    cron_expression text not null,
    redmine_project_id bigint not null references redmine_projects(id),
    wbs_file_path text,
    dry_run boolean default false,
    enabled boolean default true,
    last_run_at timestamp,
    next_run_at timestamp,
    created_at timestamp not null default now()
);

-- runs テーブルに schedule_id を追加
alter table runs
    add column if not exists schedule_id bigint references schedules(id);
