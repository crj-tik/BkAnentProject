SELECT
 (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_agent' AND table_name IN ('studio_work','studio_command','studio_run','studio_message','studio_artifact_series','studio_artifact_revision'))=6
 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='bk_agent' AND (
  (table_name='studio_work' AND ((column_name IN ('work_id','owner_id') AND data_type='varchar') OR (column_name='context_json' AND data_type='longtext') OR (column_name IN ('context_revision','ui_revision') AND data_type='bigint')))
  OR (table_name='studio_command' AND ((column_name='request_hash' AND data_type='char') OR (column_name='receipt_json' AND data_type='longtext') OR (column_name='status' AND data_type='varchar')))
  OR (table_name='studio_run' AND ((column_name IN ('owner_id','run_id','async_workflow_id') AND data_type='varchar') OR (column_name='context_revision' AND data_type='bigint') OR (column_name='artifacts_synced' AND data_type='int')))
  OR (table_name='studio_message' AND ((column_name IN ('message_id','role') AND data_type='varchar') OR (column_name='content' AND data_type='longtext')))
  OR (table_name='studio_artifact_series' AND ((column_name IN ('owner_id','source_artifact_id') AND data_type='varchar') OR (column_name='current_version' AND data_type='bigint')))
  OR (table_name='studio_artifact_revision' AND ((column_name='series_id' AND data_type='varchar') OR (column_name='version_no' AND data_type='bigint') OR (column_name='content_json' AND data_type='longtext')))
 ))=22
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='bk_agent' AND constraint_type='PRIMARY KEY' AND table_name IN ('studio_work','studio_command','studio_run','studio_message','studio_artifact_series','studio_artifact_revision'))=6
 AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='bk_agent' AND constraint_type='UNIQUE' AND constraint_name IN ('uk_studio_session','uk_studio_creation','uk_studio_message','uk_studio_source','uk_studio_revision'))=5;
