#!/usr/bin/env bash

set -euo pipefail

# Kotlin's official release history lists 2.4.10 as the current line and
# 2.3.21 as the previous stable bug-fix release:
# https://kotlinlang.org/docs/releases.html#release-history
readonly CURRENT_KOTLIN_VERSION="2.4.10"
readonly PREVIOUS_KOTLIN_VERSION="2.3.21"
readonly CURRENT_LEGACY_ROW="$CURRENT_KOTLIN_VERSION-legacy-eh"
readonly CURRENT_NEW_ROW="$CURRENT_KOTLIN_VERSION-new-eh"
readonly PREVIOUS_LEGACY_ROW="$PREVIOUS_KOTLIN_VERSION-legacy-eh"
readonly PREVIOUS_NEW_ROW="$PREVIOUS_KOTLIN_VERSION-new-eh"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/kwasm-kotlin-wasm-compat.XXXXXX")"
readonly CURRENT_LEGACY_CHECKOUT="$WORK_ROOT/kotlin-$CURRENT_LEGACY_ROW"
readonly PREVIOUS_CHECKOUT="$WORK_ROOT/kotlin-$PREVIOUS_KOTLIN_VERSION"
readonly CURRENT_LEGACY_PROJECT_CACHE="$WORK_ROOT/project-cache-$CURRENT_LEGACY_ROW"
readonly PREVIOUS_PROJECT_CACHE="$WORK_ROOT/project-cache-$PREVIOUS_KOTLIN_VERSION"
readonly CURRENT_LEGACY_BINARY="$WORK_ROOT/$CURRENT_LEGACY_ROW.wasm"
readonly PREVIOUS_LEGACY_BINARY="$WORK_ROOT/$PREVIOUS_LEGACY_ROW.wasm"
readonly PREVIOUS_NEW_BINARY="$WORK_ROOT/$PREVIOUS_NEW_ROW.wasm"
readonly FIXTURE_RELATIVE_PATH="wasm-bindgen-api/build/compileSync/wasmWasi/test/testDevelopmentExecutable/kotlin/kwasm-bindgen-api-test.wasm"

cleanup() {
    if [[ "${KWASM_COMPAT_KEEP_WORK:-0}" == "1" ]]; then
        echo "Preserving isolated compiler checkouts: $WORK_ROOT"
    else
        rm -rf -- "$WORK_ROOT"
    fi
}
trap cleanup EXIT

copy_source_checkout() {
    local destination="$1"
    mkdir -p "$destination"
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
        cd "$destination"
        tar -xf -
    )
}

pin_previous_kotlin() {
    local checkout="$1"
    local version_catalog="$checkout/gradle/libs.versions.toml"
    local current_version_line="kotlin = \"$CURRENT_KOTLIN_VERSION\""
    local previous_version_line="kotlin = \"$PREVIOUS_KOTLIN_VERSION\""

    if [[ "$(grep -Fxc "$current_version_line" "$version_catalog")" != "1" ]]; then
        echo "Expected exactly one '$current_version_line' catalog entry" >&2
        exit 1
    fi

    sed \
        "s/^${current_version_line}\$/${previous_version_line}/" \
        "$version_catalog" \
        > "$version_catalog.tmp"
    mv "$version_catalog.tmp" "$version_catalog"

    if [[ "$(grep -Fxc "$previous_version_line" "$version_catalog")" != "1" ]]; then
        echo "Failed to pin isolated Kotlin compiler $PREVIOUS_KOTLIN_VERSION" >&2
        exit 1
    fi
    if grep -Fqx "$current_version_line" "$version_catalog"; then
        echo "Current Kotlin compiler leaked into the isolated version catalog" >&2
        exit 1
    fi
}

