# PowerShell script for Native Image build
$ErrorActionPreference = "Stop"

$ProjectDir = "Y:\JAVA环境\MHT"
$GraalVMHome = "Y:\JAVA环境\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1"
$VcvarsPath = "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"

# 版本号以 pom.xml 为唯一来源（禁止硬编码：历史问题见 build-native.cmd 注释）
$AppVersion = ([xml](Get-Content "$ProjectDir\pom.xml" -Raw)).project.version
if (-not $AppVersion) { throw "无法从 $ProjectDir\pom.xml 读取版本号" }
Write-Host "项目版本: $AppVersion"

Write-Host "=== Initializing MSVC environment ==="

# Run vcvars64.bat and capture its environment
$env:JAVA_HOME = $GraalVMHome
$env:PATH = "$GraalVMHome\bin;$env:PATH"

# Run vcvars64.bat in a cmd process, then run native-image in the same process
$buildScript = @"
@echo off
call "$VcvarsPath" >nul 2>&1
if errorlevel 1 (
    echo [WARNING] vcvars64.bat failed
)
set JAVA_HOME=$GraalVMHome
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "$ProjectDir"
echo === Starting Native Image ===
"%GraalVMHome%\bin\native-image.cmd" ^
  -jar target/mhtExtractor-$AppVersion.jar ^
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
exit /b %ERRORLEVEL%
"@

# Save to temp file
$tempBat = "$env:TEMP\build-mht-native.bat"
Set-Content -Path $tempBat -Value $buildScript -Encoding ASCII

Write-Host "=== Running build ==="
$process = Start-Process -FilePath "cmd.exe" -ArgumentList "/c `"$tempBat`"" -Wait -NoNewWindow -PassThru

if ($process.ExitCode -ne 0) {
    Write-Host "[ERROR] Native Image build failed with exit code: $($process.ExitCode)"
    exit $process.ExitCode
}

Write-Host "=== Native Image build complete ==="
