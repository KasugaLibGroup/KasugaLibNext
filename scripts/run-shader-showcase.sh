#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/.." && pwd)
cd "$repo_dir"
python=${KASUGA_SHOWCASE_PYTHON:-python3}
venv="$repo_dir/build/shader-showcase-tools"
if [[ ! -x "$venv/bin/python" ]]; then
    "$python" -m venv "$venv"
fi
"$venv/bin/python" -m pip install --disable-pip-version-check -r examples/shader-dsl/requirements.txt
exec "$venv/bin/python" "$script_dir/shader-showcase.py" "$@"