build_fixture() {
    local checkout="$1"
    local project_cache="$2"
    local output="$3"
    local exception_mode="$4"
    local label="$5"
    local -a gradle_arguments=(
        --project-dir "$checkout"
        --project-cache-dir "$project_cache"
        --no-daemon
        --no-build-cache
        --no-configuration-cache
        --stacktrace
        :bindgen-api:clean
        :bindgen-api:compileTestDevelopmentExecutableKotlinWasmWasi
    )
    if [[ "$exception_mode" == "new" ]]; then
        gradle_arguments+=("-Pkwasm.kotlinWasmUseNewExceptionProposal=true")
    else
        gradle_arguments+=("-Pkwasm.kotlinWasmUseNewExceptionProposal=false")
    fi

    echo "Building wasmWasi compatibility row $label"
    "$checkout/gradlew" "${gradle_arguments[@]}"

    local fixture="$checkout/$FIXTURE_RELATIVE_PATH"
    if [[ ! -s "$fixture" ]]; then
        echo "Compatibility row $label did not produce the expected wasmWasi fixture" >&2
        exit 1
    fi
    cp "$fixture" "$output"
}

copy_source_checkout "$CURRENT_LEGACY_CHECKOUT"
copy_source_checkout "$PREVIOUS_CHECKOUT"
pin_previous_kotlin "$PREVIOUS_CHECKOUT"

build_fixture \
    "$CURRENT_LEGACY_CHECKOUT" \
    "$CURRENT_LEGACY_PROJECT_CACHE" \
    "$CURRENT_LEGACY_BINARY" \
    legacy \
    "$CURRENT_LEGACY_ROW"
build_fixture \
    "$PREVIOUS_CHECKOUT" \
    "$PREVIOUS_PROJECT_CACHE" \
    "$PREVIOUS_LEGACY_BINARY" \
    legacy \
    "$PREVIOUS_LEGACY_ROW"
build_fixture \
    "$PREVIOUS_CHECKOUT" \
    "$PREVIOUS_PROJECT_CACHE" \
    "$PREVIOUS_NEW_BINARY" \
    new \
    "$PREVIOUS_NEW_ROW"

echo "Executing all four Kotlin/Wasm compiler and exception-encoding rows in kwasm"
(
    cd "$REPOSITORY_ROOT"
    ./gradlew \
        --no-daemon \
        --no-build-cache \
        --no-configuration-cache \
        --stacktrace \
        :bindgen-api:clean \
        :bindgen-runtime:jvmTest \
        --tests io.heapy.kwasm.bindgen.runtime.KotlinWasmCompilerCompatibilityTest \
        "-Pkwasm.kotlinWasmCompatibilityBinaries=$CURRENT_LEGACY_BINARY:$PREVIOUS_LEGACY_BINARY:$PREVIOUS_NEW_BINARY" \
        "-Pkwasm.kotlinWasmCompatibilityVersions=$CURRENT_LEGACY_ROW,$PREVIOUS_LEGACY_ROW,$PREVIOUS_NEW_ROW" \
        "-Pkwasm.kotlinWasmCompatibilityRequiredVersions=$CURRENT_NEW_ROW,$CURRENT_LEGACY_ROW,$PREVIOUS_LEGACY_ROW,$PREVIOUS_NEW_ROW"
)

readonly TEST_RESULT="$REPOSITORY_ROOT/wasm-bindgen-runtime/build/test-results/jvmTest/TEST-io.heapy.kwasm.bindgen.runtime.KotlinWasmCompilerCompatibilityTest.xml"
if [[ ! -f "$TEST_RESULT" ]]; then
    echo "Kotlin/Wasm compatibility test result was not produced" >&2
    exit 1
fi

for compiler_row in \
    "$CURRENT_NEW_ROW" \
    "$CURRENT_LEGACY_ROW" \
    "$PREVIOUS_LEGACY_ROW" \
    "$PREVIOUS_NEW_ROW"; do
    if ! grep -Fq \
        "KWASM_KOTLIN_WASM_COMPATIBILITY_PASS compiler=$compiler_row" \
        "$TEST_RESULT"; then
        echo "Missing executed compatibility row for $compiler_row" >&2
        exit 1
    fi
done

echo "Verified Kotlin/Wasm compiler rows: $CURRENT_NEW_ROW, $CURRENT_LEGACY_ROW, $PREVIOUS_LEGACY_ROW, $PREVIOUS_NEW_ROW"
