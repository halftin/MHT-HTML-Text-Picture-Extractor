#!/bin/bash
# Native Image build script for MHT Extractor
set -e

PROJECT_DIR="Y:/JAVA环境/MHT"
GRAALVM_HOME="Y:/JAVA环境/graalvm-jdk-25_windows-x64_bin/graalvm-jdk-25.0.2+10.1"

# Visual Studio paths
VSINSTALLDIR="C:/Program Files/Microsoft Visual Studio/18/Community"
VCINSTALLDIR="C:/Program Files/Microsoft Visual Studio/18/Community/VC"
MSVC_TOOLS="C:/Program Files/Microsoft Visual Studio/18/Community/VC/Tools/MSVC/14.51.36231"

# Windows SDK paths
WINSDK_DIR="C:/Program Files (x86)/Windows Kits/10"
WINSDK_VER="10.0.28000.0"
WINSDK_INCLUDE="C:/Program Files (x86)/Windows Kits/10/Include/${WINSDK_VER}"
WINSDK_LIB="C:/Program Files (x86)/Windows Kits/10/Lib/${WINSDK_VER}"

echo "=== Setting up MSVC environment ==="

# MSVC toolchain in PATH
export PATH="$MSVC_TOOLS/bin/Hostx64/x64:$WINSDK_DIR/bin/${WINSDK_VER}/x64:$GRAALVM_HOME/bin:$PATH"

# INCLUDE for headers
export INCLUDE="$MSVC_TOOLS/include;$MSVC_TOOLS/atlmfc/include;$WINSDK_INCLUDE/ucrt;$WINSDK_INCLUDE/um;$WINSDK_INCLUDE/shared"

# LIB for libraries
export LIB="$MSVC_TOOLS/lib/x64;$MSVC_TOOLS/atlmfc/lib/x64;$WINSDK_LIB/ucrt/x64;$WINSDK_LIB/um/x64"

# VS install variables (native-image looks for these)
export VSINSTALLDIR="$VSINSTALLDIR"
export VCINSTALLDIR="$VCINSTALLDIR"
export WindowsSdkDir="$WINSDK_DIR"
export WindowsSDKVersion="$WINSDK_VER"
export WindowsSdkBinPath="$WINSDK_DIR/bin/${WINSDK_VER}/x64"
export WindowsSdkVerBinPath="$WINSDK_DIR/bin/${WINSDK_VER}/x64"
export VS150COMNTOOLS="$VSINSTALLDIR/Common7/Tools"
export VS170COMNTOOLS="$VSINSTALLDIR/Common7/Tools"
export VCToolsInstallDir="$MSVC_TOOLS"
export VCToolsVersion="14.51.36231"
export VSCMD_ARG_TGT_ARCH="x64"
export Platform="x64"
export VisualStudioVersion="18.0"

echo "VSINSTALLDIR=$VSINSTALLDIR"
echo "VCINSTALLDIR=$VCINSTALLDIR"
echo "WindowsSdkDir=$WINSDK_DIR"

# Verify the tools
echo "=== Verifying tools ==="
"$GRAALVM_HOME/bin/native-image.cmd" --version 2>&1 || true

echo "Checking cl.exe..."
ls "$MSVC_TOOLS/bin/Hostx64/x64/cl.exe" && echo "OK: cl.exe found"
echo "Checking link.exe..."
ls "$MSVC_TOOLS/bin/Hostx64/x64/link.exe" && echo "OK: link.exe found"

echo "=== Starting Native Image compilation ==="
cd "$PROJECT_DIR"

"$GRAALVM_HOME/bin/native-image.cmd" \
  -jar target/mhtExtractor-2.2-SNAPSHOT.jar \
  --no-fallback \
  -H:Path=target/native-image \
  -H:Name=MhtExtractor \
  -H:+JNI \
  -H:+ReportExceptionStackTraces \
  --enable-all-security-services \
  --enable-http \
  --enable-https \
  --enable-url-protocols=file,http,https \
  --initialize-at-run-time=com.sun.glass,com.sun.prism,com.sun.scenario,com.sun.javafx.font,com.sun.javafx.iio,com.sun.media,com.sun.webkit,com.sun.javafx.tk.quantum,com.sun.marlin \
  -Djava.library.path="target/jfx-dlls" \
  -J-Xmx8G \
  -Ob

echo "=== Native Image build complete ==="
