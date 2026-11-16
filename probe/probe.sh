#!/bin/bash
# probe.sh <graalvm-home> <out-dir> [platform class]: builds probe/Hello.java into a relocatable object for the platform
# (default: the host's), logging native-image's output to <out-dir>/native-image.log
set -uo pipefail
graal=$(cd "$1" && pwd); out=$2; platform=${3:-}
probe=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"; out=$(cd "$out" && pwd)
"$graal/bin/javac" -d "$out/classes" "$probe/Hello.java" || exit 1
extra=()
if [ -n "$platform" ]; then
  extra=(-Dsvm.platform="$platform" -H:-CheckToolchain -H:-UseCAPCache -H:+NewCAPCache -H:CAPCacheDir="$out/capcache")
fi
"$graal/bin/native-image.cmd" -cp "$out/classes" ${extra[@]+"${extra[@]}"} \
  -H:+UnlockExperimentalVMOptions -H:+SharedLibrary -H:+ExitAfterRelocatableImageWrite -H:TempDirectory="$out/tmp" \
  -H:+ReportExceptionStackTraces -o "$out/hello" 2>&1 | tee "$out/native-image.log"
status=${PIPESTATUS[0]}
find "$out/tmp" -name '*.obj' -o -name '*.o' -o -name '*.h' 2>/dev/null
exit "$status"
