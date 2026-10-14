SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='bk_interview' AND table_name='interview_report_task' AND column_name='asset_id' AND data_type='bigint');
