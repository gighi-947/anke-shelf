#!/usr/bin/env python3
"""发行资产摘要：版本 / commit / 数据契约版本 / 构建环境 / 产物 SHA-256。

用法：
  python scripts/release_manifest.py --version v1.2.0 \
      --zip dist/AnkeShelf-v1.2.0.zip --out dist/AnkeShelf-v1.2.0.release.txt
"""
import argparse
import hashlib
import json
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
CONTRACT_VERSION_FILE = PROJECT / "contracts" / "progress" / "progress.schema.json"


def git_commit() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=PROJECT,
            capture_output=True,
            text=True,
            timeout=10,
        )
        return out.stdout.strip() or "unknown"
    except Exception:
        return "unknown"


def contract_version() -> str:
    try:
        data = json.loads(CONTRACT_VERSION_FILE.read_text(encoding="utf-8"))
        return str(data["properties"]["version"]["const"])
    except Exception:
        return "unknown"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def build_manifest(
    version: str,
    commit: str,
    contract_version: str,
    python_version: str,
    artifacts: list,
    built_at: str,
) -> str:
    lines = [
        f"version={version}",
        f"commit={commit}",
        f"contract_version={contract_version}",
        f"python={python_version}",
        f"platform={platform.platform()}",
        f"built_at={built_at}",
    ]
    for name, path, digest, size in artifacts:
        lines.append(f"artifact={name} size={size} sha256={digest} path={path}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="生成发行资产摘要")
    parser.add_argument("--version", required=True, help="发行版本，如 v1.2.0")
    parser.add_argument("--zip", help="Windows zip 产物路径（可选）")
    parser.add_argument("--apk", help="Android apk 产物路径（可选）")
    parser.add_argument("--out", help="输出文件路径（缺省打印到 stdout）")
    args = parser.parse_args()

    artifacts = []
    for label, value in (("zip", args.zip), ("apk", args.apk)):
        if not value:
            continue
        path = Path(value)
        if not path.is_file():
            print(f"error: artifact not found: {value}", file=sys.stderr)
            return 1
        artifacts.append((label, str(path), sha256(path), path.stat().st_size))

    text = build_manifest(
        version=args.version,
        commit=git_commit(),
        contract_version=contract_version(),
        python_version=platform.python_version(),
        artifacts=artifacts,
        built_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
    )
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
