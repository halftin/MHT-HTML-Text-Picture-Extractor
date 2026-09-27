@echo off
REM ============================================================================
REM  MHT 资源批量提取器 —— GraalVM Native Image 构建（**唯一实现**）
REM
REM  其余入口 run-native-image.bat / build-native.ps1 / build-native.sh 均委托调用本脚本。
REM
REM  ★ 可观测性契约（打包机上给智能体的实时反馈）★
REM    1) 全过程**实时**同时输出到控制台与日志文件（经 native-tee.ps1）。
REM       智能体只需 tail 日志文件即可拿到完整数据流；控制台亦不丢失。
REM    2) 每步在日志里留下结构化标记：
REM         [STEP k/n] BEGIN <名称>  (<ISO 时间>)
REM         [STEP k/n] END   <名称>  exit=<码>
REM       并写入 %STEPS%（TSV: step / name / begin / end / exit，分隔符为 TAB）。
REM    3) 结束时产出机器可读报告 <LOG_DIR>\native-build-<stamp>.report.json（放在 logs/ 以保持 dist/ 只有交付物）：
REM       上下文（JDK/native-image/Maven/VS/版本）-> **输入**（jar 体积+SHA256）
REM       -> 各步耗时与退出码 -> **产物清单**（每个文件的体积+SHA256）。
REM    4) 失败时打印 === FAILURE === 现场（失败步 + 退出码 + 日志尾部）后仍产出报告。
REM
REM  用法：
REM    build-native.cmd                 完整构建
REM    build-native.cmd --prepare-only  只做 JVM 侧准备（不调用 native-image）
REM    build-native.cmd --skip-package  跳过 mvn package
REM    build-native.cmd --no-editbin    保留控制台子系统（便于看启动期崩溃）
REM
REM  环境变量覆盖：BUILD_JAVA_HOME / GRAALVM_HOME / MAVEN_CMD / OUTPUT_DIR /
REM               NATIVE_XMX（默认 4G）/ VSWHERE / LOG_DIR（默认 <仓库>\logs）
REM  退出码：0 成功 / 1 其它失败 / 20 缺 MSVC 工具链（与 native-image 同码）
REM ============================================================================
setlocal enabledelayedexpansion
pushd "%~dp0"

set "REPO=%~dp0"
if "%REPO:~-1%"=="\" set "REPO=%REPO:~0,-1%"
set "TMPCMD=%TEMP%\mht-native-step.cmd"

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

REM ---------- 日志与报告骨架 ----------
if not defined LOG_DIR set "LOG_DIR=%REPO%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
for /f "delims=" %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "RUNSTAMP=%%i"
set "LOG=%LOG_DIR%\native-build-%RUNSTAMP%.log"
set "STEPS=%LOG_DIR%\native-build-%RUNSTAMP%.steps.tsv"
set "STATUS=FAILED"
set "FAILSTEP="
set "RC=0"

call :say "============================================================"
call :say " MHT Extractor - Native Image 构建"
call :say " 仓库   : %REPO%"
call :say " 日志   : %LOG%"
call :say " 参数   : %*"
call :say " 开始   : %DATE% %TIME%"
call :say "============================================================"

REM ==================== 步骤 1/6：环境探测 ====================
call :begin "1/6" "环境探测"

set "APP_VERSION="
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml -Raw)).project.version"') do set "APP_VERSION=%%i"
if not defined APP_VERSION for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"^    <version>" pom.xml') do set "APP_VERSION=%%i"
if not defined APP_VERSION ( call :say "[ERROR] 无法从 pom.xml 读取版本号" & set "RC=1" & goto :done )

set "JFX_VERSION="
for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"<javafx.version>" pom.xml') do set "JFX_VERSION=%%i"
if not defined JFX_VERSION ( call :say "[ERROR] 无法从 pom.xml 读取 javafx.version" & set "RC=1" & goto :done )

REM 编译用 JDK 与 native-image JDK **必须独立解析**（本机 JAVA_HOME 实测是纯 JDK，无 native-image）
if not defined BUILD_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BUILD_JAVA_HOME=%JAVA_HOME%"
if not defined BUILD_JAVA_HOME if exist "D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\javac.exe" set "BUILD_JAVA_HOME=D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3"
if not defined BUILD_JAVA_HOME ( call :say "[ERROR] 找不到 JDK。请设 BUILD_JAVA_HOME 指向含 bin\javac.exe 的 JDK 25。" & set "RC=1" & goto :done )

