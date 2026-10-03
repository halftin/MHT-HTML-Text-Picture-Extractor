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
REM    build-native.cmd --skip-card-smoke  跳过 native 卡片渲染冒烟（post-build 验证）
REM    build-native.cmd --no-editbin    保留控制台子系统（便于看启动期崩溃）
REM
REM  环境变量覆盖：BUILD_JAVA_HOME / GRAALVM_HOME / MAVEN_CMD / OUTPUT_DIR /
REM               NATIVE_XMX（默认 4G）/ VSWHERE / LOG_DIR（默认 <仓库>\logs）
REM  ⚠️ OUTPUT_DIR 默认为**项目树外**的 <仓库同级>\<仓库名>-dist（原因见步骤 1 的告警）。
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
set "SKIP_CARD_SMOKE="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--skip-package" set "SKIP_PACKAGE=1"
if /i "%~1"=="--no-editbin"   set "NO_EDITBIN=1"
if /i "%~1"=="--prepare-only" set "PREPARE_ONLY=1"
if /i "%~1"=="--skip-card-smoke" set "SKIP_CARD_SMOKE=1"
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

REM PowerShell 是硬依赖：时间戳 / 实时 tee / 构建报告 / mvnw.cmd 全都靠它。
REM 缺了它的现象很难看（退出码 9009、时间戳与日志名全空），所以这里提前挡住并说清原因。
where powershell.exe >nul 2>&1
if errorlevel 1 (
    call :say "[ERROR] 找不到 powershell.exe —— 本脚本的时间戳 / 实时日志 / 构建报告 / mvnw.cmd 都依赖它。"
    call :say "        请确认 %SystemRoot%\System32\WindowsPowerShell\v1.0 在 PATH 里。"
    set "RC=1" & set "FAILSTEP=1/6 环境探测" & goto :done
)

set "APP_VERSION="
for /f "tokens=*" %%i in ('powershell -NoProfile -Command "([xml](Get-Content pom.xml -Raw)).project.version"') do set "APP_VERSION=%%i"
if not defined APP_VERSION for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"^    <version>" pom.xml') do set "APP_VERSION=%%i"
if not defined APP_VERSION ( call :say "[ERROR] 无法从 pom.xml 读取版本号" & set "RC=1" & goto :done )

set "JFX_VERSION="
for /f "tokens=3 delims=<>" %%i in ('findstr /r /c:"<javafx.version>" pom.xml') do set "JFX_VERSION=%%i"
if not defined JFX_VERSION ( call :say "[ERROR] 无法从 pom.xml 读取 javafx.version" & set "RC=1" & goto :done )

REM 编译用 JDK 与 native-image JDK **必须独立解析**（本机 JAVA_HOME 实测是纯 JDK，无 native-image）
if not defined BUILD_JAVA_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BUILD_JAVA_HOME=%JAVA_HOME%"
REM 探测顺序：JAVA_HOME -> PATH 上的 javac -> 常见安装根目录通配（**不写死任何机器路径**，打包机零配置可用）
if not defined BUILD_JAVA_HOME for /f "delims=" %%i in ('where javac.exe 2^>nul') do if not defined BUILD_JAVA_HOME call :jdk_dir "%%~dpi.."
if not defined BUILD_JAVA_HOME call :detect_jdk
if not defined BUILD_JAVA_HOME ( call :say "[ERROR] 找不到 JDK —— 需要 JDK 25 且含 bin\javac.exe。" & call :say "        修复：设 BUILD_JAVA_HOME 指向 JDK 目录，或把该 JDK 的 bin 加进 PATH。" & call :say "        推荐：BellSoft Liberica NIK 25 Full 版 https://bell-sw.com/pages/downloads/native-image-kit/" & set "RC=1" & goto :done )

