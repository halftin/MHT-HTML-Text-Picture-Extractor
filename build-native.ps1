# Native Image build - thin delegating wrapper.
#
# The real implementation is build-native.cmd (single source of truth).
# This file used to hardcode Y:\JAVA环境\MHT and
# "Microsoft Visual Studio\18\Community" - both unusable on this machine.
#
# Kept ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 files without a
# BOM as ANSI, which garbles non-ASCII comments.
#
# All arguments are forwarded verbatim, e.g.
#   .\build-native.ps1 --prepare-only

$ErrorActionPreference = 'Stop'
$cmd = Join-Path $PSScriptRoot 'build-native.cmd'
& $cmd @args
exit $LASTEXITCODE
