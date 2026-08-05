# -*- mode: python ; coding: utf-8 -*-
# Directory release build: AnkeShelf\AnkeShelf.exe plus dependency
# DLLs/PYDs next to it. One-file mode is known to freeze pywebview's .NET
# backend (pythonnet/clr_loader) on some machines after the window opens;
# the onedir layout avoids unpacking .NET assemblies into a temp dir and is
# the standard, more stable distribution form for this stack.
import sys

from PyInstaller.utils.hooks import collect_submodules

# Make ngapost2md importable for collect_submodules during the build.
sys.path.insert(0, "ngapost2md-python")

datas = [
    ("web", "web"),
    ("ngapost2md-python/config.ini.example", "ngapost2md-python"),
    ("ngapost2md-python/ngapost2md", "ngapost2md-python/ngapost2md"),
]

a = Analysis(
    ["run_app.py"],
    pathex=["ngapost2md-python"],
    binaries=[],
    datas=datas,
    hiddenimports=(
        collect_submodules("webview.platforms")
        + collect_submodules("ngapost2md")
        + ["webview.platforms.edgechromium", "bottle"]
    ),
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["PyQt5", "PyQt6", "PySide2", "PySide6", "gtk", "tkinter", "numpy"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="AnkeShelf",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="AnkeShelf",
)
