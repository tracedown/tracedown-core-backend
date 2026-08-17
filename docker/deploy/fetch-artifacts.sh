#!/bin/sh
# Downloads the Tracedown release artifacts this stack runs from.
#
# Resolves BACKEND_VERSION / FRONTEND_VERSION ("latest" or a tag like v0.1.0)
# against GitHub releases, fetches every service jar plus the schema-migrator
# distribution into /artifacts, and unpacks the frontend bundle into
# /frontend-dist for the host web server to serve. Idempotent: a version
# marker skips work already done, so restarting the stack is cheap and a
# version bump in .env triggers a re-fetch.
set -eu

BACKEND_REPO="tracedown/tracedown-core-backend"
FRONTEND_REPO="tracedown/tracedown-core-frontend"
SERVICES="api-gateway probe-scheduler result-ingestor notification-dispatcher email-service metrics-service aggregate-worker realtime-service"

apk add --no-cache curl >/dev/null

resolve() { # $1 = repo, $2 = requested version
  if [ "$2" = "latest" ]; then
    curl -fsSL "https://api.github.com/repos/$1/releases/latest" \
      | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1
  else
    echo "$2"
  fi
}

BACKEND_TAG=$(resolve "$BACKEND_REPO" "${BACKEND_VERSION:-latest}")
FRONTEND_TAG=$(resolve "$FRONTEND_REPO" "${FRONTEND_VERSION:-latest}")
[ -n "$BACKEND_TAG" ] || { echo "could not resolve backend release" >&2; exit 1; }
[ -n "$FRONTEND_TAG" ] || { echo "could not resolve frontend release" >&2; exit 1; }
BV="${BACKEND_TAG#v}"
FV="${FRONTEND_TAG#v}"

if [ -f "/artifacts/.backend-$BACKEND_TAG" ]; then
  echo "backend $BACKEND_TAG already present"
else
  echo "fetching backend $BACKEND_TAG"
  for s in $SERVICES; do
    curl -fSL -o "/artifacts/$s.jar.tmp" \
      "https://github.com/$BACKEND_REPO/releases/download/$BACKEND_TAG/$s-$BV-all.jar"
    mv "/artifacts/$s.jar.tmp" "/artifacts/$s.jar"
    echo "  $s.jar"
  done
  curl -fSL -o /artifacts/migrator.zip \
    "https://github.com/$BACKEND_REPO/releases/download/$BACKEND_TAG/schema-migrator-$BV.zip"
  rm -rf /artifacts/schema-migrator-dist
  unzip -q /artifacts/migrator.zip -d /artifacts/unpack
  mv "/artifacts/unpack/schema-migrator-$BV" /artifacts/schema-migrator-dist
  rm -rf /artifacts/unpack /artifacts/migrator.zip
  echo "  schema-migrator-dist/"
  rm -f /artifacts/.backend-*
  touch "/artifacts/.backend-$BACKEND_TAG"
fi

if [ -f "/artifacts/.frontend-$FRONTEND_TAG" ]; then
  echo "frontend $FRONTEND_TAG already present"
else
  echo "fetching frontend $FRONTEND_TAG"
  curl -fSL -o /tmp/frontend.tar.gz \
    "https://github.com/$FRONTEND_REPO/releases/download/$FRONTEND_TAG/tracedown-core-frontend-$FV-dist.tar.gz"
  rm -rf /frontend-dist/* 2>/dev/null || true
  tar -xzf /tmp/frontend.tar.gz -C /frontend-dist
  rm -f /tmp/frontend.tar.gz
  rm -f /artifacts/.frontend-*
  touch "/artifacts/.frontend-$FRONTEND_TAG"
fi

echo "artifacts ready: backend $BACKEND_TAG, frontend $FRONTEND_TAG"
