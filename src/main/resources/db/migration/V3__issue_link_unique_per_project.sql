-- external_key単体のユニーク制約を削除し、(external_key, project_id)の複合ユニークに変更
ALTER TABLE issue_link DROP CONSTRAINT IF EXISTS issue_link_external_key_key;
ALTER TABLE issue_link ADD CONSTRAINT issue_link_external_key_project_id_key UNIQUE (external_key, project_id);