REM ---- native-image JDK 解析 + **配方判定**（2026-10-02）----
REM 两种配方，产物体积与 dist 组成都不同：
REM   dynamic（**默认**，约 70 MB）：native-image JDK **不含** JavaFX 静态库（Oracle GraalVM）→
REM           JavaFX 的类来自 Maven jar，原生库以 **7 个 DLL 随包**（dist 共约 20 个文件）。
REM   static （约 98 MB）        ：native-image JDK **自带** JavaFX 静态库（NIK Full）→
REM           JavaFX 原生库静态链接进 EXE，dist **绝不能**再放同名 DLL（会双份加载并段错误）。
REM 用 NATIVE_RECIPE=dynamic|static 覆盖；默认 dynamic，找不到才退让到 static 并告警。
if not defined NATIVE_RECIPE set "NATIVE_RECIPE=dynamic"
if defined GRAALVM_HOME if not exist "%GRAALVM_HOME%\bin\native-image.cmd" set "GRAALVM_HOME="
REM 1) 先按“想要的配方”扫 —— 两种 JDK 同时存在时才会挑对
if not defined GRAALVM_HOME (
    set "WANT_RECIPE=%NATIVE_RECIPE%"
    call :scan_native_image
)
REM 2) 扫不到想要的配方 → 退让：接受任意 native-image JDK（下面会告警）
if not defined GRAALVM_HOME (
    set "WANT_RECIPE="
    call :scan_native_image
)
REM 3) 仍然没有 → 退回 BUILD_JAVA_HOME / JAVA_HOME 自带的 native-image
if not defined GRAALVM_HOME if exist "%BUILD_JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%BUILD_JAVA_HOME%"
if not defined GRAALVM_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\native-image.cmd" set "GRAALVM_HOME=%JAVA_HOME%"
if not defined GRAALVM_HOME ( call :say "[ERROR] 找不到带 native-image 的 JDK —— Windows 上必须用 NIK 或 GraalVM 发行版。" & call :say "        修复：设 GRAALVM_HOME 指向该目录，其下应有 bin\native-image.cmd。" & call :say "        注意：编译 JDK 与 native-image JDK 是**分开解析**的，两者可以不同。" & set "RC=1" & goto :done )
set "NATIVE_IMAGE=%GRAALVM_HOME%\bin\native-image.cmd"
REM 由实际选中的 JDK 反推它支持哪种配方（判据：有没有 JavaFX 的静态库 prism_common.lib）
set "RECIPE=dynamic"
if exist "%GRAALVM_HOME%\lib\static\windows-amd64\prism_common.lib" set "RECIPE=static"
if /i not "%RECIPE%"=="%NATIVE_RECIPE%" call :say "      [WARN] 想要 %NATIVE_RECIPE% 配方，但选中的 native-image JDK 只支持 %RECIPE%：%GRAALVM_HOME%"

