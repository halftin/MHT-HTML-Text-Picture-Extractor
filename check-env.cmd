@echo off
REM ============================================================================
REM  MHT 资源批量提取器 —— 打包机环境预检（面向"新机器 clone 之后"的第一步）
REM
REM  为什么要它：build-native.cmd 会在缺 JDK / 缺 native-image / 缺 MSVC 时
REM  报错退出，但那已经是"跑到一半"了。这个脚本把同样的探测**提前**做一遍，
REM  一次把机器上缺什么、怎么装、装完能不能开始构建全说清楚。
REM
REM  用法：check-env.cmd          （在仓库根目录，或任意位置调用，脚本自己定位仓库）
REM
REM  退出码（与 build-native.cmd 对齐）：
REM    0  = 全部就绪，可以直接 build-native.cmd
REM    20 = 只缺 MSVC 工具链（native-image 在 Windows 上的唯一硬依赖）
REM    1  = 缺其它关键项（JDK / native-image / Maven / PowerShell）
REM
REM  ⚠️ 本脚本的探测顺序与 build-native.cmd **刻意保持一致**（JAVA_HOME -> PATH ->
REM     常见安装根目录通配），避免出现"预检说 OK、构建又找不到"的矛盾结论。
REM     改探测逻辑时两处要一起改。
REM
REM  ⚠️ 本文件必须 CRLF 且 ASCII 安全：Windows 上 cmd.exe 按当前代码页解析 .cmd。
REM ============================================================================
setlocal enabledelayedexpansion
pushd "%~dp0"

set "REPO=%CD%"
set "CRIT=0"
set "MSVC_MISSING=0"

call :say "============================================================"
call :say " MHT Extractor - 打包机环境预检"
call :say " 仓库 : %REPO%"
call :say " 时间 : %DATE% %TIME%"
call :say "============================================================"
call :say ""

REM ==================== 1. 项目根目录 ====================
call :say "[1/9] 项目文件"
if not exist "pom.xml" (
    call :say "  [FAIL] 当前目录没有 pom.xml —— 请在仓库根目录运行本脚本。"
    set "CRIT=1"
    goto :verdict
)
set "APP_VERSION="
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml -Raw)).project.version"') do set "APP_VERSION=%%i"
if not defined APP_VERSION for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"^    <version>" pom.xml') do set "APP_VERSION=%%i"
if defined APP_VERSION ( call :say "  [OK]   pom.xml 可读，版本 !APP_VERSION!" ) else ( call :say "  [WARN] 读不到 pom.xml 的版本号" )

REM ==================== 2. PowerShell ====================
call :say ""
call :say "[2/9] PowerShell（build-native.cmd 依赖它做时间戳 / 实时日志 / 构建报告）"
where powershell.exe >nul 2>&1
if errorlevel 1 (
    call :say "  [FAIL] 找不到 powershell.exe —— 构建脚本无法工作。"
    set "CRIT=1"
) else (
    call :say "  [OK]   powershell.exe 可用"
)

REM ==================== 3. 编译用 JDK ====================
call :say ""
call :say "[3/9] 编译用 JDK（需要 JDK 25）"
if defined BUILD_JAVA_HOME if not exist "%BUILD_JAVA_HOME%\bin\javac.exe" ( call :say "  [WARN] 环境里的 BUILD_JAVA_HOME 无效，忽略它" & set "BUILD_JAVA_HOME=" )
if not defined BUILD_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BUILD_JAVA_HOME=%JAVA_HOME%"
if not defined BUILD_JAVA_HOME for /f "delims=" %%i in ('where javac.exe 2^>nul') do if not defined BUILD_JAVA_HOME call :jdk_dir "%%~dpi.."
if not defined BUILD_JAVA_HOME call :detect_jdk
if not defined BUILD_JAVA_HOME (
    call :say "  [FAIL] 找不到 JDK。"
    call :say "         修复：设 BUILD_JAVA_HOME 指向 JDK 目录，或把该 JDK 的 bin 加进 PATH。"
    call :say "         推荐：BellSoft Liberica NIK 25 Full 版 https://bell-sw.com/pages/downloads/native-image-kit/"
    set "CRIT=1"
) else (
    for /f "delims=" %%v in ('"%BUILD_JAVA_HOME%\bin\javac.exe" -version 2^>^&1') do set "JAVAC_VER=%%v"
    call :say "  [OK]   !BUILD_JAVA_HOME!"
    call :say "         !JAVAC_VER!"
    echo !JAVAC_VER! | findstr /c:"25" >nul || call :say "  [WARN] 版本里没看到 25 —— pom 的 maven.compiler.release=25，用更低版本会编译失败。"
)

