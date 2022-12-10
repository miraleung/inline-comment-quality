#!/usr/bin/env bash

set -euo pipefail

SCRIPT_ABS_PATH=$(readlink -f "$0")
SCRIPT_ABS_DIR=$(dirname "$SCRIPT_ABS_PATH")

JOERN="$SCRIPT_ABS_DIR"/../joern

# Don't test java since we're using javasrc for all Java srcs.
# Originally, java came after javasrc below, was 7 for minMethodCount,
# and callsExternalMethod for expectedMethod.
frontends=(c javascript javasrc ghidra pythonsrc)
declare -A minMethodCount=(
  [c]=2
  [javascript]=3
  [javasrc]=7
  [ghidra]=100
  [pythonsrc]=2
)
declare -A expectedMethod=(
  [c]=print_number
  [javascript]=lookForProperty
  [javasrc]=callsExternalMethod
  [ghidra]=reallocarray
  [pythonsrc]=my_fun
)

for frontend in "${frontends[@]}"; do
  rm -rf workspace
  $JOERN --script tests/frontends-testscript.sc --params inputPath=tests/code/$frontend,minMethodCount=${minMethodCount[$frontend]},expectedMethod=${expectedMethod[$frontend]},frontend=$frontend

  JOERN_EXIT_CODE=$?
  if [ $JOERN_EXIT_CODE != 0 ]; then
    echo "Script [$script] failed to run successfully for frontend=$frontend"
    exit 1
  fi

  echo "Frontend [$frontend] tested successfully"
done


