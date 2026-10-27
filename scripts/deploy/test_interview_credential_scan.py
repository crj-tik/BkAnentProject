from __future__ import annotations

import io
import subprocess
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

from scan_interview_credential_shapes import TEXT_COLUMN_QUERY, main, mysql_args, scan


class InterviewCredentialScanTest(unittest.TestCase):
    def test_reports_only_per_column_counts_and_uses_count_only_queries(self) -> None:
        responses = [
            subprocess.CompletedProcess([], 0, "interview_turn\tcontent\ninterview_asset\traw_content\n", ""),
            subprocess.CompletedProcess([], 0, "2\n0\n", ""),
        ]
        stdout = io.StringIO()
        with patch("scan_interview_credential_shapes.subprocess.run", side_effect=responses) as run:
            with redirect_stdout(stdout):
                result = main(["--database", "bk_interview"])

        self.assertEqual(0, result)
        self.assertIn("interview_turn.content\t2", stdout.getvalue())
        self.assertIn("interview_asset.raw_content\t0", stdout.getvalue())
        self.assertIn("matched_column_rows=2", stdout.getvalue())
        self.assertEqual(2, run.call_count)
        count_sql = run.call_args_list[1].kwargs["input"]
        self.assertEqual(2, count_sql.count("COUNT(*)"))
        self.assertEqual(2, count_sql.count("MAX_EXECUTION_TIME(10000)"))
        self.assertIn("REGEXP", count_sql)
        self.assertNotRegex(count_sql, r"(?i)\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE)\b")
        self.assertIn("--init-command=SET SESSION TRANSACTION READ ONLY", run.call_args_list[0].args[0])

    def test_docker_client_keeps_password_expansion_inside_container(self) -> None:
        args = mysql_args("mysql", "bk_interview", None, "bk-anent-dev-mysql-1")

        self.assertEqual(args[:4], ["docker", "exec", "-i", "bk-anent-dev-mysql-1"])
        self.assertTrue(any('MYSQL_PWD="$MYSQL_ROOT_PASSWORD"' in arg for arg in args))
        self.assertNotIn("--password", " ".join(args))
        self.assertIn("--init-command=SET SESSION TRANSACTION READ ONLY", args)

    def test_excludes_schema_migration_hashes_from_credential_scan(self) -> None:
        self.assertIn("LOWER(table_name) <> 'bk_schema_migration'", TEXT_COLUMN_QUERY)
        self.assertRegex(TEXT_COLUMN_QUERY, r"sha1\|sha256\|sha512\|md5")

    def test_validates_database_before_invoking_mysql(self) -> None:
        stderr = io.StringIO()
        with patch("scan_interview_credential_shapes.subprocess.run") as run:
            with redirect_stderr(stderr):
                result = main(["--database", "bk_interview;SELECT 1"])

        self.assertEqual(2, result)
        self.assertIn("database must contain only", stderr.getvalue())
        run.assert_not_called()

    def test_escapes_metadata_identifiers_before_building_count_query(self) -> None:
        rows = [
            subprocess.CompletedProcess([], 0, "odd`table\todd`column\n", ""),
            subprocess.CompletedProcess([], 0, "1\n", ""),
        ]
        with patch("scan_interview_credential_shapes.subprocess.run", side_effect=rows) as run:
            result = scan("bk_interview")

        self.assertEqual(1, result[0][1])
        self.assertIn("`odd``table`", run.call_args_list[1].kwargs["input"])
        self.assertIn("`odd``column`", run.call_args_list[1].kwargs["input"])

    def test_suppresses_mysql_error_details(self) -> None:
        failed = subprocess.CompletedProcess([], 1, "", "access denied for secret-user with password=secret")
        stderr = io.StringIO()
        with patch("scan_interview_credential_shapes.subprocess.run", return_value=failed):
            with redirect_stderr(stderr):
                result = main(["--database", "bk_interview"])

        self.assertEqual(2, result)
        self.assertIn("details suppressed", stderr.getvalue())
        self.assertNotIn("secret-user", stderr.getvalue())
        self.assertNotIn("password=secret", stderr.getvalue())

    def test_empty_metadata_fails_closed_instead_of_reporting_zero_matches(self) -> None:
        empty = subprocess.CompletedProcess([], 0, "", "")
        stdout = io.StringIO()
        stderr = io.StringIO()
        with patch("scan_interview_credential_shapes.subprocess.run", return_value=empty):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                result = main(["--database", "bk_interview"])

        self.assertEqual(2, result)
        self.assertEqual("", stdout.getvalue())
        self.assertIn("no scan was performed", stderr.getvalue())

    def test_rejects_host_option_file_with_docker_container(self) -> None:
        stderr = io.StringIO()
        with patch("scan_interview_credential_shapes.subprocess.run") as run:
            with redirect_stderr(stderr):
                result = main(["--database", "bk_interview", "--docker-container", "bk-anent-dev-mysql-1",
                               "--defaults-extra-file", "C:\\secure\\client.cnf"])

        self.assertEqual(2, result)
        self.assertIn("do not combine", stderr.getvalue())
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
