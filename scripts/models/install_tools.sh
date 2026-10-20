#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
conda_command="${CONDA_EXE:-conda}"

"$conda_command" run -n dl --no-capture-output python -m pip install \
  -r "$repo_root/scripts/models/requirements.txt"

# LiteRT's converter pins an old xDSL whose typing-extensions metadata conflicts
# with modern packages in dl. Isolate those three converter packages, retaining
# dl's current typing-extensions. The full conversion/parity suite validates this
# combination; nothing in the app runtime depends on this Python overlay.
"$conda_command" run -n dl --no-capture-output python -m pip install --no-deps \
  --target "$repo_root/artifacts/models/converter-python" --upgrade \
  litert-torch==0.10.0 litert-converter==0.5.0 xdsl==0.28.0
"$conda_command" run -n dl --no-capture-output python -m pip check
