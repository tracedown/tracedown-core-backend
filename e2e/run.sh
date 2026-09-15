#!/usr/bin/env bash
#
# End-to-end test runner for the Tracedown probe pipeline.
# Builds all services, starts the full stack in Docker, and runs
# integration tests against it.
#
# Usage: ./run.sh
# Requires: Docker, Java 17+, Python 3.10+
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.e2e.yml"

# Docker compose wrapper. Uses relative path to avoid Git Bash
# MSYS path mangling issues on Windows.
dc() {
    cd "$SCRIPT_DIR"
    MSYS_NO_PATHCONV=1 docker compose -f docker-compose.e2e.yml "$@"
}

# ── Cleanup on exit ──
cleanup() {
    echo ""
    echo "=== Tearing down ==="
    cd "$SCRIPT_DIR"
    dc down -v --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

# ── Build backend services ──
echo "=== Building backend services ==="
cd "$BACKEND_DIR"
./gradlew :schema-migrator:installDist :api-gateway:installDist :probe-scheduler:installDist :result-ingestor:installDist :aggregate-worker:installDist :notification-dispatcher:installDist :email-service:installDist :metrics-service:installDist --quiet

# ── Generate bootstrap token ──
echo "=== Generating agent bootstrap token ==="
cd "$SCRIPT_DIR"

# Start only postgres first
dc up -d postgres
echo "Waiting for postgres..."
until dc exec -T postgres pg_isready -U tracedown > /dev/null 2>&1; do
    sleep 1
done

# Run migrations
dc up migrator

# Generate bootstrap token via the gateway CLI
AGENT_TOKEN=$(dc run --rm \
    -e DATABASE_URL=jdbc:postgresql://postgres:5432/tracedown \
    -e DATABASE_USER=tracedown \
    -e DATABASE_PASSWORD=tracedown \
    -e PLATFORM_AES_KEY=0000000000000000000000000000000000000000000000000000000000000000 \
    gateway /app/bin/api-gateway --agent-bootstrap e2e-agent --label "E2E Agent" 2>&1 \
    | grep "Token:" | awk '{print $2}')

if [ -z "$AGENT_TOKEN" ]; then
    echo "ERROR: Failed to generate bootstrap token"
    exit 1
fi
echo "Bootstrap token: ${AGENT_TOKEN:0:16}..."

# ── Start full stack ──
echo "=== Starting full stack ==="
export AGENT_BOOTSTRAP_TOKEN="$AGENT_TOKEN"
dc up -d --build redis gateway scheduler ingestor worker dispatcher email metrics agent

# ── Wait for services ──
echo "=== Waiting for services to be healthy ==="
TIMEOUT=120
ELAPSED=0
while [ $ELAPSED -lt $TIMEOUT ]; do
    GW_HEALTH=$(dc ps gateway --format json 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")
    AGENT_HEALTH=$(dc ps agent --format json 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health',''))" 2>/dev/null || echo "")

    if [ "$GW_HEALTH" = "healthy" ] && [ "$AGENT_HEALTH" = "healthy" ]; then
        echo "All services healthy after ${ELAPSED}s"
        break
    fi

    if [ $((ELAPSED % 10)) -eq 0 ] && [ $ELAPSED -gt 0 ]; then
        echo "  Still waiting... (${ELAPSED}s) gateway=$GW_HEALTH agent=$AGENT_HEALTH"
    fi
    sleep 2
    ELAPSED=$((ELAPSED + 2))
done

if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "ERROR: Services did not become healthy within ${TIMEOUT}s"
    echo "=== Gateway logs ===" && dc logs gateway --tail 30
    echo "=== Scheduler logs ===" && dc logs scheduler --tail 30
    echo "=== Agent logs ===" && dc logs agent --tail 30
    exit 1
fi

# Let the scheduler run at least one consistency sweep
echo "Waiting for scheduler sweep..."
sleep 15

# ── Run tests ──
echo "=== Running e2e tests ==="
cd "$SCRIPT_DIR"
set +e
python3 run_tests.py
EXIT_CODE=$?
set -e

if [ $EXIT_CODE -ne 0 ]; then
    echo ""
    echo "=== E2E tests FAILED — capturing service logs ==="
    cd "$SCRIPT_DIR"
    for svc in worker metrics dispatcher; do
        echo "--- $svc logs ---"
        MSYS_NO_PATHCONV=1 docker compose -f docker-compose.e2e.yml logs "$svc" --tail 30 2>&1 || true
    done
else
    echo ""
    echo "=== All e2e tests passed ==="
fi

exit $EXIT_CODE
