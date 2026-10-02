#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/.." && pwd)
cd "$repo_dir"
backend=${KASUGA_HEADLESS_BACKEND:-hidden}
launcher_args=(:modules:modelling:runClientHeadless)
if [[ $(uname -s) == Linux ]]; then
    backend=${KASUGA_HEADLESS_BACKEND:-egl}
fi
if [[ $backend == egl ]]; then
    [[ $(uname -s) == Linux ]] || { echo 'EGL headless requires Linux.' >&2; exit 1; }
    unset DISPLAY WAYLAND_DISPLAY
    export EGL_PLATFORM=${EGL_PLATFORM:-surfaceless}
    library=${KASUGA_HEADLESS_GLFW_LIBRARY:-"$repo_dir/modules/modelling/build/headless-glfw/cmake/src/libglfw.so"}
    if [[ ! -f $library ]]; then
        if [[ -n ${KASUGA_HEADLESS_GLFW_LIBRARY:-} ]]; then
            echo "Configured GLFW library does not exist: $library" >&2; exit 1
        fi
        "$script_dir/build-headless-glfw.sh"
    fi
    launcher_args+=("-PkasugaHeadlessGlfwLibrary=$library")
fi
launcher_args+=("-PkasugaHeadlessBackend=$backend")
exec ./gradlew "${launcher_args[@]}" "$@"