if defined GRAALVM_HOME if not exist "%GRAALVM_HOME%\bin\native-image.cmd" set "GRAALVM_HOME="
if not defined GRAALVM_HOME if exist "%BUILD_JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%BUILD_JAVA_HOME%"
if not defined GRAALVM_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%JAVA_HOME%"
if not defined GRAALVM_HOME if exist "D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\native-image.cmd" set "GRAALVM_HOME=D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3"
if not defined GRAALVM_HOME ( call :say "[ERROR] 找不到带 native-image 的 JDK（NIK 自带）。请设 GRAALVM_HOME。" & set "RC=1" & goto :done )
set "NATIVE_IMAGE=%GRAALVM_HOME%\bin\native-image.cmd"

REM dir 的 /s 不支持中间目录通配，必须把 mvn.cmd 作为**文件名**交给 /s
if not defined MAVEN_CMD for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD if exist "mvnw.cmd" set "MAVEN_CMD=%CD%\mvnw.cmd"
if not defined MAVEN_CMD for /f "delims=" %%i in ('dir /b /s "%USERPROFILE%\.m2\wrapper\dists\mvn.cmd" 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD ( call :say "[ERROR] 找不到 mvn.cmd（PATH 与 Maven wrapper 都没有）。请设 MAVEN_CMD。" & set "RC=1" & goto :done )

if not defined OUTPUT_DIR set "OUTPUT_DIR=%REPO%\dist"
if not defined NATIVE_XMX set "NATIVE_XMX=4G"

call :say "      版本        : %APP_VERSION%   JavaFX %JFX_VERSION%"
call :say "      编译 JDK    : %BUILD_JAVA_HOME%"
call :say "      native-image: %NATIVE_IMAGE%"
call :say "      Maven       : %MAVEN_CMD%"
call :say "      输出目录    : %OUTPUT_DIR%"
call :say "      -J-Xmx      : %NATIVE_XMX%"
call :end 0

REM ==================== 步骤 2/6：MSVC 预检 ====================
if "%PREPARE_ONLY%"=="1" goto :vs_skipped
call :begin "2/6" "MSVC 预检"

if not defined VSWHERE set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" goto :vs_found
for /f "delims=" %%i in ('where vswhere.exe 2^>nul') do if not defined VSWHERE set "VSWHERE=%%i"
:vs_found
if exist "%VSWHERE%" goto :vs_query
call :say "[ERROR] 本机没有 MSVC 工具链 —— GraalVM native-image 在 Windows 上必须要有 MSVC（不支持 MinGW）。"
call :say "        缺的是 vswhere.exe。实测复现：连最小 HelloWorld 都失败，exit=20"
call :say "        Failed to find vswhere.exe for locating Visual Studio installations."
call :say "        修复：安装 Visual Studio 2022 Build Tools 17.6+ 并勾选「使用 C++ 的桌面开发」"
call :say "              下载     https://aka.ms/vs/17/release/vs_BuildTools.exe"
call :say "              静默安装 vs_BuildTools.exe --quiet --wait --norestart --nocache --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
call :end 20
set "RC=20" & set "FAILSTEP=2/6 MSVC 预检" & goto :done

:vs_query
set "VSPATH="
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
if defined VSPATH goto :vs_path_ok
call :say "[ERROR] 找到 vswhere.exe，但没有装「使用 C++ 的桌面开发」工作负载（缺 VC.Tools.x86.x64）。"
call :end 20
set "RC=20" & set "FAILSTEP=2/6 MSVC 预检" & goto :done

:vs_path_ok
set "VCVARS=%VSPATH%\VC\Auxiliary\Build\vcvars64.bat"
if exist "%VCVARS%" goto :vs_ok
call :say "[ERROR] 找不到 vcvars64.bat: %VCVARS%"
call :end 20
set "RC=20" & set "FAILSTEP=2/6 MSVC 预检" & goto :done

:vs_ok
for /f "delims=" %%i in ('dir /b /s "%VSPATH%\VC\Tools\MSVC\*\bin\Hostx64\x64\editbin.exe" 2^>nul') do if not defined EDITBIN set "EDITBIN=%%i"
call :say "      MSVC     : %VSPATH%"
call :say "      vcvars64 : %VCVARS%"
call :say "      editbin  : %EDITBIN%"
call :end 0
goto :vs_done

:vs_skipped
call :begin "2/6" "MSVC 预检"
call :say "      已跳过（--prepare-only）"
call :end 0
:vs_done

REM ==================== 步骤 3/6：Maven 打包 ====================
call :begin "3/6" "Maven 打包"
if "%SKIP_PACKAGE%"=="1" goto :maven_skipped

set "JAVA_HOME=%BUILD_JAVA_HOME%"
set "PATH=%BUILD_JAVA_HOME%\bin;%PATH%"
call :say "      正在运行: mvn clean package -DskipTests"
> "%TMPCMD%" echo @echo off
>>"%TMPCMD%" echo call "%MAVEN_CMD%" clean package -DskipTests -f pom.xml
call :tee
if not "%RC%"=="0" ( call :say "[ERROR] Maven 构建失败，exit=%RC%" & call :end %RC% & set "FAILSTEP=3/6 Maven 打包" & goto :done )
call :end 0
goto :maven_done

:maven_skipped
call :say "      已跳过（--skip-package）"
call :end 0
:maven_done

REM ==================== 步骤 4/6：抽取 JavaFX 原生 DLL ====================
call :begin "4/6" "抽取 JavaFX 原生 DLL"
if not exist "target\lib" ( call :say "[ERROR] 缺少 target\lib —— 请先跑一次完整打包（不要用 --skip-package）。" & call :end 1 & set "RC=1" & set "FAILSTEP=4/6 抽取 DLL" & goto :done )
set "JFX_JAR=javafx-graphics-%JFX_VERSION%-win.jar"
if not exist "target\lib\%JFX_JAR%" ( call :say "[ERROR] 找不到 target\lib\%JFX_JAR%" & call :end 1 & set "RC=1" & set "FAILSTEP=4/6 抽取 DLL" & goto :done )
if not exist "target\jfx-dlls" mkdir "target\jfx-dlls"
pushd "target\lib"
for %%f in (glass.dll prism_common.dll prism_d3d.dll prism_sw.dll decora_sse.dll javafx_font.dll javafx_iio.dll msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll) do (
    "%BUILD_JAVA_HOME%\bin\jar" xf "%JFX_JAR%" %%f >nul 2>&1
    if exist "%%f" move /Y "%%f" "..\jfx-dlls\" >nul
)
popd
set /a DLL_COUNT=0
for %%f in ("target\jfx-dlls\*.dll") do set /a DLL_COUNT+=1
call :say "      已抽取 !DLL_COUNT! 个 DLL 到 target\jfx-dlls"
for %%f in ("target\jfx-dlls\*.dll") do call :say "        - %%~nxf  %%~zf 字节"
call :end 0

if "%PREPARE_ONLY%"=="1" ( set "STATUS=PREPARE_ONLY" & set "RC=0" & goto :done )

REM ==================== 步骤 5/6：native-image ====================
call :begin "5/6" "native-image 编译"
call :say "      正在运行: native-image -jar target\mhtExtractor-%APP_VERSION%.jar"
call :say "      （首次通常 1-3 分钟；输出会实时追加到上面的日志）"
call "%VCVARS%" >nul 2>&1
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

> "%TMPCMD%" echo @echo off
>>"%TMPCMD%" echo call "%NATIVE_IMAGE%" -jar "target\mhtExtractor-%APP_VERSION%.jar" --no-fallback -H:Path="%OUTPUT_DIR%" -H:Name=MhtExtractor -H:+JNI -H:+ReportExceptionStackTraces --enable-all-security-services --enable-http --enable-https --enable-url-protocols=file,http,https --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin -Djava.library.path="target/jfx-dlls" -J-Xmx%NATIVE_XMX% -Ob
call :tee
if not "%RC%"=="0" ( call :say "[ERROR] native-image 构建失败，exit=%RC%" & call :end %RC% & set "FAILSTEP=5/6 native-image" & goto :done )
call :end 0

REM ==================== 步骤 6/6：组装绿色便携目录 ====================
call :begin "6/6" "组装 dist"
copy /Y "target\jfx-dlls\*.dll" "%OUTPUT_DIR%" >nul
call :say "      已复制 !DLL_COUNT! 个 DLL 到 %OUTPUT_DIR%"
if "%NO_EDITBIN%"=="1" goto :skip_editbin
if not defined EDITBIN goto :no_editbin
"%EDITBIN%" /SUBSYSTEM:WINDOWS "%OUTPUT_DIR%\MhtExtractor.exe" >nul 2>&1
call :say "      editbin: GUI 子系统已启用（双击不再弹黑窗）"
goto :skip_editbin
:no_editbin
call :say "      [WARN] 未找到 editbin.exe，EXE 保持控制台子系统（双击会带黑窗）"
:skip_editbin
call :end 0
set "STATUS=OK"
set "RC=0"

:done
REM ---------- 失败现场（若有） ----------
if not "%STATUS%"=="FAILED" goto :report
call :say ""
call :say "=== FAILURE ==="
call :say " 失败步 : %FAILSTEP%"
call :say " 退出码 : %RC%"
call :say " 日志尾部（末 40 行）："
powershell -NoProfile -Command "if (Test-Path -LiteralPath '%LOG%') { Get-Content -LiteralPath '%LOG%' -Tail 40 | ForEach-Object { '   ' + $_ } }"
call :say "=== /FAILURE ==="

:report
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
set "NB_STEPS=%STEPS%"
set "REPORT=%LOG_DIR%\native-build-%RUNSTAMP%.report.json"
set "NB_OUT=%REPORT%"
set "NB_LOG=%LOG%"
set "NB_STATUS=%STATUS%"
set "NB_FAILSTEP=%FAILSTEP%"
set "NB_REPO=%REPO%"
set "NB_DIST=%OUTPUT_DIR%"
set "NB_APKVERSION=%APP_VERSION%"
set "NB_JFXVERSION=%JFX_VERSION%"
set "NB_JDK=%BUILD_JAVA_HOME%"
set "NB_NATIVEIMAGE=%NATIVE_IMAGE%"
set "NB_MAVEN=%MAVEN_CMD%"
set "NB_VSPATH=%VSPATH%"
set "NB_XMX=%NATIVE_XMX%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\native-report.ps1" 2>&1

call :say ""
call :say "============================================================"
if "%STATUS%"=="OK"           call :say " 构建成功"
if "%STATUS%"=="PREPARE_ONLY" call :say " JVM 侧准备完成（未调用 native-image）"
if "%STATUS%"=="FAILED"       call :say " 构建失败（详见上面的 FAILURE 段与日志）"
call :say " 状态        : %STATUS%   退出码: %RC%"
call :say " 版本        : %APP_VERSION%"
if "%STATUS%"=="OK" call :say " 输出目录    : %OUTPUT_DIR%"
if "%STATUS%"=="OK" call :say " 分发方式    : 整个目录拷走即可，无需安装 JDK/JRE"
call :say " 日志        : %LOG%"
call :say " 机器可读报告: %REPORT%"
call :say " 步骤明细    : %STEPS%"
call :say "============================================================"

popd
set "FINAL_RC=%RC%"
endlocal & exit /b %FINAL_RC%

REM ============================================================================
REM  子程序
REM ============================================================================

:say
REM 同时输出到控制台与日志（空参数要输出真正的空行，不能用 echo 裸调用）
if "%~1"=="" (
    echo.
    >>"%LOG%" echo.
) else (
    echo %~1
    >>"%LOG%" echo %~1
)
goto :eof

:stamp
for /f "delims=" %%t in ('powershell -NoProfile -Command "Get-Date -Format o"') do set "TS=%%t"
goto :eof

:begin
set "STEP_ID=%~1"
set "STEP_NAME=%~2"
call :stamp
set "STEP_BEGIN=%TS%"
call :say ""
call :say "[STEP %STEP_ID%] BEGIN %STEP_NAME%  (%TS%)"
goto :eof

:end
call :stamp
>>"%STEPS%" echo %STEP_ID%	%STEP_NAME%	%STEP_BEGIN%	%TS%	%~1
call :say "[STEP %STEP_ID%] END   %STEP_NAME%  exit=%~1"
goto :eof

:tee
REM 执行 %TMPCMD% 并把输出实时同时送到控制台与日志；退出码放入 RC
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\native-tee.ps1" -CmdFile "%TMPCMD%" -Log "%LOG%"
set "RC=%ERRORLEVEL%"
goto :eof
