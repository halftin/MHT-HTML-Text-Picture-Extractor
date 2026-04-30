@echo off
REM ============================================================================
REM MHT资源批量提取器 - GraalVM Native Image 构建脚本
REM 用法: 在项目根目录 (Y:\instll\java) 运行此脚本
REM ============================================================================

setlocal enabledelayedexpansion

echo === Step 1: 设置环境变量 ===

REM 编译用 JDK 21 (Lombok 兼容)
set "BUILD_JAVA_HOME=C:\Users\Administrator\.jdks\liberica-full-21.0.11"

REM GraalVM JDK 25 (用于 native-image 编译)
set "GRAALVM_HOME=Y:\instll\graalvm-jdk-25.0.2+10.1"

REM Maven
set "MAVEN_HOME=Y:\instll\apache-maven-3.9.9"

echo === Step 2: 用 JDK 21 编译项目 ===
set JAVA_HOME=%BUILD_JAVA_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

call "%MAVEN_HOME%\bin\mvn.cmd" clean package -DskipTests -f pom.xml
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Maven 构建失败
    pause
    exit /b 1
)
echo 编译完成

echo === Step 3: 提取 JavaFX 原生 DLLs ===
if not exist "target\jfx-dlls" mkdir "target\jfx-dlls"
cd target\lib
for %%f in (glass.dll prism_common.dll prism_d3d.dll prism_sw.dll decora_sse.dll javafx_font.dll javafx_iio.dll msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll) do (
    "%GRAALVM_HOME%\bin\jar" xf javafx-graphics-21.0.2-win.jar %%f 2>nul
    if exist %%f move /Y %%f ..\jfx-dlls\ >nul 2>&1
)
cd ..\..
echo DLLs 已提取到 target\jfx-dlls\

echo === Step 4: 构建 Native Image (需要 1-3 分钟) ===
REM 初始化 Visual Studio 构建环境
call "Y:\instll\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [WARNING] 无法初始化 Visual Studio 环境, 尝试继续...
)

set JAVA_HOME=%GRAALVM_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

echo 正在编译 Native Image...
"%GRAALVM_HOME%\bin\native-image.cmd" ^
  -jar target/mhtExtractor-2.2-SNAPSHOT.jar ^
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
    echo [ERROR] Native Image 构建失败
    pause
    exit /b 1
)

echo === Step 5: 打包便携版 ===
if not exist "target\dist" mkdir "target\dist"
copy /Y "target\native-image\MhtExtractor.exe" "target\dist\" >nul
copy /Y "target\native-image\*.dll" "target\dist\" >nul
copy /Y "target\jfx-dlls\*.dll" "target\dist\" >nul

echo === Step 6: 隐藏控制台窗口 (GUI模式) ===
for /f "delims=" %%i in ('where /r "Y:\instll\Microsoft Visual Studio" editbin.exe 2^>nul ^| findstr /r "Hostx64.*editbin"') do set EDITBIN=%%i
if defined EDITBIN (
    "%EDITBIN%" /SUBSYSTEM:WINDOWS "target\dist\MhtExtractor.exe" >nul 2>&1
    echo GUI 模式已启用 - 双击运行不再显示黑窗口
) else (
    echo [WARNING] 未找到 editbin.exe，EXE 将以控制台模式运行
)

echo.
echo ============================================================
echo   构建成功!
echo   便携版位置: target\dist\
echo   - MhtExtractor.exe (约 58MB，无控制台窗口)
echo   - *.dll (JNI + JavaFX 运行库)
echo   直接分发 target\dist\ 文件夹即可，无需安装 JDK/JRE
echo ============================================================

endlocal
