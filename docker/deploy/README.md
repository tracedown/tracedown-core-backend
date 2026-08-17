# Deploying Tracedown from release artifacts

This directory runs the whole platform — backend services **and** the
frontend — from published GitHub releases. Nothing is built from source: a
one-shot fetcher downloads each service's jar, the schema-migrator
distribution, and the frontend bundle, pinned by version or tracking latest.

```bash
cp .env.example .env      # work through it — start with the REQUIRED section
docker compose up -d
```

The stack publishes three ports on 127.0.0.1 only (API gateway, WebSocket,
metrics). Exposure to the world is your host web server's job:

1. Copy `nginx.conf` **or** `apache.conf` into your server's config, adjust
   `server_name`/`ServerName` and the frontend path (the fetcher unpacks the
   bundle into `./frontend-dist`).
2. Both configs are **HTTP-only on purpose** — once the vhost works, run
   certbot (`--nginx` / `--apache`) or install your internal certificates.
3. Reload the server. The frontend calls same-origin `/api/v1` and `/ws`,
   which the config proxies to the localhost ports.

Then enrol at least one probe agent — nothing probes without one. The agent
ships as a Docker image and pip package; see the
[tracedown-probe-agent](https://github.com/tracedown/tracedown-probe-agent)
README and the documentation at [tracedown.dev](https://tracedown.dev).

Versions are pinned in `.env` (`BACKEND_VERSION` / `FRONTEND_VERSION`).
Upgrading is: bump the versions, `docker compose up -d` — the fetcher
re-downloads, the migrator applies pending migrations before any service
starts, and the services restart on the new jars.
