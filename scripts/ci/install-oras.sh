#!/usr/bin/env bash
# SPDX-License-Identifier: Unlicense
set -euo pipefail
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL https://github.com/oras-project/oras/releases/download/v1.3.0/oras_1.3.0_linux_amd64.tar.gz -o "$work/oras.tar.gz"
echo '6cdc692f929100feb08aa8de584d02f7bcc30ec7d88bc2adc2054d782db57c64  '"$work/oras.tar.gz" | sha256sum -c -
tar -xzf "$work/oras.tar.gz" -C "$work" oras
mkdir -p "$HOME/.local/bin"
install -m 755 "$work/oras" "$HOME/.local/bin/oras"
if [[ -n "${GITHUB_PATH:-}" ]]; then echo "$HOME/.local/bin" >> "$GITHUB_PATH"; fi
