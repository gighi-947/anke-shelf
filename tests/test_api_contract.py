"""API 契约漂移守卫（P1）：后端 _HANDLERS ↔ 前端 api-client.js ↔ bridge.js MOCKS。"""
import re
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


def _bridge_mock_names() -> set[str]:
    text = (WEB_JS / "bridge.js").read_text(encoding="utf-8")
    m = re.search(r"const MOCKS = \{(.*?)\n  \};", text, re.S)
    if not m:
        return set()
    return set(re.findall(r"^\s{4}([a-z][a-z0-9_]*):\s*async", m.group(1), re.M))


class ApiContractTest(unittest.TestCase):
    def test_backend_handlers_covered_by_client(self):
        backend = {item["name"] for item in api_manifest()}
        client = _client_method_names()
        self.assertEqual(backend - client, set(), "JS api-client 缺少后端方法")
        self.assertEqual(client - backend, set(), "JS api-client 多出未知方法")

    def test_backend_handlers_covered_by_bridge_mocks(self):
        backend = {item["name"] for item in api_manifest()}
        mocks = _bridge_mock_names()
        self.assertEqual(backend - mocks, set(), "bridge.js MOCKS 缺少后端方法")


if __name__ == "__main__":
    unittest.main()
