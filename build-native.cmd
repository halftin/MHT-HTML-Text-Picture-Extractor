@echo off
REM ============================================================================
REM  MHT 资源批量提取器 —— GraalVM Native Image 构建（**唯一实现**）
REM
REM  其余入口 run-native-image.bat / build-native.ps1 / build-native.sh 均已改为
REM  **委托调用本脚本**。历史问题：四份脚本各写死自己的 X:\ / Y:\ 路径与 Visual Studio
REM  版本号（甚至写到 VS "18"），且 native-image 的参数列表被抄了三份。
REM
REM  用法：
REM    build-native.cmd                 完整构建：预检 -> mvn package -> 抽 DLL -> native-image -> 组装 dist
REM    build-native.cmd --prepare-only  只做 JVM 侧准备（版本/打包/抽 DLL），**不调用 native-image**
REM    build-native.cmd --skip-package  跳过 mvn package（target 下已有 jar）
REM    build-native.cmd --no-editbin    不改子系统（保留控制台，便于看启动期崩溃信息）
REM
REM  可用环境变量覆盖（不设则自动探测）：
REM    BUILD_JAVA_HOME  编译用 JDK           GRAALVM_HOME  native-image 所在 JDK
REM    MAVEN_CMD        mvn.cmd 全路径       OUTPUT_DIR    产物目录（默认 <仓库>\dist）
REM    NATIVE_XMX       -J-Xmx（默认 4G）    VSWHERE       vswhere.exe 全路径
REM
REM  退出码：0 成功 / 1 其它失败 / 20 缺 MSVC 工具链（与 native-image 同码，便于脚本判断）
REM ============================================================================
setlocal enabledelayedexpansion
pushd "%~dp0"

set "REPO=%~dp0"
if "%REPO:~-1%"=="\" set "REPO=%REPO:~0,-1%"

set "SKIP_PACKAGE="
set "NO_EDITBIN="
set "PREPARE_ONLY="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--skip-package" set "SKIP_PACKAGE=1"
if /i "%~1"=="--no-editbin"   set "NO_EDITBIN=1"
if /i "%~1"=="--prepare-only" set "PREPARE_ONLY=1"
shift
goto parse
:parsed

echo ============================================================
echo  MHT Extractor - Native Image 构建
echo  仓库: %REPO%
echo ============================================================

REM ---------- 版本号：pom.xml 是唯一来源，禁止硬编码 ----------
set "APP_VERSION="
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml -Raw)).project.version"') do set "APP_VERSION=%%i"
if not defined APP_VERSION for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"^    <version>" pom.xml') do set "APP_VERSION=%%i"
if not defined APP_VERSION ( echo [ERROR] 无法从 pom.xml 读取版本号 & goto :fail )

set "JFX_VERSION="
for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"<javafx.version>" pom.xml') do set "JFX_VERSION=%%i"
if not defined JFX_VERSION ( echo [ERROR] 无法从 pom.xml 读取 javafx.version & goto :fail )
echo [1/5] 版本 %APP_VERSION%   JavaFX %JFX_VERSION%

REM ---------- 探测 JDK ----------
if not defined BUILD_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BUILD_JAVA_HOME=%JAVA_HOME%"
if not defined BUILD_JAVA_HOME if exist "D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\javac.exe" set "BUILD_JAVA_HOME=D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3"
if not defined BUILD_JAVA_HOME ( echo [ERROR] 找不到 JDK。请设 BUILD_JAVA_HOME 指向含 bin\javac.exe 的 JDK 25。 & goto :fail )

