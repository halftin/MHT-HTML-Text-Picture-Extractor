@echo off
REM ============================================================================
REM  MHT Extractor launcher  --  for the "dynamic" recipe (JavaFX DLLs shipped in
REM  the same folder).  The build copies this file into the dist as 启动.cmd.
REM
REM  WHY THIS EXISTS
REM  ---------------
REM  With the dynamic recipe the JavaFX native libraries (glass, prism_common,
REM  prism_d3d, prism_sw, decora_sse, javafx_font, javafx_iio) are loaded at run
REM  time by System.loadLibrary(). GraalVM's Substrate VM cannot handle a
REM  NON-ASCII directory in java.library.path -- and the very first entry of that
REM  list is the folder the EXE itself lives in. So an EXE placed under e.g.
REM  "C:\中文目录\" dies at startup with:
REM
REM      Graphics Device initialization failed for :  d3d, sw
REM      UnsatisfiedLinkError: Can't load library: prism_d3d
REM
REM  A SPACE in the path is fine; only non-ASCII breaks it. The "static" recipe
REM  links JavaFX into the EXE and is immune to this (see the build document).
REM
REM  WHAT IT DOES
REM  ------------
REM  Copies the DLLs to a pure-ASCII staging folder and points
REM  -Djava.library.path at it, then starts the application. When the folder
REM  path is already pure ASCII the staged copy is simply also valid.
REM
REM  This file is intentionally ASCII-only so cmd.exe (codepage 936/437/65001)
REM  always parses it correctly.
REM ============================================================================
setlocal enableextensions

REM GUI starts its companion and owns both stdio pipes; do not detach a backend here.
REM Root the GUI working directory in the distribution when invoked from elsewhere.
cd /d "%~dp0"

set "STAGE=%ProgramData%\MhtExtractor\native-dlls"
if not exist "%STAGE%" mkdir "%STAGE%" >nul 2>&1
if not exist "%STAGE%" set "STAGE=%PUBLIC%\MhtExtractor\native-dlls"
if not exist "%STAGE%" mkdir "%STAGE%" >nul 2>&1
if not exist "%STAGE%" goto :direct

REM Copy only new/changed DLLs (xcopy /D = only files newer than the target).
xcopy /Y /D /Q "%~dp0*.dll" "%STAGE%\" >nul 2>&1

start "" "%~dp0MhtExtractor.exe" -Djava.library.path="%STAGE%"
exit /b 0

:direct
REM Fallback: start in place. Succeeds only when the folder path is pure ASCII.
start "" "%~dp0MhtExtractor.exe"
exit /b 0
