import tempfile
import unittest
from pathlib import Path

from tools.verify_evidence import validate_row


class EvidenceTest(unittest.TestCase):
    def row(self):
        return dict(product="IDEA", build="263.7307.200", jbr="25", os="Linux",
                    plugin="3.3.0", feature="settings", command="integrationTest",
                    status="Passed", evidence=["result.log"])

    def test_passed_requires_build_and_evidence(self):
        row = self.row() | dict(build="", evidence=[])
        self.assertEqual(["Passed requires build and evidence"], validate_row(row))

    def test_unknown_status_never_promoted(self):
        self.assertEqual(["Unknown status"], validate_row(self.row() | dict(status="OK")))

    def test_all_versions_and_command_are_required_for_passed(self):
        for field in ("product", "jbr", "os", "plugin", "feature", "command"):
            with self.subTest(field=field):
                self.assertIn(f"Passed requires {field}", validate_row(self.row() | {field: ""}))

    def test_artifact_must_exist_inside_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(["Missing evidence: result.log"], validate_row(self.row(), root))
            (root / "result.log").write_text("actual run\n")
            self.assertEqual([], validate_row(self.row(), root))
            self.assertIn("Evidence must be repository-relative: ../result.log",
                          validate_row(self.row() | dict(evidence=["../result.log"]), root))

    def test_hardware_pass_requires_observed_model_and_server(self):
        row = self.row() | dict(kind="hardware")
        errors = validate_row(row)
        for field in ("model", "openrgb", "endpoint", "device", "observation"):
            self.assertIn(f"Passed hardware requires {field}", errors)

    def test_not_run_remains_not_run_without_invented_evidence(self):
        row = self.row() | dict(status="NotRun", build="", evidence=[])
        self.assertEqual([], validate_row(row))
        self.assertEqual("NotRun", row["status"])


if __name__ == "__main__":
    unittest.main()