REM ---------- 探测 native-image（NIK 自带 native-image，无需单独装 GraalVM）----------
REM ⚠️ 必须与"编译用 JDK"**独立解析**：本机 JAVA_HOME 实测指向 D:\install\New Folder，
REM    那是个纯 JDK 25（有 javac、**没有** native-image）。若先定编译 JDK 再只在它里面找
REM    native-image，就会误报"找不到 native-image"。
REM 候选顺序：显式 GRAALVM_HOME（须验有效）-> 编译 JDK -> JAVA_HOME -> 本机 NIK 路径
set "NATIVE_IMAGE="
if defined GRAALVM_HOME if not exist "%GRAALVM_HOME%\bin\native-image.cmd" set "GRAALVM_HOME="
if not defined NATIVE_IMAGE if exist "%BUILD_JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%BUILD_JAVA_HOME%"
if not defined NATIVE_IMAGE if defined GRAALVM_HOME if exist "%GRAALVM_HOME%\bin\native-image.cmd" set "NATIVE_IMAGE=%GRAALVM_HOME%\bin\native-image.cmd"
if not defined NATIVE_IMAGE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%JAVA_HOME%"
if not defined NATIVE_IMAGE if defined GRAALVM_HOME if exist "%GRAALVM_HOME%\bin\native-image.cmd" set "NATIVE_IMAGE=%GRAALVM_HOME%\bin\native-image.cmd"
if not defined NATIVE_IMAGE if exist "D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\native-image.cmd" set "GRAALVM_HOME=D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3"
if not defined NATIVE_IMAGE if defined GRAALVM_HOME if exist "%GRAALVM_HOME%\bin\native-image.cmd" set "NATIVE_IMAGE=%GRAALVM_HOME%\bin\native-image.cmd"
if not defined NATIVE_IMAGE ( echo [ERROR] 找不到 native-image.cmd。NIK 自带 native-image；请设 GRAALVM_HOME 指向它。 & goto :fail )

