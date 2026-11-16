#!/bin/bash
# graal-sources.sh <graalvm-home> <dir> <path>...
#
# Checks out <path>s of oracle/graal at the commit a GraalVM CE release was built from (its release file's SOURCE) into
# <dir>, then applies the patches/*.patch that touch them.
set -euo pipefail
graal=$1; dir=$2; shift 2
recipe=$(cd "$(dirname "$0")/.." && pwd)
commit=$(sed -n 's/^SOURCE=.*substratevm:\([0-9a-f]*\).*/\1/p' "$graal/release")
[ -n "$commit" ] || { echo "No substratevm commit in $graal/release" >&2; exit 1; }
echo "oracle/graal $commit: $*"
mkdir -p "$dir"
# Git for Windows wants Windows paths (MSYS2_ARG_CONV_EXCL may disable their conversion)
command -v cygpath >/dev/null && dir=$(cygpath -m "$dir")
git -C "$dir" init -q
git -C "$dir" remote add origin https://github.com/oracle/graal.git
git -C "$dir" sparse-checkout set --no-cone "$@"
git -C "$dir" fetch -q --depth 1 --filter=blob:none origin "$commit"
git -C "$dir" checkout -q FETCH_HEAD
for p in "$recipe"/patches/*.patch; do
  touched=$(sed -n 's|^+++ b/||p' "$p")
  for t in $touched; do
    for path in "$@"; do
      case $t in "${path%/}"|"${path%/}"/*) git -C "$dir" apply -v "$p"; continue 3 ;; esac
    done
  done
done