REM dir 的 /s 不支持中间目录通配，必须把 mvn.cmd 作为**文件名**交给 /s
if not defined MAVEN_CMD for /f "delims=" %%i in ('where mvn.cmd 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD if exist "mvnw.cmd" set "MAVEN_CMD=%CD%\mvnw.cmd"
if not defined MAVEN_CMD for /f "delims=" %%i in ('dir /b /s "%USERPROFILE%\.m2\wrapper\dists\mvn.cmd" 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%i"
if not defined MAVEN_CMD ( call :say "[ERROR] 找不到 mvn.cmd（PATH 与 Maven wrapper 都没有）。请设 MAVEN_CMD。" & set "RC=1" & goto :done )

REM 🚨 默认输出到**项目树外**。本机实测（2026-10-02）：任何位于项目树内的 native EXE
REM    会让 JavaFX 的 Font.loadFont(...) 返回 null，于是**全部 Ikonli 图标渲染成空心方框**；
REM    同一份 EXE 字节放到树外（D:\JAVA\newmht、C:\... 甚至 75 字符长路径）则完全正常。
REM    这本机现象与构建配方无关（NIK 静态 JavaFX 与动态 JavaFX 的构建都会中招），
REM    故默认不再用 %REPO%\dist；放树内仍允许，但会在步骤 1 明确告警。
REM %%~dpI 给出<盘符+上级目录>并自带结尾反斜杠；%%~fI 不会把 .. 规范化，会污染下面的前缀判断
for %%I in ("%REPO%") do set "REPO_PARENT=%%~dpI"
for %%I in ("%REPO%") do set "REPO_BASENAME=%%~nxI"
if not defined OUTPUT_DIR set "OUTPUT_DIR=%REPO_PARENT%%REPO_BASENAME%-dist"
for %%I in ("%OUTPUT_DIR%") do set "OUTPUT_ABS=%%~fI"
REM 用 PowerShell 做前缀判断：cmd 的字符串替换不支持把变量当搜索模式
powershell -NoProfile -Command "$o='%OUTPUT_ABS%'.TrimEnd('\'); $r='%REPO%'.TrimEnd('\'); if ($o -eq $r -or $o.ToLower().StartsWith($r.ToLower() + '\')) { exit 1 } else { exit 0 }" >nul 2>&1
if errorlevel 1 (
    call :say ""
    call :say "[WARN] 输出目录在**项目树内**：!OUTPUT_ABS!"
    call :say "       本机实测：项目树内的 native EXE 会让 JavaFX 的 Font.loadFont(...) 返回 null，"
    call :say "       表现为**全部 Ikonli 图标变成空心方框**（字体资源本身没问题，同一份 EXE 放到树外就正常）。"
    call :say "       建议：删掉 OUTPUT_DIR 环境变量用新的默认值，或 set OUTPUT_DIR=D:\MhtExtractor-dist"
    call :say ""
)
if not defined NATIVE_XMX set "NATIVE_XMX=4G"

call :say "      版本        : %APP_VERSION%   JavaFX %JFX_VERSION%"
call :say "      编译 JDK    : %BUILD_JAVA_HOME%"
call :say "      native-image: %NATIVE_IMAGE%"
call :say "      **配方**    : %RECIPE%  (dynamic≈70MB 随包 7 个 JavaFX DLL / static≈98MB JavaFX 编进 EXE)"
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
for /d %%d in ("%VSPATH%\VC\Tools\MSVC\*") do if not defined EDITBIN if exist "%%~d\bin\Hostx64\x64\editbin.exe" set "EDITBIN=%%~d\bin\Hostx64\x64\editbin.exe"
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

REM ==================== 步骤 4/6：抽取随包 DLL（按配方） ====================
REM   dynamic → 7 个 JavaFX DLL + 5 个 VC++ 运行库（JavaFX 走 DLL，必须随包）
REM   static  → **只** 5 个 VC++ 运行库（JavaFX 已在 EXE 里，再放同名 DLL 会双份加载并段错误）
call :begin "4/6" "抽取随包 DLL（%RECIPE%）"
if not exist "target\lib" ( call :say "[ERROR] 缺少 target\lib —— 请先跑一次完整打包（不要用 --skip-package）。" & call :end 1 & set "RC=1" & set "FAILSTEP=4/6 抽取 DLL" & goto :done )
set "JFX_JAR=javafx-graphics-%JFX_VERSION%-win.jar"
if not exist "target\lib\%JFX_JAR%" ( call :say "[ERROR] 找不到 target\lib\%JFX_JAR%" & call :end 1 & set "RC=1" & set "FAILSTEP=4/6 抽取 DLL" & goto :done )
REM 🚨 先清空：否则上一次用另一种配方构建留下的 DLL 会被一起复制进 dist
REM    （static 配方里混进 glass.dll 就是段错误的直接原因）
if exist "target\jfx-dlls" rmdir /s /q "target\jfx-dlls"
mkdir "target\jfx-dlls"
set "DLL_LIST=msvcp140.dll msvcp140_1.dll msvcp140_2.dll vcruntime140.dll vcruntime140_1.dll"
if /i "%RECIPE%"=="dynamic" set "DLL_LIST=glass.dll prism_common.dll prism_d3d.dll prism_sw.dll decora_sse.dll javafx_font.dll javafx_iio.dll %DLL_LIST%"
pushd "target\lib"
for %%f in (%DLL_LIST%) do (
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
if not exist "target\mhtExtractor-%APP_VERSION%.jar" ( call :say "[ERROR] 找不到 target\mhtExtractor-%APP_VERSION%.jar —— 先跑一次完整打包，别用 --skip-package。" & call :end 1 & set "RC=1" & set "FAILSTEP=5/6 native-image" & goto :done )
call :say "      正在运行: native-image -jar target\mhtExtractor-%APP_VERSION%.jar"
call :say "      （首次通常 1-3 分钟；输出会实时追加到上面的日志）"
call "%VCVARS%" >nul 2>&1
set "JAVA_HOME=%GRAALVM_HOME%"
set "PATH=%GRAALVM_HOME%\bin;%PATH%"
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM prism_common 只在 **static 配方**下需要显式交给链接器：
REM   static ：`_overrideNativeWindowHandle` 的实现住在 <NIK>\lib\static\windows-amd64\prism_common.lib
REM            （dumpbin /EXPORTS 实测），而 native-image 自己挑库时没挑它 → 不加必 LNK2001 + LNK1120。
REM   dynamic：该符号由 java.library.path 里的 prism_common.dll 提供（link.exe 直接吃 DLL 的导出表），
REM            既不需要、也不应该传这个选项。
set "PRISM_COMMON_OPT="
if /i not "%RECIPE%"=="static" goto :prism_done
set "PRISM_COMMON_LIB=%GRAALVM_HOME%\lib\static\windows-amd64\prism_common.lib"
if not exist "%PRISM_COMMON_LIB%" goto :prism_missing
set "PRISM_COMMON_OPT=-H:NativeLinkerOption=%PRISM_COMMON_LIB%"
REM 路径含空格时裸库名更安全：链接命令里本来就有该目录的 /LIBPATH
if not "%PRISM_COMMON_LIB%"=="%PRISM_COMMON_LIB: =%" set "PRISM_COMMON_OPT=-H:NativeLinkerOption=prism_common.lib"
goto :prism_done
:prism_missing
call :say "      [WARN] 静态配方却找不到 %PRISM_COMMON_LIB% —— 链接很可能因未解析符号失败"
:prism_done
call :say "      链接附加库  : %PRISM_COMMON_OPT%   (dynamic 配方此处为空是正常的)"
> "%TMPCMD%" echo @echo off
>>"%TMPCMD%" echo call "%NATIVE_IMAGE%" -jar "target\mhtExtractor-%APP_VERSION%.jar" --no-fallback -H:Path="%OUTPUT_DIR%" -H:Name=MhtExtractor -H:+JNI -H:+ReportExceptionStackTraces --enable-all-security-services --enable-http --enable-https --enable-url-protocols=file,http,https --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin -Djava.library.path="target/jfx-dlls" -J-Xmx%NATIVE_XMX% -Ob %PRISM_COMMON_OPT%
call :tee
if not "%RC%"=="0" ( call :say "[ERROR] native-image 构建失败，exit=%RC%" & call :end %RC% & set "FAILSTEP=5/6 native-image" & goto :done )
call :end 0

REM ==================== 步骤 6/6：组装绿色便携目录 ====================
call :begin "6/6" "组装 dist"
copy /Y "target\jfx-dlls\*.dll" "%OUTPUT_DIR%" >nul
call :say "      已复制 !DLL_COUNT! 个 DLL 到 %OUTPUT_DIR%"
if /i "%RECIPE%"=="dynamic" call :say "      （含 7 个 JavaFX DLL —— dynamic 配方必须随包；static 配方则绝不能带）"

REM 🚨 dynamic 配方在**非 ASCII 路径**下会启动失败（GraalVM/SubstrateVM 的 System.loadLibrary
REM    处理不了 java.library.path 里的中文目录，而该列表首项就是 EXE 所在目录）——现象是
REM    "Graphics Device initialization failed for : d3d, sw" 后立刻退出。实测：路径含**空格**没事，
REM    只有非 ASCII 会炸；static 配方（JavaFX 已静态链接）完全不受影响。
REM    补救：随包一个启动器，它把 DLL 复制到纯 ASCII 目录再用 -Djava.library.path 指过去。
REM    ⚠️ 中文文件名必须由 PowerShell 创建：cmd 会把 UTF-8 字节当 ANSI 传给 Win32，
REM       在 .cmd 里直接 copy 成中文名会得到乱码文件名（实测踩过）。
if /i not "%RECIPE%"=="dynamic" goto :skip_launcher
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\tools\launcher\make-launcher.ps1" -Dest "%OUTPUT_DIR%" >nul 2>&1
if errorlevel 1 (
    call :say "      [WARN] 启动器生成失败 —— 此时 dynamic 产物只能放在**纯 ASCII 路径**下运行"
) else (
    call :say "      已生成 启动.cmd / Launch.cmd（非 ASCII 路径下的补救；static 配方不需要）"
)
:skip_launcher
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

REM ---------- post-build smoke: can the native image actually PAINT a reminder card? ----------
REM   Background (2026-10-03 incident): when the native image lacks the JavaFX effect-peer
REM   reflection registrations (PrMergePeer / PPSLinearConvolveShadowPeer), EVERY centered
REM   reminder card fails to paint while its ModalPane keeps blocking input -> "no card +
REM   black window + cannot interact + cannot exit". The JVM is unaffected, so unit tests,
REM   GUI automation and the UI probes cannot catch it. This step runs the real assembly in
REM   the native image and asserts a confirm card is actually laid out.
REM   Skip with --skip-card-smoke or NATIVE_CARD_SMOKE=0.
if /i "%SKIP_CARD_SMOKE%"=="1" goto :card_smoke_skipped
if /i "%NATIVE_CARD_SMOKE%"=="0" goto :card_smoke_skipped
call :begin "SMOKE" "native card render smoke"
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\tools\native\native-card-smoke.ps1" -Repo "%REPO%" -OutDir "%REPO%\.claude-work\native-card-smoke" -JavaHome "%BUILD_JAVA_HOME%" -NativeImage "%NATIVE_IMAGE%" -PrismCommonLib "%PRISM_COMMON_LIB%" >> "%LOG%" 2>&1
if errorlevel 1 (
    call :end 1
    call :say "      [ERROR] native card smoke FAILED - the native image cannot paint a reminder card."
    call :say "              Usual cause: missing JavaFX effect-peer reflection metadata."
    call :say "              See tools/native/native-card-smoke.ps1 + tools/probes/AppCardProbe.java."
    set "RC=1"
    set "FAILSTEP=SMOKE native card render smoke"
    set "STATUS=FAILED"
    goto :done
)
call :end 0
call :say "      OK: native image paints a centered confirm card"
goto :card_smoke_done
:card_smoke_skipped
call :say "      [SKIP] native card smoke skipped"
:card_smoke_done
:done
REM ---------- 失败现场（若有） ----------
if not "%STATUS%"=="FAILED" goto :report
call :say ""
call :say "=== FAILURE ==="
call :say " 失败步 : %FAILSTEP%"
call :say " 退出码 : %RC%"
call :say " 日志尾部（末 40 行）："
REM 🚨 必须带 -Encoding UTF8：日志是 UTF-8 写的，PS 5.1 的 Get-Content 默认按 ANSI(GBK) 读，
REM    会把"环境探测"这类中文读成 鐜鎺㈡祴 —— 而这段正是排障时要看的东西
powershell -NoProfile -Command "if (Test-Path -LiteralPath '%LOG%') { Get-Content -LiteralPath '%LOG%' -Encoding UTF8 -Tail 40 | ForEach-Object { '   ' + $_ } }"
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
if "%STATUS%"=="OK" if /i "%RECIPE%"=="dynamic" call :say " 启动方式    : **双击 启动.cmd**（dynamic 配方：直接双击 exe 在中文/非 ASCII 路径下会失败）"
if "%STATUS%"=="OK" if /i "%RECIPE%"=="static"  call :say " 启动方式    : 双击 MhtExtractor.exe（static 配方对路径无要求）"
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
set "SAYMSG=%~1"
if not defined SAYMSG (
    echo.
    >>"%LOG%" echo.
) else (
    echo !SAYMSG!
    >>"%LOG%" echo !SAYMSG!
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

:jdk_dir
REM 把一个"可能的 JDK 根目录"登记为编译 JDK（先到先得）
if not defined BUILD_JAVA_HOME if exist "%~1\bin\javac.exe" set "BUILD_JAVA_HOME=%~1"
goto :eof

:detect_jdk
REM 常见安装根目录通配探测；开发机与打包机共用同一套逻辑，**不含任何机器专属路径**
REM 注意：这里**故意不列 %ProgramFiles(x86)%** —— 它的值含 ')'，放进 for 的列表会直接拆坏解析
for %%r in ("%ProgramFiles%\BellSoft" "%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%LOCALAPPDATA%\Programs\BellSoft" "%USERPROFILE%\jdk" "C:\Java" "D:\Java" "D:\install") do (
    if exist "%%~r" for /d %%d in ("%%~r\*") do call :jdk_dir "%%~d"
)
goto :eof

:scan_native_image
REM 在常见安装根目录下找 bin\native-image.cmd；**会再深一层**，因为 Oracle GraalVM 的 zip
REM 解压出来常是 <根>\graalvm-jdk-25_windows-x64_bin\graalvm-jdk-25.0.2+10.1\ 这种两层结构。
REM 设了 WANT_RECIPE 时只接受配方匹配的候选（这样 NIK 与 Oracle GraalVM 同时存在也能挑对）。
REM 🚨 列表里**故意不写 %ProgramFiles(x86)%** —— 它的值含 ')'，放进 for 列表会拆坏解析。
for %%r in ("%ProgramFiles%\BellSoft" "%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%LOCALAPPDATA%\Programs\BellSoft" "%USERPROFILE%\jdk" "C:\Java" "D:\Java" "D:\install" "C:\graalvm" "D:\graalvm" "%USERPROFILE%\graalvm") do (
    if exist "%%~r" for /d %%d in ("%%~r\*") do (
        call :accept_ni "%%~d"
        for /d %%e in ("%%~d\*") do call :accept_ni "%%~e"
    )
)
goto :eof

:accept_ni
REM %1 = 候选 JDK 目录。先到先得；设了 WANT_RECIPE 时配方不符就跳过。
if defined GRAALVM_HOME goto :eof
if not exist "%~1\bin\native-image.cmd" goto :eof
if not defined WANT_RECIPE goto :accept_ni_take
set "CAND_RECIPE=dynamic"
if exist "%~1\lib\static\windows-amd64\prism_common.lib" set "CAND_RECIPE=static"
if /i not "!CAND_RECIPE!"=="%WANT_RECIPE%" goto :eof
:accept_ni_take
set "GRAALVM_HOME=%~1"
goto :eof

:tee
REM 执行 %TMPCMD% 并把输出实时同时送到控制台与日志；退出码放入 RC
REM 🚨 本标签与下面两行**必须紧邻**：中间插任何东西（哪怕一行 REM 或另一个 :label）都会让
REM    `call :tee` 落空滑进下一个子程序 —— 表现是"该步 exit=0 但什么都没执行"（本项目真踩过）
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\native-tee.ps1" -CmdFile "%TMPCMD%" -Log "%LOG%"
set "RC=%ERRORLEVEL%"
goto :eof
