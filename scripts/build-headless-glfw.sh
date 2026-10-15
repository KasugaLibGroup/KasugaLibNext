#!/usr/bin/env bash
set -euo pipefail
[[ $(uname -s) == Linux ]] || { echo 'The EGL pbuffer GLFW build requires Linux.' >&2; exit 1; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output_dir=${1:-"$script_dir/../modules/modelling/build/headless-glfw"}
mkdir -p "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
archive="$output_dir/glfw-3.4.tar.gz"
if [[ ! -f $archive ]]; then
    curl --fail --location --retry 3 https://codeload.github.com/glfw/glfw/tar.gz/refs/tags/3.4 -o "$archive"
fi
printf '%s  %s\n' c038d34200234d071fae9345bc455e4a8f2f544ab60150765d7704e08f3dac01 "$archive" | sha256sum --check --status
source_dir="$output_dir/source"
# Re-extract pinned sources to make reruns independent of a previous patch/build.
mkdir -p "$source_dir"
tar -xzf "$archive" -C "$source_dir" --strip-components=1
patch --directory="$source_dir" -p1 < "$script_dir/glfw-null-egl-pbuffer.patch"
cmake -S "$source_dir" -B "$output_dir/cmake" \
    -DBUILD_SHARED_LIBS=ON -DGLFW_BUILD_X11=OFF -DGLFW_BUILD_WAYLAND=OFF \
    -DGLFW_BUILD_EXAMPLES=OFF -DGLFW_BUILD_TESTS=OFF -DGLFW_BUILD_DOCS=OFF
cmake --build "$output_dir/cmake" --parallel
echo "$output_dir/cmake/src/libglfw.so"
