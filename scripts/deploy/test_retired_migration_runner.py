#!/usr/bin/env python3
"""Exercise retirement ledger decisions without altering a database."""
import hashlib
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/deploy/run-init-manifest.sh"


class RetiredMigrationRunnerTest(unittest.TestCase):
    def run_case(self, *, ledger_hash=None, schema_present="0", state="retired"):
        work = ROOT / ".work" / "retired-runner-test"
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as temporary:
            root = pathlib.Path(temporary)
            (root / "sql").mkdir()
            (root / "docker/mysql/checks").mkdir(parents=True)
            (root / "bin").mkdir()
            sql = b"SELECT 1;\n"
            check = b"SELECT 1;\n"
            (root / "sql/migration.sql").write_bytes(sql)
            (root / "docker/mysql/checks/check.sql").write_bytes(check)
            expected = hashlib.sha256(sql).hexdigest() + ":" + hashlib.sha256(check).hexdigest()
            (root / "docker/mysql/init-manifest.tsv").write_text(
                "010\tupgrade\told-contract-v1\tbk_contract\tsql/migration.sql\t"
                + expected.split(":")[0] + "\tdocker/mysql/checks/check.sql\t" + expected.split(":")[1] + "\n",
                encoding="utf-8")
            fake = root / "bin/docker"
            fake.write_text("""#!/usr/bin/env bash
if [ "$1" = inspect ]; then printf 'bk-anent-dev:mysql\\n'; exit 0; fi
sql="$(cat)"
case "$sql" in
  *"table_name='retired_schema_migration'"*) printf '1\\n' ;;
  *"FROM bk_auth.retired_schema_migration"*) printf '%s\\n' "$TEST_LEDGER_HASH" ;;
  *"FROM information_schema.schemata"*) printf '%s\\n' "$TEST_SCHEMA_PRESENT" ;;
  *"table_name='bk_schema_migration'"*) printf '1\\n' ;;
  *"FROM bk_contract.bk_schema_migration"*) printf '%s\\n' "$TEST_EXPECTED_HASH" ;;
  *) printf 'unexpected database call\\n' >&2; exit 1 ;;
esac
""", encoding="utf-8")
            fake.chmod(0o755)
            bash = shutil.which("bash")
            if os.name == "nt" and pathlib.Path(r"C:\Program Files\Git\bin\bash.exe").exists():
                bash = r"C:\Program Files\Git\bin\bash.exe"
            if not bash:
                self.skipTest("bash unavailable")
            env = os.environ.copy()
            env.update(BK_INIT_ROOT=str(root), PATH=str(root / "bin") + os.pathsep + env["PATH"],
                       TEST_LEDGER_HASH=(expected if ledger_hash is None else ledger_hash) + ":" + state,
                       TEST_EXPECTED_HASH=expected,
                       TEST_SCHEMA_PRESENT=schema_present)
            return subprocess.run([bash, str(RUNNER), "--mode", "upgrade", "--container", "fixture"],
                                  env=env, text=True, capture_output=True, check=False)

    def test_valid_retirement_is_skipped(self):
        result = self.run_case()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Verified retired migration old-contract-v1", result.stdout)

    def test_changed_checksum_fails_closed(self):
        result = self.run_case(ledger_hash="0" * 64 + ":" + "1" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Retired migration checksum or state changed", result.stderr)

    def test_recreated_retired_database_fails_closed(self):
        result = self.run_case(schema_present="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Retired schema unexpectedly exists", result.stderr)

    def test_interrupted_preparation_can_resume_with_existing_or_dropped_schema(self):
        existing = self.run_case(schema_present="1", state="prepared")
        self.assertEqual(existing.returncode, 0, existing.stderr)
        self.assertIn("Verified previously applied old-contract-v1", existing.stdout)
        dropped = self.run_case(schema_present="0", state="prepared")
        self.assertEqual(dropped.returncode, 0, dropped.stderr)
        self.assertIn("Verified retired migration old-contract-v1", dropped.stdout)


if __name__ == "__main__":
    unittest.main()
