"""发行资产摘要生成器测试。"""
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.release_manifest import build_manifest

PROJECT = Path(__file__).resolve().parent.parent
SCRIPT = PROJECT / "scripts" / "release_manifest.py"


class ReleaseManifestTest(unittest.TestCase):
    def test_build_manifest_fields(self):
        text = build_manifest(
            version="v1.2.0",
            commit="abc123",
            contract_version="2",
            python_version="3.12",
            artifacts=[("zip", "dist/a.zip", "ABCDEF", 123)],
            built_at="2026-08-14T00:00:00+00:00",
        )
        self.assertIn("version=v1.2.0", text)
        self.assertIn("commit=abc123", text)
        self.assertIn("contract_version=2", text)
        self.assertIn("artifact=zip size=123 sha256=ABCDEF", text)

    def test_cli_writes_manifest_and_sha(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            zip_path = root / "sample.zip"
            with zipfile.ZipFile(zip_path, "w") as zf:
                zf.writestr("a.txt", "x")
            out = root / "release.txt"
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--version", "v9.9.9", "--zip", str(zip_path), "--out", str(out)],
                capture_output=True,
                text=True,
                timeout=60,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            content = out.read_text(encoding="utf-8")
            self.assertIn("version=v9.9.9", content)
            self.assertIn("sha256=", content)
            self.assertIn("contract_version=", content)
            self.assertTrue(Path(out).is_file())

    def test_cli_missing_artifact_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--version", "v1.0.0", "--zip", str(Path(tmp) / "nope.zip")],
                capture_output=True,
                text=True,
                timeout=60,
            )
            self.assertEqual(proc.returncode, 1)
            self.assertIn("not found", proc.stderr)


if __name__ == "__main__":
    unittest.main()
