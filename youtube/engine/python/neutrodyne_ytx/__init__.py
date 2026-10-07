# SPDX-License-Identifier: Unlicense
"""Shared yt-dlp engine shim (04 Shared engine module); one package for both hosts.

M0a holds the S7 hello-world only (`selftest` reports the interpreter's versions); the method
table of bridge.py, errors.py and the host adapters arrive with M9a.
"""

SHIM_API_VERSION = 1