REM ==================== 4. native-image ====================
call :say ""
call :say "[4/9] native-image（NIK / GraalVM 自带，编译 JDK 与它是**分开解析**的）"
if defined GRAALVM_HOME if not exist "%GRAALVM_HOME%\bin\native-image.cmd" ( call :say "  [WARN] 环境里的 GRAALVM_HOME 无效，忽略它" & set "GRAALVM_HOME=" )
if not defined GRAALVM_HOME if defined BUILD_JAVA_HOME if exist "%BUILD_JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%BUILD_JAVA_HOME%"
if not defined GRAALVM_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%JAVA_HOME%"
if not defined GRAALVM_HOME call :detect_nik
if not defined GRAALVM_HOME (
    call :say "  [FAIL] 找不到 bin\native-image.cmd。"
    call :say "         修复：设 GRAALVM_HOME 指向 NIK / GraalVM 目录。"
    call :say "         注意：普通 JDK 没有 native-image，必须用 NIK 或 GraalVM 发行版。"
    set "CRIT=1"
) else (
    call :say "  [OK]   !GRAALVM_HOME!"
    call :say "         !GRAALVM_HOME!\bin\native-image.cmd"
)

REM ==================== 5. Maven ====================
call :say ""
call :say "[5/9] Maven"
set "MAVEN_CMD="
for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD if exist "mvnw.cmd" set "MAVEN_CMD=%CD%\mvnw.cmd"
if not defined MAVEN_CMD for /f "delims=" %%i in ('dir /b /s "%USERPROFILE%\.m2\wrapper\dists\mvn.cmd" 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD (
    call :say "  [FAIL] PATH、仓库内 mvnw.cmd、Maven wrapper 缓存里都没有 mvn.cmd。"
    call :say "         修复：装 Maven 3.9+ 并加进 PATH，或设 MAVEN_CMD 指向 mvn.cmd。"
    set "CRIT=1"
) else (
    call :say "  [OK]   !MAVEN_CMD!"
)

