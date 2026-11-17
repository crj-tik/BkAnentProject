#!/usr/bin/env python3
"""Run the cross-domain owner reconciliation against connection-local MySQL fixtures."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
SQL = ROOT / "scripts/migrations/rollback-user-owner-ledger.sql"


def main():
    # MySQL cannot reopen a temporary table in a UNION. Use three connection-local
    # copies only for the customer ledger; production SQL runs unchanged otherwise.
    query = SQL.read_text(encoding="utf-8")
    original = "FROM bk_customer.customer_owner_migration_ledger l"
    assert query.count(original) == 3
    for number in range(1, 4):
        query = query.replace(original, original.replace("ledger", f"ledger_{number}"), 1)

    setup = """
CREATE TEMPORARY TABLE bk_customer.customer_owner_migration_ledger_1 (record_type VARCHAR(20), record_id BIGINT, candidate_user_id BIGINT, legacy_broker_id BIGINT, status VARCHAR(20));
CREATE TEMPORARY TABLE bk_customer.customer_owner_migration_ledger_2 LIKE bk_customer.customer_owner_migration_ledger_1;
CREATE TEMPORARY TABLE bk_customer.customer_owner_migration_ledger_3 LIKE bk_customer.customer_owner_migration_ledger_1;
CREATE TEMPORARY TABLE bk_customer.customer_profile (id BIGINT, owner_user_id BIGINT, broker_id BIGINT);
CREATE TEMPORARY TABLE bk_customer.customer_follow_record (id BIGINT, owner_user_id BIGINT, broker_id BIGINT);
CREATE TEMPORARY TABLE bk_customer.customer_listing_inquiry (id BIGINT, owner_user_id BIGINT, broker_id BIGINT);
CREATE TEMPORARY TABLE bk_listing.listing_owner_migration_ledger (record_type VARCHAR(20), record_id BIGINT, candidate_user_id BIGINT, legacy_broker_id BIGINT, status VARCHAR(20));
CREATE TEMPORARY TABLE bk_listing.listing_info (id BIGINT, owner_user_id BIGINT, broker_id BIGINT);
CREATE TEMPORARY TABLE bk_marketing.marketing_owner_migration_ledger (record_type VARCHAR(20), record_id BIGINT, candidate_user_id BIGINT, legacy_broker_id BIGINT, status VARCHAR(20));
CREATE TEMPORARY TABLE bk_marketing.marketing_content (id BIGINT, owner_user_id BIGINT, owner_broker_id BIGINT);
CREATE TEMPORARY TABLE bk_auth.auth_user_business_identity (user_id BIGINT, identity_type VARCHAR(24), identity_id BIGINT);
INSERT INTO bk_customer.customer_owner_migration_ledger_1 VALUES
 ('PROFILE',101,11,21,'APPLIED'),('PROFILE',102,11,22,'APPLIED'),
 ('PROFILE',103,11,21,'APPLIED'),('PROFILE',104,11,21,'APPLIED'),
 ('PROFILE',105,11,21,'APPLIED'),('PROFILE',106,11,23,'APPLIED'),
 ('PROFILE',109,NULL,21,'APPLIED'),('PROFILE',110,11,0,'APPLIED');
INSERT INTO bk_customer.customer_owner_migration_ledger_2 VALUES ('FOLLOW',107,11,21,'APPLIED');
INSERT INTO bk_customer.customer_owner_migration_ledger_3 VALUES ('INQUIRY',108,11,21,'APPLIED');
INSERT INTO bk_customer.customer_profile VALUES
 (101,11,21),(102,11,22),(103,12,21),(104,11,22),(106,11,23),(109,NULL,21),(110,11,0);
INSERT INTO bk_customer.customer_follow_record VALUES (107,11,21);
INSERT INTO bk_customer.customer_listing_inquiry VALUES (108,11,21);
INSERT INTO bk_listing.listing_owner_migration_ledger VALUES ('LISTING',201,11,21,'APPLIED');
INSERT INTO bk_listing.listing_info VALUES (201,11,21);
INSERT INTO bk_marketing.marketing_owner_migration_ledger VALUES ('CONTENT',301,11,21,'APPLIED');
INSERT INTO bk_marketing.marketing_content VALUES (301,11,21);
INSERT INTO bk_auth.auth_user_business_identity VALUES (11,'BROKER',21),(12,'BROKER',22);
"""
    check = """
SELECT 'POST_CHECK', COUNT(*), SUM(owner_user_id IS NULL), SUM(owner_user_id=11)
FROM bk_customer.customer_profile;
SELECT 'LEDGER_CHECK', COUNT(*), SUM(status='APPLIED')
FROM bk_customer.customer_owner_migration_ledger_1;
"""
    result = subprocess.run(
        ["docker", "exec", "-i", "bk-anent-dev-mysql-1", "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'],
        input=setup + query + check, text=True, capture_output=True,
    )
    if result.returncode:
        raise RuntimeError("MySQL 临时数据测试失败：" + result.stderr.strip())
    lines = result.stdout.splitlines()
    assert lines[0].startswith("DIAGNOSTIC ONLY:"), lines[0]
    rows = {int(parts[2]): parts[-1] for line in lines[1:-2]
            if len(parts := line.split("\t")) == 9}
    expected = {
        101: "ALIGNED_RETAIN_OWNER", 102: "MAPPING_CHANGED", 103: "OWNER_CHANGED",
        104: "BROKER_CHANGED", 105: "RECORD_MISSING", 106: "MAPPING_MISSING",
        107: "ALIGNED_RETAIN_OWNER", 108: "ALIGNED_RETAIN_OWNER",
        109: "INVALID_LEDGER", 110: "INVALID_LEDGER",
        201: "ALIGNED_RETAIN_OWNER", 301: "ALIGNED_RETAIN_OWNER",
    }
    assert rows == expected, rows
    assert lines[-2:] == ["POST_CHECK\t7\t1\t5", "LEDGER_CHECK\t8\t8"], lines[-2:]
    print("负责人对账 MySQL 测试通过：12 条临时记录、异常分类及数据不变。")


if __name__ == "__main__":
    main()
