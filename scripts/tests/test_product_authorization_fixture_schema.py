from __future__ import annotations

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_DIRECTORY = ROOT / "contracts" / "product-authorization"


class ProductAuthorizationFixtureSchemaTest(unittest.TestCase):
    def test_registry_lineage_is_exact_to_the_current_immutable_index(self) -> None:
        index = json.loads(
            (CONTRACT_DIRECTORY / "product-surfaces-v1.index.json").read_text(
                encoding="utf-8"
            )
        )
        schema = json.loads(
            (CONTRACT_DIRECTORY / "pilot-fixtures.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        lineage = schema["properties"]["registryLineage"]["properties"]
        versions = lineage["versions"]
        expected = [
            {
                "bundleKey": index["bundleKey"],
                "version": release["version"],
                "sha256": release["checksum"],
            }
            for release in index["versions"]
        ]

        self.assertEqual(lineage["latestAliasVersion"]["const"], index["latestVersion"])
        self.assertEqual(versions["items"], {"$ref": "#/$defs/registryReference"})
        self.assertTrue(versions["uniqueItems"])
        self.assertEqual(versions["minItems"], len(expected))
        self.assertEqual(versions["maxItems"], len(expected))
        self.assertEqual(schema["$defs"]["registryReference"]["enum"], expected)


if __name__ == "__main__":
    unittest.main()
