#!/usr/bin/env bash
set -euo pipefail

readonly WASMTIME_VERSION="45.0.0"
readonly WASMTIME_RELEASE="v${WASMTIME_VERSION}"
readonly WASMTIME_RELEASE_BASE="https://github.com/bytecodealliance/wasmtime/releases/download/${WASMTIME_RELEASE}"

readonly ROOT_DIRECTORY="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
  pwd
)"
readonly SOURCE_FILE="${ROOT_DIRECTORY}/benchmarks/tools/wasmtime_reference_runner.c"
readonly UPSTREAM_DIRECTORY="${ROOT_DIRECTORY}/build/upstreams/wasmtime"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)
    platform="aarch64-macos"
    artifact_sha256="43cd87ec7d398f2e799e81c7d4e143d930e0139953d3c5d2a9c4055789f29851"
    runtime_rpath="@loader_path/lib"
    ;;
  Darwin-x86_64)
    platform="x86_64-macos"
    artifact_sha256="92d6b32a31711127fde10acbf5b984fa37b94052cec783a4fca6edd0bb8cdd6f"
    runtime_rpath="@loader_path/lib"
    ;;
  Linux-aarch64 | Linux-arm64)
    platform="aarch64-linux"
    artifact_sha256="59794105fcdcd3d5dd496acc63a78cefa5fad63662b3efb9bcd21ee0616f4944"
    runtime_rpath='$ORIGIN/lib'
    ;;
  Linux-x86_64)
    platform="x86_64-linux"
    artifact_sha256="95959e7a4cc4bfc12bbe45c9dea82cf45dd5b4321d9163e66343c50728429129"
    runtime_rpath='$ORIGIN/lib'
    ;;
  *)
    echo "Unsupported Wasmtime reference host: $(uname -s)-$(uname -m)" >&2
    exit 1
    ;;
esac

readonly artifact="wasmtime-${WASMTIME_RELEASE}-${platform}-c-api.tar.xz"
readonly archive="${UPSTREAM_DIRECTORY}/${artifact}"
readonly distribution="${UPSTREAM_DIRECTORY}/wasmtime-${WASMTIME_RELEASE}-${platform}-c-api"
readonly runner="${distribution}/kwasm-wasmtime-reference"

mkdir -p "${UPSTREAM_DIRECTORY}"
if [[ ! -f "${archive}" ]]; then
  partial_archive="${archive}.partial.$$"
  curl \
    --fail \
    --location \
    --retry 3 \
    --output "${partial_archive}" \
    "${WASMTIME_RELEASE_BASE}/${artifact}"
  mv "${partial_archive}" "${archive}"
fi

actual_sha256="$(shasum -a 256 "${archive}" | awk '{print $1}')"
if [[ "${actual_sha256}" != "${artifact_sha256}" ]]; then
  echo "Wasmtime C API archive SHA-256 is ${actual_sha256}; expected ${artifact_sha256}" >&2
  exit 1
fi

if [[ ! -f "${distribution}/include/wasmtime.h" ]] ||
  [[ ! -f "${distribution}/lib/libwasmtime.dylib" && ! -f "${distribution}/lib/libwasmtime.so" ]]; then
  tar -xJf "${archive}" -C "${UPSTREAM_DIRECTORY}"
fi

if [[ ! -x "${runner}" || "${SOURCE_FILE}" -nt "${runner}" ]]; then
  cc \
    -std=c11 \
    -O2 \
    -Wall \
    -Wextra \
    -Werror \
    "-DWASMTIME_REFERENCE_ARTIFACT=\"${artifact}\"" \
    "-DWASMTIME_REFERENCE_ARTIFACT_SHA256=\"${artifact_sha256}\"" \
    -I "${distribution}/include" \
    "${SOURCE_FILE}" \
    -L "${distribution}/lib" \
    -lwasmtime \
    "-Wl,-rpath,${runtime_rpath}" \
    -o "${runner}"
fi

printf '%s\n' "${runner}"
