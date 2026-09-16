from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-wave4-owner-widget-seed.py"
SPEC = importlib.util.spec_from_file_location("check_wave4_owner_widget_seed", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class Wave4OwnerWidgetSeedTest(unittest.TestCase):

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        for relative in (CHECKER.FIXTURE, CHECKER.PROVIDER_CONTRACT, CHECKER.MIGRATION):
            source = Path(__file__).resolve().parents[2] / relative
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
        self.fixture_path = self.root / CHECKER.FIXTURE
        self.sql_path = self.root / CHECKER.MIGRATION

    def tearDown(self) -> None:
        self.directory.cleanup()

    def fixture(self) -> dict:
        return json.loads(self.fixture_path.read_text(encoding="utf-8"))

    def write_fixture(self, value: dict) -> None:
        self.fixture_path.write_text(json.dumps(value), encoding="utf-8")

    def blob(self, _root: Path, _commit: str, path: str) -> bytes:
        return (Path(__file__).resolve().parents[2] / path).read_bytes()

    def violations(self) -> list[str]:
        return CHECKER.violations(self.root, self.blob)

    def test_checked_seed_is_truthful_shadow_and_hash_bound(self) -> None:
        self.assertEqual([], self.violations())

    def test_rejects_frontend_certification_before_frontend_evidence(self) -> None:
        sql = self.sql_path.read_text(encoding="utf-8")
        sql = sql.replace("'NOT_RUN'", "'PASS'", 1)
        sql += "\nSELECT 'A11Y';\nSELECT 'PERFORMANCE';\nSELECT 'LOCALIZATION';\n"
        self.sql_path.write_text(sql, encoding="utf-8")

        problems = "\n".join(self.violations())

        self.assertIn("falsely claims PASS certification", problems)
        self.assertIn("fabricates frontend A11Y evidence", problems)
        self.assertIn("fabricates frontend PERFORMANCE evidence", problems)
        self.assertIn("fabricates frontend LOCALIZATION evidence", problems)

    def test_rejects_authoritative_activation_and_extra_default_widget(self) -> None:
        fixture = self.fixture()
        fixture["registryMode"] = "AUTHORITATIVE"
        fixture["runtimeActivationReady"] = True
        fixture["fixtures"][0]["enabledByDefault"] = True
        self.write_fixture(fixture)
        self.sql_path.write_text(
            self.sql_path.read_text(encoding="utf-8") + "\nSELECT 'AUTHORITATIVE';\n",
            encoding="utf-8",
        )

        problems = "\n".join(self.violations())

        self.assertIn("must remain SHADOW", problems)
        self.assertIn("cannot claim runtime activation readiness", problems)
        self.assertIn("only notification.app-badges may be enabled", problems)
        self.assertIn("must not contain an AUTHORITATIVE", problems)

    def test_rejects_manifest_authority_and_git_evidence_drift(self) -> None:
        fixture = self.fixture()
        value = fixture["fixtures"][0]
        value["manifest"]["requiredAuthorities"] = ["APP.WORK:VIEW"]
        value["expectedSha256"] = CHECKER.canonical_sha256(value["manifest"])
        value["backendEvidence"][0]["sha256"] = value["expectedSha256"]
        value["backendEvidence"][1]["sha256"] = "0" * 64
        self.write_fixture(fixture)

        problems = "\n".join(self.violations())

        self.assertIn("required authority binding drifted", problems)
        self.assertIn("SECURITY evidence hash does not match its git blob", problems)

    def test_rejects_ambiguous_git_commit_reference(self) -> None:
        fixture = self.fixture()
        evidence = fixture["fixtures"][0]["backendEvidence"][1]
        evidence["ref"] = evidence["ref"].replace(
            "6e453915088b0274eb87638cbaa30c468a796d24", "6e453915"
        )
        self.write_fixture(fixture)

        self.assertIn("must use a full git blob ref", "\n".join(self.violations()))


if __name__ == "__main__":
    unittest.main()
