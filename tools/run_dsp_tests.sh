#!/usr/bin/env bash
# Builds and runs the C++ DSP tests on the host compiler. No Android SDK or device needed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${ROOT}/tools/build/dsp-tests"

cmake -S "${ROOT}/app/src/main/cpp/test" -B "${BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "${BUILD_DIR}" -j "$(nproc 2>/dev/null || echo 4)" >/dev/null

"${BUILD_DIR}/kolan_dsp_tests"
