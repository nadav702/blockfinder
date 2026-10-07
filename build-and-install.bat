@echo off
setlocal
cd /d "%~dp0"
title BlockAtlas - build and install

echo ============================================
echo   BlockAtlas: build and install
echo ============================================
echo.

where java >nul 2>nul
if errorlevel 1 (
  echo [!] Java was not found.
  echo     Install JDK 25 from https://adoptium.net/temurin/releases/?version=25
  echo     ^(choose Windows x64, JDK, .msi, and tick "Set JAVA_HOME"^), then run this again.
  echo.
  pause
  exit /b 1
)

echo Java found:
java -version 2>&1 | findstr /i "version"
echo.
echo Building (the first build downloads Minecraft + Fabric, takes a few minutes)...
echo.

call gradlew.bat build --no-daemon
if errorlevel 1 (
  echo.
  echo [!] BUILD FAILED. Copy the red error lines above and send them to Claude.
  echo.
  pause
  exit /b 1
)

set "MODS=%APPDATA%\.minecraft\mods"
if not exist "%MODS%" mkdir "%MODS%"
copy /Y "build\libs\blockatlas-1.0.0.jar" "%MODS%\" >nul
if errorlevel 1 (
  echo [!] Could not copy the jar into %MODS%
  pause
  exit /b 1
)

echo.
echo ============================================
echo   Done! Installed:  %MODS%\blockatlas-1.0.0.jar
echo.
echo   Also needed in that same mods folder:
echo     Fabric API for 26.3  ^(https://modrinth.com/mod/fabric-api^)
echo   And Fabric Loader installed for 26.3:
echo     https://fabricmc.net/use/installer/
echo ============================================
echo.
pause
