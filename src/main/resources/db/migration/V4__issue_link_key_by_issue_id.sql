-- Excelに「チケットID」列を持つ方式への移行。
-- issue_link を (issue_id, project_id) で一意に管理し、external_key は任意項目にする。

-- 1. external_key を NULL 許可にし、(external_key, project_id) の一意制約を削除
ALTER TABLE issue_link ALTER COLUMN external_key DROP NOT NULL;
ALTER TABLE issue_link DROP CONSTRAINT IF EXISTS issue_link_external_key_project_id_key;
ALTER TABLE issue_link DROP CONSTRAINT IF EXISTS issue_link_external_key_key;

-- 2. 同じ (issue_id, project_id) の重複行は id が最大のものだけ残す
DELETE FROM issue_link a
USING issue_link b
WHERE a.issue_id = b.issue_id
  AND a.project_id IS NOT DISTINCT FROM b.project_id
  AND a.id < b.id;

-- 3. (issue_id, project_id) の一意制約を追加
ALTER TABLE issue_link ADD CONSTRAINT issue_link_issue_id_project_id_key UNIQUE (issue_id, project_id);
