from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-source-size.py"
SOURCE = "dwp-example/src/main/java/com/dwp/example/LargeSource.java"
REDUCED_SOURCE = "dwp-example/src/main/java/com/dwp/example/ReducedSource.java"


class SourceSizeRatchetTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copy2(CHECKER, scripts / "check-source-size.py")
        self.baseline_file = scripts / "source-size-baseline.json"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_baseline(self, value: object) -> None:
        self.baseline_file.write_text(
            json.dumps(value, indent=2) + "\n", encoding="utf-8"
        )

    def write_source(self, relative: str, lines: int) -> None:
        source = self.root / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("// source line\n" * lines, encoding="utf-8")

    def run_checker(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "scripts/check-source-size.py", *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_check_rejects_headroom_after_a_file_shrinks(self) -> None:
        self.write_baseline({SOURCE: 710})
        self.write_source(SOURCE, 705)

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("reduced from 710 to 705 lines", result.stderr)
        self.assertIn("check-source-size.py --write", result.stderr)

    def test_writer_lowers_exact_limit_and_removes_unneeded_entries(self) -> None:
        self.write_baseline({SOURCE: 710, REDUCED_SOURCE: 705})
        self.write_source(SOURCE, 705)
        self.write_source(REDUCED_SOURCE, 700)

        result = self.run_checker("--write")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            {SOURCE: 705},
            json.loads(self.baseline_file.read_text(encoding="utf-8")),
        )
        self.assertEqual(0, self.run_checker().returncode)

    def test_writer_refuses_a_new_oversized_exception_without_mutating_baseline(self) -> None:
        self.write_baseline({})
        original = self.baseline_file.read_bytes()
        self.write_source(SOURCE, 705)

        result = self.run_checker("--write")

        self.assertEqual(1, result.returncode)
        self.assertIn("Refusing to raise the source-size baseline", result.stderr)
        self.assertIn("705 lines exceeds 700", result.stderr)
        self.assertEqual(original, self.baseline_file.read_bytes())

    def test_writer_refuses_increasing_an_existing_limit_without_mutation(self) -> None:
        self.write_baseline({SOURCE: 705})
        original = self.baseline_file.read_bytes()
        self.write_source(SOURCE, 706)

        result = self.run_checker("--write")

        self.assertEqual(1, result.returncode)
        self.assertIn("706 lines exceeds 705", result.stderr)
        self.assertEqual(original, self.baseline_file.read_bytes())

    def test_malformed_numeric_limits_fail_closed(self) -> None:
        invalid_limits = ("unbounded", 701.5, True, None, 700, 2**53)
        for invalid_limit in invalid_limits:
            with self.subTest(limit=invalid_limit):
                self.write_baseline({SOURCE: invalid_limit})

                result = self.run_checker()

                self.assertEqual(1, result.returncode)
                self.assertIn("Invalid source-size baseline", result.stderr)
                self.assertIn("safe integer limit greater than 700", result.stderr)

    def test_non_object_and_unsafe_path_baselines_fail_closed(self) -> None:
        for baseline in (
            [],
            {"../LargeSource.java": 705},
            {"dwp-example/src/test/java/LargeSource.java": 705},
        ):
            with self.subTest(baseline=baseline):
                self.write_baseline(baseline)

                result = self.run_checker()

                self.assertEqual(1, result.returncode)
                self.assertIn("Invalid source-size baseline", result.stderr)


if __name__ == "__main__":
    unittest.main()
