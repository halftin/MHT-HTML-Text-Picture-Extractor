@echo off
setlocal enabledelayedexpansion

echo === Initializing MSVC environment ===
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] MSVC initialization failed, trying to continue...
)

echo === Setting up GraalVM ===
set JAVA_HOME=Y:\JAVA环境\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1
set PATH=%JAVA_HOME%\bin;%PATH%

echo === Verifying environment ===
echo JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java" -version 2>&1
echo.
echo === Starting Native Image compilation ===
cd /d Y:\JAVA环境\MHT

REM 版本号以 pom.xml 为唯一来源（禁止硬编码）
set "APP_VERSION="
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml -Raw)).project.version"') do set "APP_VERSION=%%i"
if not defined APP_VERSION (
    REM PowerShell 不可用时的兜底：findstr 直读 pom.xml 中 4 空格缩进的 <version>
    for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"^    <version>" pom.xml') do set "APP_VERSION=%%i"
)
if not defined APP_VERSION (
    echo [ERROR] 无法从 pom.xml 读取项目版本号
    exit /b 1
)
echo 项目版本: %APP_VERSION%

"%JAVA_HOME%\bin\native-image.cmd" ^
  -jar target/mhtExtractor-%APP_VERSION%.jar ^
  --no-fallback ^
  -H:Path=target/native-image ^
  -H:Name=MhtExtractor ^
  -H:+JNI ^
  -H:+ReportExceptionStackTraces ^
  --enable-all-security-services ^
  --enable-http --enable-https ^
  --enable-url-protocols=file,http,https ^
  --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin ^
  -Djava.library.path="target/jfx-dlls" ^
  -J-Xmx8G ^
  -Ob

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Native Image build failed
    exit /b 1
)

echo === Native Image build successful ===
endlocal
