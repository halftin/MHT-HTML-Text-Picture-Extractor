@echo off
REM ============================================================================
REM  兼容入口：只跑 native-image（跳过 Maven 打包）
REM
REM  已改为**委托 build-native.cmd**（唯一实现）。原文件自己复制了一份
REM  native-image 参数列表，并写死 Y:\JAVA环境\MHT 与
REM  "Microsoft Visual Studio\18\Community" —— 那两处在本机都不可用。
REM
REM  语义等价于：build-native.cmd --skip-package
REM ============================================================================
call "%~dp0build-native.cmd" --skip-package %*
exit /b %errorlevel%
