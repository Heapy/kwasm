#!/usr/bin/env bash

set -euo pipefail

# Kotlin's official release history lists 2.4.0 as the current line and
# 2.3.21 as the previous stable bug-fix release:
# https://kotlinlang.org/docs/releases.html#release-history
readonly CURRENT_KOTLIN_VERSION="2.4.0"
readonly PREVIOUS_KOTLIN_VERSION="2.3.21"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kwasm-kotlin-wasm-compat.XXXXXX")"
readonly ISOLATED_CHECKOUT="$WORK_ROOT/kotlin-$PREVIOUS_KOTLIN_VERSION"
readonly PREVIOUS_PROJECT_CACHE="$WORK_ROOT/project-cache-$PREVIOUS_KOTLIN_VERSION"

cleanup() {
    if [[ "${KWASM_COMPAT_KEEP_WORK:-0}" == "1" ]]; then
        echo "Preserving isolated compiler checkout: $WORK_ROOT"
    else
        rm -rf -- "$WORK_ROOT"
    fi
}
trap cleanup EXIT

mkdir -p "$ISOLATED_CHECKOUT"

# Copy source inputs, never build products or checkout metadata. The version
# catalog is changed only inside this disposable tree.
(
    cd "$REPOSITORY_ROOT"
    tar \
        --exclude='./.git' \
        --exclude='./.gradle' \
        --exclude='./.kotlin' \
        --exclude='./build' \
        --exclude='*/build' \
        -cf - .
) | (
    cd "$ISOLATED_CHECKOUT"
    tar -xf -
)

readonly ISOLATED_VERSION_CATALOG="$ISOLATED_CHECKOUT/gradle/libs.versions.toml"
readonly CURRENT_VERSION_LINE="kotlin = \"$CURRENT_KOTLIN_VERSION\""
readonly PREVIOUS_VERSION_LINE="kotlin = \"$PREVIOUS_KOTLIN_VERSION\""

if [[ "$(grep -Fxc "$CURRENT_VERSION_LINE" "$ISOLATED_VERSION_CATALOG")" != "1" ]]; then
    echo "Expected exactly one '$CURRENT_VERSION_LINE' catalog entry" >&2
    exit 1
fi

sed \
    "s/^${CURRENT_VERSION_LINE}\$/${PREVIOUS_VERSION_LINE}/" \
    "$ISOLATED_VERSION_CATALOG" \
    > "$ISOLATED_VERSION_CATALOG.tmp"
mv "$ISOLATED_VERSION_CATALOG.tmp" "$ISOLATED_VERSION_CATALOG"

if [[ "$(grep -Fxc "$PREVIOUS_VERSION_LINE" "$ISOLATED_VERSION_CATALOG")" != "1" ]]; then
    echo "Failed to pin isolated Kotlin compiler $PREVIOUS_KOTLIN_VERSION" >&2
    exit 1
fi
if grep -Fqx "$CURRENT_VERSION_LINE" "$ISOLATED_VERSION_CATALOG"; then
    echo "Current Kotlin compiler leaked into the isolated version catalog" >&2
    exit 1
fi

echo "Building the wasmWasi fixture with isolated Kotlin $PREVIOUS_KOTLIN_VERSION"
"$ISOLATED_CHECKOUT/gradlew" \
    --project-dir "$ISOLATED_CHECKOUT" \
    --project-cache-dir "$PREVIOUS_PROJECT_CACHE" \
    --no-daemon \
    --stacktrace \
    :bindgen-api:compileTestDevelopmentExecutableKotlinWasmWasi

readonly PREVIOUS_BINARY="$ISOLATED_CHECKOUT/wasm-bindgen-api/build/compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/kwasm-bindgen-api-test.wasm"
if [[ ! -s "$PREVIOUS_BINARY" ]]; then
    echo "Kotlin $PREVIOUS_KOTLIN_VERSION did not produce the expected wasmWasi fixture" >&2
    exit 1
fi

echo "Executing Kotlin $CURRENT_KOTLIN_VERSION and $PREVIOUS_KOTLIN_VERSION outputs in the current runtime"
(
    cd "$REPOSITORY_ROOT"
    ./gradlew \
        --no-daemon \
        --stacktrace \
        --rerun-tasks \
        :bindgen-runtime:jvmTest \
        --tests io.heapy.kwasm.bindgen.runtime.KotlinWasmCompilerCompatibilityTest \
        "-Pkwasm.kotlinWasmCompatibilityBinaries=$PREVIOUS_BINARY" \
        "-Pkwasm.kotlinWasmCompatibilityVersions=$PREVIOUS_KOTLIN_VERSION" \
        "-Pkwasm.kotlinWasmCompatibilityRequiredVersions=$CURRENT_KOTLIN_VERSION,$PREVIOUS_KOTLIN_VERSION"
)

readonly TEST_RESULT="$REPOSITORY_ROOT/wasm-bindgen-runtime/build/test-results/jvmTest/TEST-io.heapy.kwasm.bindgen.runtime.KotlinWasmCompilerCompatibilityTest.xml"
if [[ ! -f "$TEST_RESULT" ]]; then
    echo "Kotlin/Wasm compatibility test result was not produced" >&2
    exit 1
fi

for compiler_version in "$CURRENT_KOTLIN_VERSION" "$PREVIOUS_KOTLIN_VERSION"; do
    if ! grep -Fq \
        "KWASM_KOTLIN_WASM_COMPATIBILITY_PASS compiler=$compiler_version" \
        "$TEST_RESULT"; then
        echo "Missing executed compatibility row for Kotlin $compiler_version" >&2
        exit 1
    fi
done

echo "Verified Kotlin/Wasm compiler rows: $CURRENT_KOTLIN_VERSION, $PREVIOUS_KOTLIN_VERSION"
