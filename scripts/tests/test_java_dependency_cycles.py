from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CHECKER = Path(__file__).resolve().parents[1] / "check-java-dependency-cycles.py"
A = "com.example.A"
B = "com.example.B"


class JavaDependencyCycleRatchetTest(unittest.TestCase):

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        shutil.copy2(CHECKER, scripts / CHECKER.name)
        self.architecture = self.root / "docs" / "architecture"
        self.architecture.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_source(self, class_name: str, body: str) -> None:
        path = (
            self.root
            / "dwp-example"
            / "src"
            / "main"
            / "java"
            / "com"
            / "example"
            / f"{class_name}.java"
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            f"package com.example;\n\n{body}\n",
            encoding="utf-8",
        )

    def write_baseline(
        self,
        cycles: list[dict[str, object]],
    ) -> None:
        (self.architecture / "java-dependency-cycle-baseline.json").write_text(
            json.dumps({"schemaVersion": 1, "cycles": cycles}, indent=2) + "\n",
            encoding="utf-8",
        )

    def cycle(self, *, review_by: str = "2999-01-01") -> dict[str, object]:
        return {
            "id": "example-owner-helper",
            "owner": "example-team",
            "reason": "A temporary owner/helper nested-contract coupling.",
            "reviewBy": review_by,
            "runtimeComponentCycle": False,
            "members": [A, B],
        }

    def run_checker(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, f"scripts/{CHECKER.name}"],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_exact_compile_time_cycle_baseline_passes(self) -> None:
        self.write_source("A", "public class A { B helper; }")
        self.write_source("B", "final class B { A.Owner owner; }")
        self.write_baseline([self.cycle()])

        result = self.run_checker()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("1 exact legacy SCCs", result.stdout)
        self.assertIn("0 Spring constructor cycles", result.stdout)

    def test_new_cycle_fails_without_an_exception(self) -> None:
        self.write_source("A", "public class A { B helper; }")
        self.write_source("B", "final class B { A owner; }")
        self.write_baseline([])

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("new or expanded SCC", result.stderr)

    def test_removed_cycle_requires_stale_exception_cleanup(self) -> None:
        self.write_source("A", "public class A { B helper; }")
        self.write_source("B", "final class B {}")
        self.write_baseline([self.cycle()])

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("stale baseline example-owner-helper", result.stderr)

    def test_runtime_constructor_cycle_is_never_allowlisted(self) -> None:
        self.write_source(
            "A",
            """import org.springframework.stereotype.Service;
@Service
public class A {
    public A(B dependency) {}
}""",
        )
        self.write_source(
            "B",
            """import org.springframework.stereotype.Component;
@Component
public class B {
    public B(A dependency) {}
}""",
        )
        self.write_baseline([self.cycle()])

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("Spring constructor dependency cycles are never allowlisted", result.stderr)

    def test_expired_review_date_fails_closed(self) -> None:
        self.write_source("A", "public class A { B helper; }")
        self.write_source("B", "final class B { A owner; }")
        self.write_baseline([self.cycle(review_by="2000-01-01")])

        result = self.run_checker()

        self.assertEqual(1, result.returncode)
        self.assertIn("stale exception example-owner-helper", result.stderr)

    def test_comments_and_string_literals_do_not_create_dependencies(self) -> None:
        self.write_source(
            "A",
            'public class A { String text = "B"; /* B ignored; */ }',
        )
        self.write_source("B", "final class B { A owner; }")
        self.write_baseline([])

        result = self.run_checker()

        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
