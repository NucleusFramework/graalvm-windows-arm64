#!/bin/bash
# build.sh <graalvm-home> <out-dir>
#
# Builds Substrate VM's C libraries for Windows on ARM64 (GraalVM CE ships them for windows-amd64 only): libchelper.lib
# and jvm.lib, from oracle/graal at the release's commit, with patches/*.patch applied to them. Run in an ARM64 MSVC
# environment (vcvarsall arm64).
set -euo pipefail
export MSYS2_ARG_CONV_EXCL='*'
graal=$(cd "$1" && pwd); out=$2
recipe=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$out"; out=$(cd "$out" && pwd)
work=$(mktemp -d)
commit=$(sed -n 's/^SOURCE=.*substratevm:\([0-9a-f]*\).*/\1/p' "$graal/release")
echo "oracle/graal $commit"

# The projects' sources (the GitHub tree of the release's commit)
projects="com.oracle.svm.native.libchelper com.oracle.svm.native.jvm.windows"
tree=$(curl -sSfL "https://api.github.com/repos/oracle/graal/git/trees/$commit?recursive=1" ${GH_TOKEN:+-H "Authorization: Bearer $GH_TOKEN"})
for project in $projects; do
  for f in $(echo "$tree" | python -c "import json,sys; [print(e['path']) for e in json.load(sys.stdin)['tree'] if e['type'] == 'blob' and e['path'].startswith('substratevm/src/$project/')]"); do
    mkdir -p "$work/$(dirname "$f")"
    curl -sSfL -o "$work/$f" "https://raw.githubusercontent.com/oracle/graal/$commit/$f"
  done
done
# The patches of these projects
(cd "$work" && git init -q && for p in "$recipe"/patches/*.patch; do
  grep -q "^+++ b/substratevm/src/com.oracle.svm.native" "$p" && git apply -v "$p"; done; true)

src=$work/substratevm/src
w() { cygpath -w "$1"; }
compile() { # <lib> <cflags> <dir>...
  local lib=$1 flags=$2; shift 2
  local objs="$work/obj/$lib"; mkdir -p "$objs"
  for dir in "$@"; do
    for c in "$dir"/*.c; do
      # shellcheck disable=SC2086
      cl -nologo -c $flags -I"$(w "$src/com.oracle.svm.native.libchelper/include")" -I"$(w "$graal/include")" -I"$(w "$graal/include/win32")" \
        -Fo"$(w "$objs/$(basename "$c" .c).obj")" "$(w "$c")"
    done
  done
  lib -nologo -machine:arm64 -out:"$(w "$out/$lib.lib")" "$(w "$objs")"/*.obj
  echo "$out/$lib.lib"; lib -nologo -list "$(w "$out/$lib.lib")"
}
compile libchelper "-MD -Zi -O2 -D_LITTLE_ENDIAN" "$src/com.oracle.svm.native.libchelper/src"
compile jvm "-MD -Zi -O2 -DJNIEXPORT=" "$src/com.oracle.svm.native.jvm.windows/src"