REM ==================== 6. MSVC 工具链 ====================
call :say ""
call :say "[6/9] MSVC 工具链（native-image 在 Windows 上的**唯一硬依赖**，不支持 MinGW）"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" for /f "delims=" %%i in ('where vswhere.exe 2^>nul') do set "VSWHERE=%%i"
if not exist "%VSWHERE%" (
    call :say "  [FAIL] 找不到 vswhere.exe —— 说明本机没有 Visual Studio / Build Tools。"
    call :say "         实测复现：连最小 HelloWorld 都失败，native-image 报 exit=20"
    call :say "         Failed to find 'vswhere.exe' for locating Visual Studio installations."
    call :say "         修复：装 VS 2022 Build Tools，勾选「使用 C++ 的桌面开发」："
    call :say "           curl -L -o vs_BuildTools.exe https://aka.ms/vs/17/release/vs_BuildTools.exe"
    call :say "           vs_BuildTools.exe --quiet --wait --norestart --nocache --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
    set "MSVC_MISSING=1"
    goto :after_msvc
)
call :say "  [OK]   vswhere: !VSWHERE!"
set "VSPATH="
for /f "usebackq delims=" %%i in (`"!VSWHERE!" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
if not defined VSPATH (
    call :say "  [FAIL] 有 vswhere.exe，但没有「使用 C++ 的桌面开发」工作负载（缺 VC.Tools.x86.x64）。"
    call :say "         修复：打开 Visual Studio Installer，勾选该工作负载后重装。"
    set "MSVC_MISSING=1"
    goto :after_msvc
)
set "VCVARS=!VSPATH!\VC\Auxiliary\Build\vcvars64.bat"
if not exist "!VCVARS!" (
    call :say "  [FAIL] 缺 vcvars64.bat: !VCVARS!"
    set "MSVC_MISSING=1"
    goto :after_msvc
)
call :say "  [OK]   VS: !VSPATH!"
call :say "  [OK]   vcvars64.bat"
set "EDITBIN="
for /f "delims=" %%i in ('dir /b /s "!VSPATH!\VC\Tools\MSVC\*\bin\Hostx64\x64\editbin.exe" 2^>nul') do if not defined EDITBIN set "EDITBIN=%%i"
if defined EDITBIN ( call :say "  [OK]   editbin.exe —— 构建后会用它把 EXE 切到 GUI 子系统，双击不弹黑窗" ) else ( call :say "  [WARN] 没找到 editbin.exe —— EXE 会保持控制台子系统，双击带黑窗" )
set "PF86=%ProgramFiles(x86)%"
if exist "!PF86!\Windows Kits\10\Include" ( call :say "  [OK]   Windows SDK" ) else ( call :say "  [WARN] 没看到 Windows Kits\10\Include —— SDK 通常随 C++ 工作负载一起装" )

:after_msvc

REM ==================== 7. 资源 ====================
call :say ""
call :say "[7/9] 内存与磁盘（native-image 默认 -J-Xmx4G，编译期需要数 GB 临时空间）"
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { $ram=[math]::Round((Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize/1MB,1); if($ram -lt 8){ Write-Output ('  [WARN] 物理内存 ' + $ram + ' GB 低于 8 GB，native-image 可能因内存不足失败；可调小 NATIVE_XMX') } else { Write-Output ('  [OK]   物理内存 ' + $ram + ' GB') } } catch { Write-Output '  [WARN] 读不到内存信息' }; try { $q=(Split-Path -Qualifier '%REPO%').TrimEnd(':'); $f=[math]::Round((Get-PSDrive $q).Free/1GB,1); if($f -lt 5){ Write-Output ('  [WARN] ' + $q + ': 空闲 ' + $f + ' GB 低于 5 GB') } else { Write-Output ('  [OK]   ' + $q + ': 空闲 ' + $f + ' GB') } } catch { Write-Output '  [WARN] 读不到磁盘信息' }; try { $t=(Split-Path -Qualifier $env:TEMP).TrimEnd(':'); $tf=[math]::Round((Get-PSDrive $t).Free/1GB,1); Write-Output ('  [OK]   TEMP 所在盘 ' + $t + ': 空闲 ' + $tf + ' GB') } catch { }"

REM ==================== 8. git ====================
call :say ""
call :say "[8/9] git（只影响构建报告里的 gitCommit 字段，不影响产物）"
where git.exe >nul 2>&1
if errorlevel 1 ( call :say "  [WARN] 找不到 git.exe —— 报告里 gitCommit 会是空" ) else ( call :say "  [OK]   git 可用" )

REM ==================== 9. 首次构建的网络 ====================
call :say ""
call :say "[9/9] 网络（首次构建要从 Maven 仓库拉依赖；若 Maven 是 wrapper 还会下发行版）"
ping -n 1 -w 1500 repo1.maven.org >nul 2>&1
if errorlevel 1 ( call :say "  [WARN] ping 不通 repo1.maven.org —— 若依赖未缓存过，mvn package 会失败；公司网络请先配好代理/镜像" ) else ( call :say "  [OK]   可以连到 Maven 中央仓库" )

:verdict
call :say ""
call :say "============================================================"
if "%CRIT%"=="1" (
    call :say " 结论：**不能开始构建** —— 上面标 [FAIL] 的关键项要先解决。"
    set "FINAL_RC=1"
) else (
    if "%MSVC_MISSING%"=="1" (
        call :say " 结论：JVM 侧就绪，但**缺 MSVC**，native-image 一定失败。"
        call :say "       可以先用 build-native.cmd --prepare-only 验证 JVM 侧链路。"
        set "FINAL_RC=20"
    ) else (
        call :say " 结论：**全部就绪**，可以直接跑 build-native.cmd"
        set "FINAL_RC=0"
    )
)
call :say " 退出码 : !FINAL_RC!   （0=就绪 / 20=缺 MSVC / 1=缺其它关键项）"
call :say " 下一步 : build-native.cmd                    完整构建，产物在 dist 目录"
call :say "          build-native.cmd --prepare-only   只验证 JVM 侧，无 MSVC 也能跑"
call :say "============================================================"

popd
endlocal & exit /b %FINAL_RC%

REM ============================================================================
REM  子程序（探测逻辑与 build-native.cmd 保持一致）
REM ============================================================================

:say
if "%~1"=="" ( echo. ) else ( echo %~1 )
goto :eof

:jdk_dir
if not defined BUILD_JAVA_HOME if exist "%~1\bin\javac.exe" set "BUILD_JAVA_HOME=%~1"
goto :eof

:detect_jdk
REM 故意不列 %ProgramFiles(x86)%：它的值含 ')'，放进 for 列表会拆坏解析
for %%r in ("%ProgramFiles%\BellSoft" "%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%LOCALAPPDATA%\Programs\BellSoft" "%USERPROFILE%\jdk" "C:\Java" "D:\Java" "D:\install") do (
    if exist "%%~r" for /d %%d in ("%%~r\*") do call :jdk_dir "%%~d"
)
goto :eof

:detect_nik
for %%r in ("%ProgramFiles%\BellSoft" "%LOCALAPPDATA%\Programs\BellSoft" "%USERPROFILE%\jdk" "C:\Java" "D:\Java" "D:\install") do (
    if exist "%%~r" for /d %%d in ("%%~r\*") do (
        if not defined GRAALVM_HOME if exist "%%~d\bin\native-image.cmd" set "GRAALVM_HOME=%%~d"
    )
)
goto :eof

