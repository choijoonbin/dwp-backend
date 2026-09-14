import copy
import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "generate-product-authorization-fixtures.py"
SPEC = importlib.util.spec_from_file_location("authorization_fixtures", SCRIPT)
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class AuthorizationV7CatalogPreservationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = generator.load_json_yaml(generator.FIXTURE_OUTPUT)

    def test_pre_wave7_negative_cases_and_fixed_v2_v3_catalogs_are_unchanged(self):
        generator.validate_preserved_v7_catalog(self.fixture)
        self.assertEqual(46, len(self.fixture["negativeCases"]))
        for group, count, version in (("APPROVALS", 18, 2), ("HCM", 24, 3)):
            cases = [t for t in self.fixture["testCases"] if t["group"] == group]
            self.assertEqual(count, len(cases))
            self.assertTrue(all(t["requiredRegistryRef"]["version"] == version for t in cases))

    def test_changed_negative_case_is_rejected_even_with_valid_new_catalog_checksum(self):
        changed = copy.deepcopy(self.fixture)
        changed["negativeCases"][0]["input"] += ":changedContent"
        changed.pop("fixtureChecksum")
        changed["fixtureChecksum"] = generator.sha256(changed)
        with self.assertRaisesRegex(generator.ContractError, "negativeCases"):
            generator.validate_preserved_v7_catalog(changed)

    def test_changed_fixed_gate_case_is_rejected_even_with_recomputed_checksum(self):
        for group in ("APPROVALS", "HCM"):
            changed = copy.deepcopy(self.fixture)
            case = next(t for t in changed["testCases"] if t["group"] == group)
            case["requiredRegistryRef"]["sha256"] = "0" * 64
            changed.pop("fixtureChecksum")
            changed["fixtureChecksum"] = generator.sha256(changed)
            with self.assertRaisesRegex(generator.ContractError, "testCases"):
                generator.validate_preserved_v7_catalog(changed)


if __name__ == "__main__":
    unittest.main()
