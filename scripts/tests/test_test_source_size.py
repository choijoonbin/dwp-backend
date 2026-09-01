from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-test-source-size.py"
TEST_SOURCE = "dwp-example/src/test/java/com/dwp/example/LargeTest.java"
REDUCED_TEST = "dwp-example/src/test/java/com/dwp/example/ReducedTest.java"


class TestSourceSizeRatchetTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copy2(CHECKER, scripts / "check-test-source-size.py")
        self.baseline_file = scripts / "test-source-size-baseline.json"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_baseline(self, value: object) -> None:
        self.baseline_file.write_text(
            json.dumps(value, indent=2) + "\n", encoding="utf-8"
        )

    def write_test(self, relative: str, lines: int) -> None:
        source = self.root / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("// test line\n" * lines, encoding="utf-8")

    def run_checker(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "scripts/check-test-source-size.py", *arguments],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_accepts_an_exact_legacy_test_exception(self) -> None:
        self.write_baseline({TEST_SOURCE: 1005})
        self.write_test(TEST_SOURCE, 1005)

        result = self.run_checker()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1 exact legacy exception", result.stdout)

    def test_rejects_a_new_test_above_the_default_limit(self) -> None:
        self.write_baseline({})
        self.write_test(TEST_SOURCE, 1001)

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("1001 lines exceeds 1000", result.stderr)

    def test_rejects_growth_above_an_exact_test_baseline(self) -> None:
        self.write_baseline({TEST_SOURCE: 1005})
        self.write_test(TEST_SOURCE, 1006)

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("1006 lines exceeds 1005", result.stderr)

    def test_rejects_stale_and_no_longer_needed_exceptions(self) -> None:
        self.write_baseline({TEST_SOURCE: 1005, REDUCED_TEST: 1010})
        self.write_test(TEST_SOURCE, 999)

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("no longer needs a baseline exception", result.stderr)
        self.assertIn("stale test source-size baseline entry", result.stderr)

    def test_requires_baseline_refresh_after_a_large_test_shrinks(self) -> None:
        self.write_baseline({TEST_SOURCE: 1010})
        self.write_test(TEST_SOURCE, 1005)

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("reduced from 1010 to 1005 lines", result.stderr)

    def test_writer_refuses_new_or_growing_test_exceptions(self) -> None:
        for baseline, lines in (({}, 1001), ({TEST_SOURCE: 1005}, 1006)):
            with self.subTest(baseline=baseline, lines=lines):
                self.write_baseline(baseline)
                self.write_test(TEST_SOURCE, lines)

                result = self.run_checker("--write")

                self.assertEqual(1, result.returncode)
                self.assertIn("Refusing to raise the test source-size baseline", result.stderr)

    def test_invalid_paths_and_limits_fail_closed(self) -> None:
        for baseline in (
            [],
            {"../LargeTest.java": 1005},
            {"dwp-example/src/main/java/LargeTest.java": 1005},
            {TEST_SOURCE: 1000},
            {TEST_SOURCE: "unbounded"},
        ):
            with self.subTest(baseline=baseline):
                self.write_baseline(baseline)

                result = self.run_checker()

                self.assertEqual(1, result.returncode)
                self.assertIn("Invalid test source-size baseline", result.stderr)


if __name__ == "__main__":
    unittest.main()
