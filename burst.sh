#!/usr/bin/env bash
# Usage: ./burst.sh <BASE_URL> [extra flags]
#   ./burst.sh http://localhost:8080
#   ./burst.sh https://seat-reservation-86o2.onrender.com -requests 20000 -concurrency 300
# Flags: -requests N -concurrency N -hot-seats N -hot-users N -limit N -seed N -secret S
# Uses a local Go toolchain if present, otherwise runs inside the golang Docker image.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
shift || true
DIR="$(cd "$(dirname "$0")" && pwd)"

if command -v go >/dev/null 2>&1; then
  cd "$DIR/burst"
  exec go run . -base "$BASE_URL" "$@"
fi

# Inside a container "localhost" is the container itself; point it at the host instead.
DOCKER_BASE="${BASE_URL/localhost/host.docker.internal}"
DOCKER_BASE="${DOCKER_BASE/127.0.0.1/host.docker.internal}"
exec docker run --rm --add-host=host.docker.internal:host-gateway \
  -e AUTH_SECRET="${AUTH_SECRET:-}" \
  -v "$DIR/burst:/src" -w /src golang:1.23-alpine \
  go run . -base "$DOCKER_BASE" "$@"
