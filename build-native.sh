#!/bin/bash
# ============================================================================
#  Native Image 构建 —— Windows 上的 Git Bash / MSYS2 入口
#
#  已改为**委托 build-native.cmd**（唯一实现）。
#  原文件自己手工拼 MSVC 的 INCLUDE / LIB / VSINSTALLDIR，并写死了
#  "Visual Studio\18\Community"、MSVC 工具链 14.51.36231、Windows SDK 10.0.28000.0
#  —— 三个版本号在本机全都不存在，属于必然失败的写法。
#
#  用法（与 cmd 入口完全一致）：
#    ./build-native.sh                 完整构建
#    ./build-native.sh --prepare-only  只做 JVM 侧准备（不调用 native-image）
# ============================================================================
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if command -v cygpath >/dev/null 2>&1; then
    CMD="$(cygpath -w "$HERE/build-native.cmd")"
else
    CMD="$HERE/build-native.cmd"
fi

exec cmd.exe /c "$CMD" "$@"
