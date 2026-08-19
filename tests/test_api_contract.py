"""API 契约漂移守卫（P1）：后端 _HANDLERS ↔ 前端 api-client.js。"""
import re
import subprocess
import sys
import unittest
from pathlib import Path

from app.api import api_manifest

PROJECT = Path(__file__).resolve().parent.parent
WEB_JS = PROJECT / "web" / "js"


def _client_method_names() -> set[str]:
    text = (WEB_JS / "api-client.js").read_text(encoding="utf-8")
    return {
        m.group(1)
        for m in re.finditer(
            r"\['([a-z][a-z0-9_]*)'\s*,\s*'[A-Za-z][A-Za-z0-9_]*'\]",
            text,
        )
    }


class ApiContractTest(unittest.TestCase):
    def test_manifest_load_does_not_require_product_network_or_crypto_packages(self):
        script = r'''
import importlib.abc
import sys

class BlockProductPackages(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname.split(".", 1)[0] in {"httpx", "cryptography"}:
            raise ModuleNotFoundError(f"blocked optional product package: {fullname}")
        return None

sys.meta_path.insert(0, BlockProductPackages())
from app.api import api_manifest
assert api_manifest()
'''
        result = subprocess.run(
            [sys.executable, "-c", script],
            cwd=PROJECT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)

    def test_backend_handlers_covered_by_client(self):
        backend = {item["name"] for item in api_manifest()}
        client = _client_method_names()
        self.assertEqual(backend - client, set(), "JS api-client 缺少后端方法")
        self.assertEqual(client - backend, set(), "JS api-client 多出未知方法")


if __name__ == "__main__":
    unittest.main()
