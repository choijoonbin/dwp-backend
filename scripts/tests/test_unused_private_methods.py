import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-unused-private-methods.py"
SPEC = importlib.util.spec_from_file_location("check_unused_private_methods", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class UnusedPrivateMethodCheckTest(unittest.TestCase):
    def fixture(self, source: str) -> Path:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        target = root / "sample/src/main/java/example/Sample.java"
        target.parent.mkdir(parents=True)
        target.write_text(source, encoding="utf-8")
        return root

    def test_rejects_an_unreferenced_private_method(self):
        root = self.fixture("class Sample {\n    private void orphan() {}\n}\n")
        self.assertEqual(
            MODULE.scan(root), ["sample/src/main/java/example/Sample.java:2#orphan"]
        )

    def test_accepts_a_called_private_method(self):
        root = self.fixture(
            "class Sample {\n    void run() { helper(); }\n    private void helper() {}\n}\n"
        )
        self.assertEqual(MODULE.scan(root), [])

    def test_comments_and_literals_do_not_hide_dead_code(self):
        root = self.fixture(
            'class Sample {\n    private void orphan() {} // orphan\n    String value = "orphan";\n}\n'
        )
        self.assertEqual(
            MODULE.scan(root), ["sample/src/main/java/example/Sample.java:2#orphan"]
        )

    def test_preserves_annotated_reflection_entrypoints(self):
        root = self.fixture(
            "class Sample {\n    @jakarta.annotation.PostConstruct\n    private void initialize() {}\n}\n"
        )
        self.assertEqual(MODULE.scan(root), [])


if __name__ == "__main__":
    unittest.main()
