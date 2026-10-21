-- Add cross-instance claim leases to the existing transactional memory RAG outbox.
-- Remote RAG calls remain outside the source transaction; lease_owner fences late responses.
USE bk_memory;

SELECT COUNT(*) INTO @memory_rag_lease_owner_exists
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'memory_rag_outbox' AND column_name = 'lease_owner';
SET @memory_rag_lease_owner_sql = IF(@memory_rag_lease_owner_exists = 0,
    'ALTER TABLE memory_rag_outbox ADD COLUMN lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE memory_rag_lease_owner_stmt FROM @memory_rag_lease_owner_sql;
EXECUTE memory_rag_lease_owner_stmt;
DEALLOCATE PREPARE memory_rag_lease_owner_stmt;

SELECT COUNT(*) INTO @memory_rag_lease_until_exists
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'memory_rag_outbox' AND column_name = 'lease_until';
SET @memory_rag_lease_until_sql = IF(@memory_rag_lease_until_exists = 0,
    'ALTER TABLE memory_rag_outbox ADD COLUMN lease_until DATETIME(6) NULL', 'SELECT 1');
PREPARE memory_rag_lease_until_stmt FROM @memory_rag_lease_until_sql;
EXECUTE memory_rag_lease_until_stmt;
DEALLOCATE PREPARE memory_rag_lease_until_stmt;

SELECT COUNT(*) INTO @memory_rag_dispatch_index_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'memory_rag_outbox' AND index_name = 'idx_memory_rag_dispatch';
SELECT COUNT(*) INTO @memory_rag_lease_index_column_exists
FROM information_schema.statistics
WHERE table_schema = DATABASE() AND table_name = 'memory_rag_outbox'
  AND index_name = 'idx_memory_rag_dispatch' AND column_name = 'lease_until';
SET @memory_rag_dispatch_index_sql = IF(@memory_rag_dispatch_index_exists = 0,
    'ALTER TABLE memory_rag_outbox ADD KEY idx_memory_rag_dispatch(state,next_attempt_at,lease_until)',
    IF(@memory_rag_lease_index_column_exists = 0,
        'ALTER TABLE memory_rag_outbox DROP INDEX idx_memory_rag_dispatch, ADD KEY idx_memory_rag_dispatch(state,next_attempt_at,lease_until)',
        'SELECT 1'));
PREPARE memory_rag_dispatch_index_stmt FROM @memory_rag_dispatch_index_sql;
EXECUTE memory_rag_dispatch_index_stmt;
DEALLOCATE PREPARE memory_rag_dispatch_index_stmt;
