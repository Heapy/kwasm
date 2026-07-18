#!/usr/bin/env bash
set -euo pipefail

readonly CHASM_REPOSITORY="https://github.com/CharlieTap/chasm.git"
readonly CHASM_COMMIT="9e2e2fa50eef63c793473894633a00e5d58bcefe"
readonly COREMARK_RELATIVE_PATH="benchmark/src/commonMain/resources/benchmark/coremark.wasm"
readonly COREMARK_SHA256="77da1d88a16d432a6c74d3e60d1e239003f2adc1e50b31125507bb8e175af05a"

readonly ROOT_DIRECTORY="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
  pwd
)"
readonly UPSTREAM_DIRECTORY="${ROOT_DIRECTORY}/build/upstreams/chasm"

if [[ ! -d "${UPSTREAM_DIRECTORY}/.git" ]]; then
  mkdir -p "$(dirname -- "${UPSTREAM_DIRECTORY}")"
  git clone \
    --filter=blob:none \
    --no-checkout \
    "${CHASM_REPOSITORY}" \
    "${UPSTREAM_DIRECTORY}"
fi

if ! git -C "${UPSTREAM_DIRECTORY}" cat-file -e "${CHASM_COMMIT}^{commit}" 2>/dev/null; then
  git -C "${UPSTREAM_DIRECTORY}" fetch \
    --depth=1 \
    origin \
    "${CHASM_COMMIT}"
fi

git -C "${UPSTREAM_DIRECTORY}" checkout --detach --force "${CHASM_COMMIT}"

actual_commit="$(git -C "${UPSTREAM_DIRECTORY}" rev-parse HEAD)"
if [[ "${actual_commit}" != "${CHASM_COMMIT}" ]]; then
  echo "Chasm checkout is ${actual_commit}; expected ${CHASM_COMMIT}" >&2
  exit 1
fi

coremark_path="${UPSTREAM_DIRECTORY}/${COREMARK_RELATIVE_PATH}"
if [[ ! -f "${coremark_path}" ]]; then
  echo "Pinned Chasm checkout does not contain ${COREMARK_RELATIVE_PATH}" >&2
  exit 1
fi

actual_sha256="$(shasum -a 256 "${coremark_path}" | awk '{print $1}')"
if [[ "${actual_sha256}" != "${COREMARK_SHA256}" ]]; then
  echo "CoreMark fixture SHA-256 is ${actual_sha256}; expected ${COREMARK_SHA256}" >&2
  exit 1
fi

printf '%s\n' "${coremark_path}"
