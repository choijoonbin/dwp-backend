from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


CHECKER = Path(__file__).resolve().parents[1] / "check-home-runtime-gate.py"
SPEC = importlib.util.spec_from_file_location("home_runtime_gate_checker", CHECKER)
assert SPEC is not None and SPEC.loader is not None
CHECKER_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER_MODULE)
TEMPLATE = json.loads(
    (Path(__file__).resolve().parents[2]
     / "contracts/home-runtime/wave4-runtime-gate.template.json").read_text(encoding="utf-8")
)


class HomeRuntimeGateTest(unittest.TestCase):

    def test_checked_in_pending_template_is_structurally_complete(self) -> None:
        self.assertEqual([], CHECKER_MODULE.validate(TEMPLATE, allow_pending=True))

    def test_pending_evidence_cannot_open_wave_five(self) -> None:
        problems = CHECKER_MODULE.validate(TEMPLATE)

        self.assertIn("status must equal PASS", problems)

    def test_pass_requires_hash_bound_evidence_slo_and_rollback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "build/reports/wave4.txt"
            artifact.parent.mkdir(parents=True)
            artifact.write_text("PASS\n", encoding="utf-8")
            digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
            evidence = copy.deepcopy(TEMPLATE)
            evidence.update({
                "status": "PASS",
                "backendCommit": "a" * 40,
                "contractHashes": {"build/reports/wave4.txt": digest},
                "providerCoverage": {
                    "activeDefinitions": 1, "verifiedDefinitions": 1,
                    "shadowDefinitions": 12, "verifiedShadowDefinitions": 12,
                },
                "slo": {
                    "status": "PASS", "sampleCount": 1000,
                    "serverP95Ms": 850, "hardFailureRate": 0.001,
                    "liveProduction": False,
                },
                "rollback": {
                    "status": "PASS", "v2KillSwitchVerified": True,
                    "commandKillSwitchVerified": True,
                    "providerKillSwitchVerified": True, "cachePurgeVerified": True,
                    "recoveryTimeSeconds": 120,
                },
            })
            for check in evidence["checks"]:
                check["status"] = "PASS"
                check["artifacts"] = [{"path": "build/reports/wave4.txt", "sha256": digest}]

            with patch.object(CHECKER_MODULE, "_commit_is_ancestor", return_value=True):
                self.assertEqual([], CHECKER_MODULE.validate(evidence, root))

            artifact.write_text("tampered\n", encoding="utf-8")
            with patch.object(CHECKER_MODULE, "_commit_is_ancestor", return_value=True):
                problems = CHECKER_MODULE.validate(evidence, root)
            self.assertTrue(any("hash mismatch" in item for item in problems))

    def test_wave_four_cannot_claim_production_vitals_or_canary(self) -> None:
        for field, value in (("status", "PASS"), ("lcpP75", 2200),
                             ("inpP75", 180), ("canary", "PASS")):
            with self.subTest(field=field):
                evidence = copy.deepcopy(TEMPLATE)
                evidence["wave6ProductionTelemetry"][field] = value

                problems = CHECKER_MODULE.validate(evidence, allow_pending=True)

                self.assertTrue(any("wave6ProductionTelemetry" in item for item in problems))

    def test_cross_tenant_leak_and_incomplete_provider_coverage_fail_closed(self) -> None:
        evidence = copy.deepcopy(TEMPLATE)
        evidence["security"]["crossTenantLeakCount"] = 1
        evidence["providerCoverage"] = {
            "activeDefinitions": 4, "verifiedDefinitions": 3,
            "shadowDefinitions": 12, "verifiedShadowDefinitions": 11,
        }

        problems = CHECKER_MODULE.validate(evidence, allow_pending=True)

        self.assertIn("security.crossTenantLeakCount must equal zero", problems)
        self.assertIn("pending provider coverage must be zero", problems)

    def test_pass_requires_every_shadow_definition_to_have_verified_routing(self) -> None:
        evidence = copy.deepcopy(TEMPLATE)
        evidence["status"] = "PASS"
        evidence["providerCoverage"] = {
            "activeDefinitions": 1, "verifiedDefinitions": 1,
            "shadowDefinitions": 12, "verifiedShadowDefinitions": 11,
        }

        problems = CHECKER_MODULE.validate(evidence)

        self.assertIn(
            "all 12 or more SHADOW definitions must have verified provider routing",
            problems,
        )


if __name__ == "__main__":
    unittest.main()
