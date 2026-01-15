-- 複数Redmineプロジェクト管理テーブル
create table if not exists redmine_projects (
    id bigserial primary key,
    name text not null,
    base_url text not null,
    api_key text not null,
    project_id text not null,
    is_default boolean default false,
    created_at timestamp not null default now(),
    updated_at timestamp
);

-- diffs テーブルに redmine_project_id を追加
alter table diffs
    add column if not exists redmine_project_id bigint references redmine_projects(id);

-- issue_link テーブルに redmine_project_id を追加
alter table issue_link
    add column if not exists redmine_project_id bigint references redmine_projects(id);

-- unique 制約の変更 (external_key + redmine_project_id)
alter table issue_link drop constraint if exists issue_link_external_key_key;
create unique index if not exists idx_issue_link_external_key_project
    on issue_link(external_key, redmine_project_id);
