# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec for PDF Converter (Windows EXE, single file, windowed GUI)
# Robust against the working directory: asset paths are resolved from the
# repository root (one level above the desktop/ folder).
#
# Build (from anywhere):
#     pyinstaller --noconfirm --clean desktop/pdf_converter.spec

import os

from PyInstaller.utils.hooks import collect_all

_here = os.path.dirname(os.path.abspath(SPECPATH))     # desktop/
_repo = os.path.abspath(os.path.join(_here, ".."))     # repo root

block_cipher = None

datas = []
binaries = []
hiddenimports = []

# Bundle assets (icon) and make sure PyMuPDF/OpenPyXL ship completely
for pkg in ("fitz", "openpyxl"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

assets_dir = os.path.join(_repo, "assets")
if os.path.isdir(assets_dir):
    datas.append((assets_dir, "assets"))

icon_path = os.path.join(assets_dir, "icon.ico")

a = Analysis(
    [os.path.join(_here, "app.py")],
    pathex=[_here],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "tkinter.test",
        "unittest",
        "pydoc",
        "test",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="PDFConverter",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,               # windowed GUI — no console window
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=icon_path,
)
