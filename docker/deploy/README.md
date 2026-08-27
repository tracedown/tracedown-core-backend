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

## Your first account

Tracedown is invite-only: everyone after the first person is invited from
inside the app, but the first owner has to be created by the stack. That is
what `SINGLE_ORG_MODE` is for. It is **off by default**, and because the
credentials it reads ship with the source, the gateway refuses to start with
it on under `DEPLOYMENT_ENV=production` unless you have replaced them:

```bash
# in .env
SINGLE_ORG_MODE=true
DEMO_USER_EMAIL=you@example.com
DEMO_USER_PASSWORD=<a real password, it must pass the password policy>
```

`docker compose up -d`, sign in, then set `SINGLE_ORG_MODE=false` again — it
only ever acts on an empty user table, but leaving it on is one less thing to
reason about. Further organizations come from the CLI:

```bash
docker compose run --rm tracedown-gateway \
  java -jar /artifacts/api-gateway.jar --create-org <name> --owner <email>
```

Then enrol at least one probe agent — nothing probes without one. The agent
ships as a Docker image and pip package; see the
[tracedown-probe-agent](https://github.com/tracedown/tracedown-probe-agent)
README and the documentation at [tracedown.dev](https://tracedown.dev).

Versions are pinned in `.env` (`BACKEND_VERSION` / `FRONTEND_VERSION`).
Upgrading is: bump the versions, `docker compose up -d` — the fetcher
re-downloads, the migrator applies pending migrations before any service
starts, and the services restart on the new jars.
