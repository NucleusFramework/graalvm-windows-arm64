#!/bin/bash
# build.sh <graalvm-home> <out-dir>
#
# Builds the native-image patches for Windows on ARM64 against a GraalVM CE release: fetches the files the patches
# touch from oracle/graal at the release's commit (its release file's SOURCE), applies patches/*.patch, compiles them in
# their modules and writes <out-dir>/patch-<module>.jar, for native-image's -J--patch-module=<module>=<jar>.
set -euo pipefail
graal=$(cd "$1" && pwd); out=$2
patches=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"; out=$(cd "$out" && pwd)
work=$(mktemp -d)

# The patched files, as of the release, patched
"$patches/../scripts/graal-sources.sh" "$graal" "$work" $(sed -n 's|^+++ b/||p' "$patches"/*.patch | grep '\.java$' | sort -u)

# Windows' java wants Windows paths, ';' separated
case $(uname -s) in
  MINGW*|MSYS*|CYGWIN*) sep=';'; win() { cygpath -m "$1"; }; exe=.exe ;;
  *) sep=':'; win() { echo "$1"; }; exe= ;;
esac
modulePath=""
for jar in "$graal"/lib/svm/builder/*.jar; do modulePath+="${modulePath:+$sep}$(win "$jar")"; done

# The builder's exports (as native-image launches it), which the patched classes need as much as the originals
probe="$work/probe"; mkdir -p "$probe"
printf 'public class P { public static void main(String[] a) { } }\n' > "$probe/P.java"
"$graal/bin/javac$exe" -d "$probe" "$(win "$probe/P.java")"
launcher="$graal/bin/native-image"; [ -n "$exe" ] && launcher+=.cmd
exports=$(cd "$probe" && "$launcher" --dry-run --verbose -cp . P 2>&1 | tr ' ' '\n' | tr -d '\r' | grep '^--add-exports=' | sort -u |
  sed 's/$/,org.graalvm.nativeimage.foreign/')
# The foreign module gets java.base's foreign internals at run time (not on the command line)
exports+=" $("$graal/bin/jimage$exe" list "$(win "$graal/lib/modules")" | tr -d '\r' | sed -n '/^Module: java.base$/,/^Module:/p' |
  sed -n 's|^ *\(jdk/internal/foreign/.*\)/[^/]*\.class$|\1|p' | sort -u | tr / . |
  sed 's|.*|--add-exports=java.base/&=org.graalvm.nativeimage.foreign|' | tr '\n' ' ')"

# Each patched project's sources, compiled in its module
compile() { # <module> <project>...
  local m=$1; shift
  local roots="" sources=""
  for project in "$@"; do
    [ -d "$work/substratevm/src/$project/src" ] || continue
    roots+="${roots:+$sep}$(win "$work/substratevm/src/$project/src")"
    for f in $(find "$work/substratevm/src/$project/src" -name '*.java'); do sources+=" $(win "$f")"; done
  done
  [ -n "$sources" ] || return 0
  echo "== $m"
  # shellcheck disable=SC2086
  "$graal/bin/javac$exe" -nowarn $exports --module-path "$modulePath" --add-modules org.graalvm.nativeimage.builder,org.graalvm.nativeimage.foreign \
    --patch-module "$m=$roots" -d "$(win "$work/classes/$m")" $sources
  "$graal/bin/jar$exe" --create --file "$(win "$out/patch-$m.jar")" -C "$(win "$work/classes/$m")" .
  echo "$out/patch-$m.jar"
}
compile org.graalvm.nativeimage.objectfile com.oracle.objectfile
compile org.graalvm.nativeimage.builder com.oracle.svm.core com.oracle.svm.core.windows com.oracle.svm.core.graal.aarch64 com.oracle.svm.hosted
compile org.graalvm.nativeimage.foreign com.oracle.svm.core.foreign com.oracle.svm.hosted.foreign
