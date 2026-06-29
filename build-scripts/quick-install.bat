@echo off
REM Quick install of rebuilt SRT Source Plugin DLL
REM Right-click this file and select "Run as administrator"

copy /Y "c:\Users\AMAL\Downloads\srt-camera-hub - WITHOBSPLUGIN\obs-plugin\build_x64\RelWithDebInfo\obs-srt-source.dll" "C:\Program Files\obs-studio\obs-plugins\64bit\obs-srt-source.dll"
if %errorlevel% equ 0 (
    echo [OK] DLL installed successfully
) else (
    echo [FAIL] Run this script as Administrator!
)
pause
