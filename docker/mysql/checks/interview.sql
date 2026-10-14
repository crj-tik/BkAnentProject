SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='bk_interview' AND table_name IN ('interview_case','interview_session','interview_report_task'))=3;
