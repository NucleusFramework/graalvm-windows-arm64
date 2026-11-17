#!/bin/bash
# install.sh <graalvm-home>
#
# Installs this kit into a GraalVM CE windows-x64 home (of the kit's release) so that its native-image builds Windows
# on ARM64 images: the ARM64 static libraries where native-image looks for them (lib/svm/clibraries/windows-aarch64,
# lib/static/windows-aarch64, and the JDK's DLLs in lib/svm/jdk-libraries/windows-aarch64). Prints native-image's options for the target, for NATIVE_IMAGE_OPTIONS.
# native-image must then run in an ARM64 MSVC environment (vcvarsall arm64), on Windows on ARM64.
set -euo pipefail
graal=$(cd "$1" && pwd)
kit=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$graal/lib/svm/clibraries/windows-aarch64" "$graal/lib/static/windows-aarch64" "$graal/lib/svm/jdk-libraries/windows-aarch64" "$kit/capcache"
cp "$kit"/lib/svm/clibraries/windows-aarch64/* "$graal/lib/svm/clibraries/windows-aarch64/"
cp "$kit"/lib/static/windows-aarch64/* "$graal/lib/static/windows-aarch64/"
# The JDK's DLLs (AWT, fonts...) native-image copies next to Windows images
cp "$kit"/lib/svm/jdk-libraries/windows-aarch64/* "$graal/lib/svm/jdk-libraries/windows-aarch64/"

w() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
options="--target=windows-aarch64 -H:+UnlockExperimentalVMOptions -H:-CheckToolchain"
# The JDK's foreign ABI, computed in the builder (x64) and kept in the image: the target's
options+=" -J-Djdk.internal.foreign.CABI=WIN_AARCH_64"
# The C queries run on the build host (ARM64), their results kept in a cache, which native-image wants when the target
# isn't the host it runs on (x64, emulated)
options+=" -H:-UseCAPCache -H:+NewCAPCache -H:CAPCacheDir=$(w "$kit/capcache")"
for jar in "$kit"/patches/patch-*.jar; do
  module=$(basename "$jar" .jar); module=${module#patch-}
  options+=" -J--patch-module=$module=$(w "$jar")"
done
echo "$options"
