from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CHECKER = ROOT / "scripts/check-service-boundaries.py"
SPEC = importlib.util.spec_from_file_location("mail_service_boundary_checker", CHECKER)
assert SPEC is not None and SPEC.loader is not None
CHECKER_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER_MODULE)

MAIL_CLIENT_IDS = {
    "platform-mail-provider-http-transport",
    "platform-mail-microsoft-graph-adapter",
    "platform-mail-google-gmail-adapter",
    "platform-mail-provider-token-broker",
    "platform-mail-trusted-attachment-scanner",
}


class MailServiceInterfaceContractsTest(unittest.TestCase):

    def test_registered_mail_transports_match_their_real_security_markers(self) -> None:
        policy = json.loads(
            (ROOT / "docs/architecture/service-interface-contracts.json")
            .read_text(encoding="utf-8")
        )
        entries = [
            entry for entry in policy["httpClients"]
            if entry["id"] in MAIL_CLIENT_IDS
        ]
        self.assertEqual(MAIL_CLIENT_IDS, {entry["id"] for entry in entries})

        required_sources = {
            Path(entry["path"])
            for entry in entries
        }
        required_sources.update(
            Path(source)
            for entry in entries
            for source in entry.get("requiredMarkerSources", [])
        )

        with tempfile.TemporaryDirectory() as directory:
            isolated_root = Path(directory)
            for relative in required_sources:
                target = isolated_root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)

            isolated_policy_file = (
                isolated_root / "docs/architecture/service-interface-contracts.json"
            )
            isolated_policy_file.parent.mkdir(parents=True, exist_ok=True)
            isolated_policy = {
                "version": policy["version"],
                "resilienceDefaults": policy["resilienceDefaults"],
                "httpClients": entries,
                "crossDatabaseExceptions": [],
                "metadataScanners": [],
            }
            isolated_policy_file.write_text(
                json.dumps(isolated_policy), encoding="utf-8"
            )

            previous_root = CHECKER_MODULE.ROOT
            previous_policy_file = CHECKER_MODULE.POLICY_FILE
            try:
                CHECKER_MODULE.ROOT = isolated_root
                CHECKER_MODULE.POLICY_FILE = isolated_policy_file
                self.assertEqual(
                    [], CHECKER_MODULE.policy_manifest_violations(isolated_policy)
                )
                self.assertEqual(
                    [], CHECKER_MODULE.http_client_policy_violations(isolated_policy)
                )
            finally:
                CHECKER_MODULE.ROOT = previous_root
                CHECKER_MODULE.POLICY_FILE = previous_policy_file


if __name__ == "__main__":
    unittest.main()
