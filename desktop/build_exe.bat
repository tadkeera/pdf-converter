@echo off
REM =====================================================================
REM  PDF Converter - Windows EXE build script
REM  Produces a single self-contained .exe (all libraries bundled inside)
REM  =====================================================================
setlocal

cd /d "%~dp0"

echo [1/3] Creating virtual environment...
if not exist ".venv" (
    python -m venv .venv
)
call .venv\Scripts\activate.bat

echo [2/3] Installing dependencies...
python -m pip install --upgrade pip
pip install -r requirements.txt pyinstaller==6.9.0

echo [3/3] Building EXE with PyInstaller...
pyinstaller --noconfirm --clean pdf_converter.spec

echo.
echo DONE! Your standalone executable is at:
echo     dist\PDFConverter.exe
echo.
echo You can now copy PDFConverter.exe to any Windows machine and run it
echo directly - Python and all libraries are bundled inside.
pause
