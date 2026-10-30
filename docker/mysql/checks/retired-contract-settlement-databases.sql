SELECT IF(
    (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('bk_contract','bk_settlement')) = 0
    AND (SELECT COUNT(*) FROM bk_auth.retired_schema_migration
         WHERE (schema_name='bk_contract' AND migration_id='a2a-task-authorization-contract-20261026-v1'
                OR schema_name='bk_settlement' AND migration_id='a2a-task-authorization-settlement-20261026-v1')
           AND file_sha256='8946aa62546215fc1c6781dc84cf03cda8ee2ba0ccc6ec5ca321a49cfb5a411c'
           AND check_sha256='82a67a4233cddd0779117024917fd9efce6002174e1ebe6522dbaa22852d0c84'
           AND retirement_state='retired') = 2,
    1, 0
);
