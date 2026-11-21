#!/usr/bin/env bash
#
# Release gate: the unit suite first, then the real-database matrix.
#
# The matrix catches what mocks cannot — the 2.4.x SQL Server CDC discovery bug
# (asking for columns cdc.change_tables does not have) was found by a container IT,
# not by any unit test. Run this before tagging a release.
#
# Env-gated suites (Kingbase, YashanDB, Doris, ES SQL) run only when their variables
# are exported; otherwise they are reported as SKIP by the matrix script.
#
set -euo pipefail

cd "$(dirname "$0")/.."

MVN="${MVN:-mvn}"
DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"

say() {
    printf '%s\n' "$*"
}

say "== [1/2] unit suite"
"${MVN}" -q test "-Dapi.version=${DOCKER_API_VERSION}"

say ""
say "== [2/2] real-database matrix"
./scripts/run-verification-matrix.sh

say ""
say "Release gate passed."