REM ---------- 探测 Maven ----------
REM ⚠️ dir 的 /s 不支持**中间目录**通配（实测 `dists\apache-maven-*\*\bin\mvn.cmd` 恒无输出），
REM    必须把 mvn.cmd 作为**文件名**交给 /s 递归查找。wrapper 真实层级为
REM    dists\apache-maven-<ver>-bin\<hash>\apache-maven-<ver>\bin\mvn.cmd（三层）。
if not defined MAVEN_CMD for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD if exist "mvnw.cmd" set "MAVEN_CMD=%CD%\mvnw.cmd"
if not defined MAVEN_CMD for /f "delims=" %%i in ('dir /b /s "%USERPROFILE%\.m2\wrapper\dists\mvn.cmd" 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD ( echo [ERROR] 找不到 mvn.cmd：PATH 与 Maven wrapper 目录都没有。请设 MAVEN_CMD。 & goto :fail )

if not defined OUTPUT_DIR set "OUTPUT_DIR=%REPO%\dist"
if not defined NATIVE_XMX set "NATIVE_XMX=4G"

echo       JDK        : %BUILD_JAVA_HOME%
echo       native-image: %NATIVE_IMAGE%
echo       Maven      : %MAVEN_CMD%
echo       输出目录    : %OUTPUT_DIR%

REM ---------- MSVC 预检（--prepare-only 时跳过）----------
if "%PREPARE_ONLY%"=="1" goto :skip_vs

if not defined VSWHERE set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" goto :vs_found
for /f "delims=" %%i in ('where vswhere.exe 2^>nul') do if not defined VSWHERE set "VSWHERE=%%i"
:vs_found
if exist "%VSWHERE%" goto :vs_query
echo.
echo [ERROR] 本机没有 MSVC 工具链 —— GraalVM native-image 在 Windows 上**必须**有 MSVC
echo         （不支持 MinGW）。缺的是 vswhere.exe。
echo.
echo   实测复现 ^(2026-09-27^): 连最小 HelloWorld 都失败, exit=20
echo       "Failed to find 'vswhere.exe' for locating Visual Studio installations."
echo.
echo   修复：安装 Visual Studio 2022 Build Tools 17.6+ 并勾选「使用 C++ 的桌面开发」
echo         下载      https://aka.ms/vs/17/release/vs_BuildTools.exe
echo         静默安装  vs_BuildTools.exe --quiet --wait --norestart --nocache
echo                   --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended
echo.
goto :fail_vs

:vs_query
set "VSPATH="
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
if defined VSPATH goto :vs_path_ok
echo [ERROR] 找到 vswhere.exe，但没有安装「使用 C++ 的桌面开发」工作负载（缺 VC.Tools.x86.x64）。
goto :fail_vs

:vs_path_ok
set "VCVARS=%VSPATH%\VC\Auxiliary\Build\vcvars64.bat"
if exist "%VCVARS%" goto :vs_vcvars_ok
echo [ERROR] 找不到 vcvars64.bat: %VCVARS%
goto :fail_vs

:vs_vcvars_ok
for /f "delims=" %%i in ('dir /b /s "%VSPATH%\VC\Tools\MSVC\*\bin\Hostx64\x64\editbin.exe" 2^>nul') do if not defined EDITBIN set "EDITBIN=%%i"
echo [2/5] MSVC %VSPATH%
goto :after_vs

:skip_vs
echo [2/5] 跳过 MSVC 预检（--prepare-only）
:after_vs

REM ---------- Maven 打包 ----------
if "%SKIP_PACKAGE%"=="1" goto :skip_maven
echo [3/5] Maven 打包（mvn clean package -DskipTests）...
set "JAVA_HOME=%BUILD_JAVA_HOME%"
set "PATH=%BUILD_JAVA_HOME%\bin;%PATH%"
call "%MAVEN_CMD%" clean package -DskipTests -f pom.xml
if errorlevel 1 ( echo [ERROR] Maven 构建失败 & goto :fail )
goto :maven_done
:skip_maven
echo [3/5] 跳过 Maven 打包（--skip-package）
:maven_done

REM ---------- 抽取 JavaFX 原生 DLL ----------
echo [4/5] 抽取 JavaFX 原生 DLL...
if not exist "target\lib" ( echo [ERROR] 缺少 target\lib —— 请先跑一次完整打包（不要用 --skip-package）。 & goto :fail )
set "JFX_JAR=javafx-graphics-%JFX_VERSION%-win.jar"
if not exist "target\lib\%JFX_JAR%" ( echo [ERROR] 找不到 target\lib\%JFX_JAR% & goto :fail )
if not exist "target\jfx-dlls" mkdir "target\jfx-dlls"
pushd "target\lib"
for %%f in (glass.dll prism_common.dll prism_d3d.dll prism_sw.dll decora_sse.dll javafx_font.dll javafx_iio.dll msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll) do (
    "%BUILD_JAVA_HOME%\bin\jar" xf "%JFX_JAR%" %%f >nul 2>&1
    if exist "%%f" move /Y "%%f" "..\jfx-dlls\" >nul
)
popd
set /a DLL_COUNT=0
for %%f in ("target\jfx-dlls\*.dll") do set /a DLL_COUNT+=1
echo       已抽取 !DLL_COUNT! 个 DLL 到 target\jfx-dlls

if "%PREPARE_ONLY%"=="1" goto :prepared

REM ---------- native-image ----------
echo [5/5] native-image 编译（首次约 1-3 分钟）...
call "%VCVARS%" >nul 2>&1
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
"%NATIVE_IMAGE%" ^
  -jar "target\mhtExtractor-%APP_VERSION%.jar" ^
  --no-fallback ^
  -H:Path="%OUTPUT_DIR%" ^
  -H:Name=MhtExtractor ^
  -H:+JNI ^
  -H:+ReportExceptionStackTraces ^
  --enable-all-security-services ^
  --enable-http --enable-https ^
  --enable-url-protocols=file,http,https ^
  --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin ^
  -Djava.library.path="target/jfx-dlls" ^
  -J-Xmx%NATIVE_XMX% ^
  -Ob
if errorlevel 1 ( echo [ERROR] native-image 构建失败 & goto :fail )

REM ---------- 组装绿色便携目录 ----------
copy /Y "target\jfx-dlls\*.dll" "%OUTPUT_DIR%" >nul
if "%NO_EDITBIN%"=="1" goto :skip_editbin
if not defined EDITBIN goto :no_editbin
"%EDITBIN%" /SUBSYSTEM:WINDOWS "%OUTPUT_DIR%\MhtExtractor.exe" >nul 2>&1
echo       GUI 子系统已启用（双击不再弹黑窗）
goto :skip_editbin
:no_editbin
echo       [WARN] 未找到 editbin.exe，EXE 保持控制台子系统（双击会带黑窗）
:skip_editbin

echo.
echo ============================================================
echo   构建成功
echo   版本    : %APP_VERSION%
echo   输出目录: %OUTPUT_DIR%
echo   内容    : MhtExtractor.exe + %DLL_COUNT% 个 DLL（JNI/JavaFX/VC 运行库）
echo   分发    : 整个目录拷走即可，无需安装 JDK/JRE
echo ============================================================
goto :done

:prepared
echo.
echo [OK] --prepare-only 完成：JVM 侧已就绪（jar + target\jfx-dlls，%DLL_COUNT% 个 DLL）。
echo      native-image 未调用 —— 它需要 MSVC 工具链。
goto :done

:fail_vs
popd
endlocal
exit /b 20

:fail
popd
endlocal
exit /b 1

:done
popd
endlocal
exit /b 0
