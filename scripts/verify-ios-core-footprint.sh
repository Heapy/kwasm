#!/usr/bin/env bash
#
# Measure NFR-2 against two stripped, dead-code-eliminated final iOS Mach-O
# executables. Both probes include Kotlin and kotlinx.coroutines; only the
# core probe internally decodes, validates, instantiates, and executes Wasm.

set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly OUTPUT_ROOT="$REPOSITORY_ROOT/build/ios-footprint"
readonly BASELINE_FRAMEWORK_ROOT="$REPOSITORY_ROOT/benchmarks/footprint/baseline/build/bin/iosArm64/releaseFramework"
readonly CORE_FRAMEWORK_ROOT="$REPOSITORY_ROOT/benchmarks/footprint/core/build/bin/iosArm64/releaseFramework"
readonly BASELINE_FRAMEWORK="$BASELINE_FRAMEWORK_ROOT/KwasmFootprintBaseline.framework/KwasmFootprintBaseline"
readonly CORE_FRAMEWORK="$CORE_FRAMEWORK_ROOT/KwasmFootprintCore.framework/KwasmFootprintCore"
readonly BASELINE_EXECUTABLE="$OUTPUT_ROOT/baseline"
readonly CORE_EXECUTABLE="$OUTPUT_ROOT/core"
readonly REPORT="$OUTPUT_ROOT/report.json"
readonly LIMIT_BYTES="${KWASM_IOS_CORE_FOOTPRINT_LIMIT_BYTES:-1500000}"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "The iOS footprint gate requires a macOS host with Xcode." >&2
    exit 1
fi
if ! [[ "$LIMIT_BYTES" =~ ^[1-9][0-9]*$ ]]; then
    echo "KWASM_IOS_CORE_FOOTPRINT_LIMIT_BYTES must be a positive integer." >&2
    exit 1
fi
command -v xcrun >/dev/null

mkdir -p "$OUTPUT_ROOT"

"$REPOSITORY_ROOT/gradlew" \
    --project-dir "$REPOSITORY_ROOT" \
    --no-daemon \
    --no-build-cache \
    :footprint-baseline:clean \
    :footprint-core:clean \
    :footprint-baseline:linkReleaseFrameworkIosArm64 \
    :footprint-core:linkReleaseFrameworkIosArm64

if [[ ! -s "$BASELINE_FRAMEWORK" || ! -s "$CORE_FRAMEWORK" ]]; then
    echo "Kotlin/Native did not produce both release footprint frameworks." >&2
    exit 1
fi

readonly SDK_ROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
readonly COMMON_CLANG_ARGUMENTS=(
    -fobjc-arc
    -fblocks
    -Oz
    -arch arm64
    -miphoneos-version-min=15.0
    -isysroot "$SDK_ROOT"
    -framework Foundation
    -Wl,-dead_strip
    -Wl,-fatal_warnings
)

xcrun --sdk iphoneos clang \
    "${COMMON_CLANG_ARGUMENTS[@]}" \
    -F "$BASELINE_FRAMEWORK_ROOT" \
    "$REPOSITORY_ROOT/benchmarks/footprint/ios/baseline-main.m" \
    "$BASELINE_FRAMEWORK" \
    -o "$BASELINE_EXECUTABLE"

xcrun --sdk iphoneos clang \
    "${COMMON_CLANG_ARGUMENTS[@]}" \
    -F "$CORE_FRAMEWORK_ROOT" \
    "$REPOSITORY_ROOT/benchmarks/footprint/ios/core-main.m" \
    "$CORE_FRAMEWORK" \
    -o "$CORE_EXECUTABLE"

xcrun strip -S -x "$BASELINE_EXECUTABLE" "$CORE_EXECUTABLE"

for executable in "$BASELINE_EXECUTABLE" "$CORE_EXECUTABLE"; do
    if [[ "$(xcrun lipo -archs "$executable")" != "arm64" ]]; then
        echo "Footprint output is not an arm64-only final executable: $executable" >&2
        exit 1
    fi
done

readonly BASELINE_BYTES="$(wc -c < "$BASELINE_EXECUTABLE" | tr -d '[:space:]')"
readonly CORE_BYTES="$(wc -c < "$CORE_EXECUTABLE" | tr -d '[:space:]')"
readonly INCREMENTAL_BYTES="$((CORE_BYTES - BASELINE_BYTES))"

if (( INCREMENTAL_BYTES <= 0 )); then
    echo "Invalid footprint result: core=$CORE_BYTES baseline=$BASELINE_BYTES" >&2
    exit 1
fi

readonly STATUS="$(
    if (( INCREMENTAL_BYTES <= LIMIT_BYTES )); then
        echo "pass"
    else
        echo "fail"
    fi
)"

printf '%s\n' \
    '{' \
    '  "schemaVersion": 1,' \
    '  "requirement": "NFR-2",' \
    '  "target": "iosArm64",' \
    '  "measurement": "paired-stripped-final-mach-o",' \
    '  "baseline": "Kotlin plus kotlinx.coroutines",' \
    '  "coreProbe": "decode, validate, instantiate, and execute i32.const 42",' \
    '  "kotlinNativeBinaryOptions": ["smallBinary=true", "latin1Strings=true"],' \
    "  \"limitBytes\": $LIMIT_BYTES," \
    "  \"baselineBytes\": $BASELINE_BYTES," \
    "  \"coreBytes\": $CORE_BYTES," \
    "  \"incrementalBytes\": $INCREMENTAL_BYTES," \
    "  \"status\": \"$STATUS\"" \
    '}' \
    > "$REPORT"

cat "$REPORT"

if [[ "$STATUS" != "pass" ]]; then
    echo "NFR-2 failed: :core adds $INCREMENTAL_BYTES bytes; limit is $LIMIT_BYTES." >&2
    exit 1
fi

echo "NFR-2 passed: :core adds $INCREMENTAL_BYTES bytes (limit $LIMIT_BYTES)."
