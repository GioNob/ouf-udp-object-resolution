#!/bin/sh
set -eu
# Grid resources must be an immutable/read-only deployment mount.
proj_manifest_sha=$(python3 /usr/local/bin/ouf-verify-grids.py)
if [ "$#" -eq 0 ]; then set -- postgres; fi
if [ "$1" != postgres ]; then
  echo 'OUF_PROJ_POSTGRES_COMMAND_REQUIRED' >&2
  exit 1
fi
exec /usr/local/bin/docker-entrypoint.sh "$@" -c "ouf.proj_manifest_sha256=$proj_manifest_sha"
