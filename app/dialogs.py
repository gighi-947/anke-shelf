"""原生文件对话框：通过 PowerShell + WinForms OpenFileDialog 实现。

前后端分离后，文件选择由后端 HTTP 接口触发（前端不需要 pywebview 桥接），
本模块用系统自带 PowerShell 弹原生对话框并返回真实文件路径，
保留“导入后仍指向用户原文件”的语义。
"""
import base64
import json
import subprocess

_DIALOGS = {
    "epub": {
        "title": "选择 EPUB 文件",
        "filter": "EPUB 文件 (*.epub)|*.epub",
        "multi": True,
    },
    "font": {
        "title": "选择字体文件",
        "filter": "字体文件 (*.ttf;*.otf;*.ttc;*.woff;*.woff2)|*.ttf;*.otf;*.ttc;*.woff;*.woff2",
        "multi": False,
    },
    "backup": {
        "title": "选择备份包",
        "filter": "备份包 (*.zip)|*.zip",
        "multi": False,
    },
    "image": {
        "title": "选择封面图片",
        "filter": "图片文件 (*.jpg;*.jpeg;*.png;*.gif;*.webp)|*.jpg;*.jpeg;*.png;*.gif;*.webp",
        "multi": False,
    },
}


def pick_paths(kind: str) -> list[str]:
    """弹出对应类型的原生文件对话框，返回用户选择的路径列表（取消则空列表）。"""
    spec = _DIALOGS.get(kind)
    if spec is None:
        return []
    script = f"""
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$d = New-Object System.Windows.Forms.OpenFileDialog
$d.Title = '{spec['title']}'
$d.Filter = '{spec['filter']}'
$d.Multiselect = ${str(spec['multi']).lower()}
$d.RestoreDirectory = $true
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {{
    $d.FileNames | ConvertTo-Json -Compress
}} else {{
    '[]'
}}
"""
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-STA", "-EncodedCommand", encoded],
            capture_output=True,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    if proc.returncode != 0:
        return []
    lines = [ln.strip() for ln in proc.stdout.decode("utf-8", errors="replace").splitlines() if ln.strip()]
    if not lines:
        return []
    try:
        data = json.loads(lines[-1])
    except json.JSONDecodeError:
        return []
    if isinstance(data, str):
        data = [data]
    return [p for p in data if isinstance(p, str) and p]


def pick_epub_paths() -> list[str]:
    return pick_paths("epub")


def pick_font_paths() -> list[str]:
    return pick_paths("font")


def pick_folder(title: str = "选择导出文件夹") -> str:
    """弹出文件夹选择对话框，返回用户选择的目录（取消则空字符串）。"""
    script = f"""
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$d = New-Object System.Windows.Forms.FolderBrowserDialog
$d.Description = '{title}'
$d.ShowNewFolderButton = $true
if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {{
    $d.SelectedPath
}} else {{
    ''
}}
"""
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-STA", "-EncodedCommand", encoded],
            capture_output=True,
            timeout=120,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    if proc.returncode != 0:
        return ""
    lines = [ln.strip() for ln in proc.stdout.decode("utf-8", errors="replace").splitlines() if ln.strip()]
    return lines[-1] if lines else ""
