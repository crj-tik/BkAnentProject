SELECT COUNT(*) = 1 FROM information_schema.columns
WHERE table_schema='bk_auth' AND table_name='auth_role' AND column_name='role_type'
  AND data_type='varchar' AND character_maximum_length=16
  AND is_nullable='NO' AND column_default='UNCLASSIFIED';
