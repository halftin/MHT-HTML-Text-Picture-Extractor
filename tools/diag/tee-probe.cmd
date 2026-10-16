@echo off
REM 最小复现：完全照抄 build-native.cmd 的 :tee 调用方式，看它到底跑了什么
setlocal enabledelayedexpansion
for %%I in ("%~dp0..\..") do set "REPO=%%~fI"
set "TMPCMD=%TEMP%\tee-probe-step.cmd"
set "LOG=%TEMP%\tee-probe.log"
echo [probe] TMPCMD=!TMPCMD!
echo [probe] LOG=!LOG!
echo [probe] powershell 位置：
where powershell
> "%TMPCMD%" echo @echo off
>>"%TMPCMD%" echo echo HELLO-FROM-TEE
echo [probe] 临时文件内容：
type "%TMPCMD%"
echo [probe] --- 调用 tee ---
call :tee
echo [probe] --- tee 返回 RC=!RC! ---
echo [probe] 日志内容：
if exist "%LOG%" type "%LOG%"
goto :eof

:tee
powershell -NoProfile -ExecutionPolicy Bypass -File "%REPO%\native-tee.ps1" -CmdFile "%TMPCMD%" -Log "%LOG%"
set "RC=%ERRORLEVEL%"
goto :eof
