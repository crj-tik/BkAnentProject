#!/usr/bin/env bash
# Executed only for an empty MySQL volume by the official image entrypoint.
BK_INIT_ROOT=/opt/bk-init bash /opt/bk-init/scripts/deploy/run-init-manifest.sh --mode fresh
