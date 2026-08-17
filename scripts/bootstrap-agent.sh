#!/usr/bin/env bash
# Bootstraps (or re-bootstraps) the probe agent against a running stack.
# Generates a new token, stops any existing agent, and starts a fresh one.
#
# Usage: ./scripts/bootstrap-agent.sh [slug]
#
# slug: agent identifier (default: dev-agent)
#
# Environment:
#   AGENT_IMAGE  image to run. Unset = build `tracedown-agent` locally from
#                ../tracedown-probe-agent. Set it to use a published image
#                instead, e.g. AGENT_IMAGE=tracedown/tracedown-probe-agent:latest
#
# Requires: the backend docker stack to be running (gateway healthy).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="docker compose -f $BACKEND_DIR/docker/docker-compose.yml"

SLUG="${1:-dev-agent}"
AGENT_IMAGE="${AGENT_IMAGE:-}"
AGENT_CONTAINER="tracedown-agent-${SLUG}"
AGENT_NETWORK="tracedown_tracedown-net"

DB_NAME="${DB_NAME:-tracedown}"
DB_USER="${DB_USER:-tracedown}"
DB_PASSWORD="${DB_PASSWORD:-tracedown}"
PLATFORM_AES_KEY="${PLATFORM_AES_KEY:-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef}"

# Fast path: if the agent container already exists, try to just restart it
# instead of regenerating a token and rebuilding the image.
if docker inspect "$AGENT_CONTAINER" >/dev/null 2>&1; then
  echo "==> Existing agent container '$AGENT_CONTAINER' found."

  # Network ID the container currently believes it is attached to.
  ATTACHED_NET_ID=$(docker inspect \
    -f "{{with index .NetworkSettings.Networks \"$AGENT_NETWORK\"}}{{.NetworkID}}{{end}}" \
    "$AGENT_CONTAINER" 2>/dev/null || true)
  # Live network ID currently registered under that name (empty if it's gone).
  LIVE_NET_ID=$(docker network inspect -f '{{.Id}}' "$AGENT_NETWORK" 2>/dev/null || true)

  if [[ -n "$ATTACHED_NET_ID" && "$ATTACHED_NET_ID" == "$LIVE_NET_ID" ]]; then
    # Network is unchanged — just bring the container back up.
    echo "==> Network '$AGENT_NETWORK' still present. Restarting agent..."
    docker start "$AGENT_CONTAINER" >/dev/null
    echo "==> Agent restarted."
    exit 0
  fi

  if [[ -n "$LIVE_NET_ID" ]]; then
    # Network was regenerated with a new id (e.g. compose down/up) — reconnect.
    # The slug alias must be re-registered on the new network for the
    # scheduler to resolve the agent's URI.
    echo "==> Network '$AGENT_NETWORK' was regenerated. Reconnecting agent..."
    docker network disconnect -f "$AGENT_NETWORK" "$AGENT_CONTAINER" 2>/dev/null || true
    docker network connect --alias "$SLUG" "$AGENT_NETWORK" "$AGENT_CONTAINER"
    docker start "$AGENT_CONTAINER" >/dev/null
    echo "==> Agent reconnected and restarted."
    exit 0
  fi

  echo "ERROR: backend network '$AGENT_NETWORK' not found. Start the backend first."
  exit 1
fi

# Check gateway is running
if ! docker inspect --format='{{.State.Health.Status}}' tracedown-gateway 2>/dev/null | grep -q healthy; then
  echo "ERROR: tracedown-gateway is not healthy. Start the stack first."
  exit 1
fi

# A registered slug cannot re-register: the gateway rejects it outright
# (single-enrolment by design). Catch it here with a clear message instead of
# letting the agent die on an opaque 400 at startup.
EXISTING_SLUG=$(docker exec tracedown-postgres \
  psql -U "$DB_USER" -d "$DB_NAME" -t -A \
  -c "select count(*) from probe_agents where slug='$SLUG'" 2>/dev/null || echo 0)
if [[ "$EXISTING_SLUG" != "0" ]]; then
  echo "ERROR: an agent with slug '$SLUG' is already registered but its"
  echo "container is gone. Registration is single-enrolment: remove the agent"
  echo "first (dashboard: Settings -> Agents -> remove) or pick another slug."
  exit 1
fi

# Generate bootstrap token
echo "==> Generating agent bootstrap token..."
TOKEN_OUTPUT=$($COMPOSE run --rm \
  -e DATABASE_URL=jdbc:postgresql://tracedown-postgres:5432/$DB_NAME \
  -e DATABASE_USER=$DB_USER \
  -e DATABASE_PASSWORD=$DB_PASSWORD \
  -e PLATFORM_AES_KEY=$PLATFORM_AES_KEY \
  tracedown-gateway ./bin/api-gateway --agent-bootstrap "$SLUG" --label "Dev Agent" 2>&1)

TOKEN=$(echo "$TOKEN_OUTPUT" | grep "Token:" | awk '{print $2}')
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: Failed to extract bootstrap token."
  echo "$TOKEN_OUTPUT"
  exit 1
fi
echo "    Token: ${TOKEN:0:16}..."

# Stop old agent if running
docker rm -f "$AGENT_CONTAINER" 2>/dev/null || true

if [[ -z "$AGENT_IMAGE" ]]; then
  # Always rebuild the local agent image to pick up code changes.
  AGENT_IMAGE="tracedown-agent"
  echo "==> Building agent image..."
  docker build --no-cache -t "$AGENT_IMAGE" "$BACKEND_DIR/../tracedown-probe-agent/"
else
  echo "==> Using agent image: $AGENT_IMAGE"
fi

# Start agent. Hostname and network alias must equal the slug: the agent
# self-reports its URI from its hostname, the certificate's SAN is the slug
# (set server-side), and the scheduler verifies the TLS hostname against it —
# a mismatched hostname fails every health challenge and probe dispatch.
echo "==> Starting probe agent..."
docker run -d \
  --name "$AGENT_CONTAINER" \
  --hostname "$SLUG" \
  --network "$AGENT_NETWORK" \
  --network-alias "$SLUG" \
  -v tracedown_tracedown-bodies:/data/bodies \
  -e PROBE_AGENT_BOOTSTRAP_TOKEN="$TOKEN" \
  -e PROBE_AGENT_SCHEDULER_URL=http://tracedown-gateway:20714 \
  -e PROBE_AGENT_PORT=8443 \
  -e PROBE_AGENT_STORAGE_BACKEND=filesystem \
  -e PROBE_AGENT_STORAGE_DIR=/data/bodies \
  "$AGENT_IMAGE" > /dev/null

echo "==> Agent started. Will be healthy after first health check (~1 min)."
