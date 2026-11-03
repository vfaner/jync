#!/usr/bin/env bash
#
# Runs the real-database verification matrix locally. Not wired into CI and not
# part of the default `mvn test` run; *IT classes are opt-in by name.
#
# Default suite: the two container ITs added in v1.12.0 (MariaDB, ClickHouse).
# JYNC_FULL_MATRIX=1 additionally runs the older Oracle / MySQL / openGauss /
# SQL Server suites.
#
# Env-gated tests run only when their variables are present; otherwise they are
# reported as SKIP with the missing variable, never as a failure.
#
set -euo pipefail

cd "$(dirname "$0")/.."

MVN="${MVN:-mvn}"
DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.44}"
MVN_ARGS=(-q test "-Dapi.version=${DOCKER_API_VERSION}")

RUN=0
SKIP=0

say() {
    printf '%s\n' "$*"
}

run_test() {
    local name="$1"
    say ""
    say "RUN  ${name}"
    "${MVN}" "${MVN_ARGS[@]}" "-Dtest=${name}"
    RUN=$((RUN + 1))
}

skip_test() {
    local name="$1"
    local reason="$2"
    say ""
    say "SKIP ${name} — ${reason}"
    SKIP=$((SKIP + 1))
}

require_env() {
    # require_env TEST_NAME VAR [VAR...]
    local name="$1"
    shift
    local missing=()
    local var
    for var in "$@"; do
        if [[ -z "${!var:-}" ]]; then
            missing+=("${var}")
        fi
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        skip_test "${name}" "missing ${missing[*]}"
        return 1
    fi
    return 0
}

say "Jync verification matrix (Docker API ${DOCKER_API_VERSION}, Maven: ${MVN})"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    say ""
    say "WARN Docker is unavailable: every container test below will skip."
fi

# --- Default container suite (must work with Docker alone) ------------------
run_test MariaDbPollingIT
run_test ClickHouseSourceIT

# --- Older container suites (heavier: Oracle images) -----------------------
if [[ "${JYNC_FULL_MATRIX:-0}" == "1" ]]; then
    run_test MysqlToOracleIT
    run_test PostgresToOpenGaussIT
    run_test SqlServerToOpenGaussIT
    run_test OracleTargetIdentityIT
    run_test MetadataStorePostgresIT
else
    skip_test "MysqlToOracleIT,PostgresToOpenGaussIT,SqlServerToOpenGaussIT,OracleTargetIdentityIT,MetadataStorePostgresIT" \
        "set JYNC_FULL_MATRIX=1 to run the heavier historical container suites"
fi

# --- Env-gated tests --------------------------------------------------------
if require_env KingbasePollingIT JYNC_KINGBASE_IT_URL JYNC_KINGBASE_IT_USER JYNC_KINGBASE_IT_PASSWORD; then
    run_test KingbasePollingIT
fi

if require_env YashanDbPollingIT JYNC_YASHAN_IT_URL JYNC_YASHAN_IT_USER JYNC_YASHAN_IT_PASSWORD \
        JYNC_YASHAN_IT_SCHEMA JYNC_YASHAN_IT_TARGET_SCHEMA; then
    run_test YashanDbPollingIT
fi

if require_env DorisSourceIT JYNC_DORIS_IT_URL JYNC_DORIS_IT_USER JYNC_DORIS_IT_PASSWORD; then
    run_test DorisSourceIT
fi

if require_env ElasticsearchSqlSourceIT JYNC_ES_IT_URL JYNC_ES_IT_DRIVER_JAR; then
    run_test ElasticsearchSqlSourceIT
fi

say ""
say "Done: ${RUN} test class(es) run, ${SKIP} skipped."
say ""
say "Manual steps for products without a runnable test here:"
say "  Doris:   point JYNC_DORIS_IT_URL at jdbc:mysql://<fe>:9030/<db> (see docs/verification-matrix.md)"
say "  ES SQL:  supply JYNC_ES_IT_DRIVER_JAR from elastic.co (not on Maven Central)"
say "  Docs:    docs/verification-matrix.md"
