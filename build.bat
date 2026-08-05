@echo off
REM 一键打包为 Windows 目录版发行包（需先 pip install pyinstaller）
cd /d %~dp0
pyinstaller --noconfirm --clean ankeshelf.spec
echo.
echo 打包完成：dist\AnkeShelf\AnkeShelf.exe
pause
