# Tracedown

**Self-hosted API monitoring.** Tracedown runs automated checks against your
HTTP APIs on a schedule, records what happened in full detail, and tells your
team when something breaks or slows down.

A check can be as simple as *"is this endpoint returning 200?"* or as involved as
*"log in, fetch the order, verify the total changed, and warn me if the TLS
handshake got slower"*. Probes are written in [Lace](https://lacelang.dev), a
language built for exactly this — not YAML with an `if` bolted on, and not a
general-purpose runtime you have to sandbox.

You run it on your own infrastructure, and it does not phone home.

📖 **Documentation: [tracedown.dev](https://tracedown.dev)**

## What a probe looks like

```lace
post("$p.baseUrl/login", {
  body: json({ email: "$o.apiUser", password: "$o.apiPassword" })
})
.expect(status: 200)
.store({ "$$token": this.body.access_token })

get("$p.baseUrl/orders", {
  headers: { Authorization: "Bearer $$token" }
})
.expect(status: 200)
.check(
  totalDelayMs: { value: 800 },
  ttfb:         { value: 300 }
)
```

`.expect()` fails the run; `.check()` records the problem and carries on. Every
call returns a full timing breakdown — DNS, connect, TLS, time-to-first-byte,
transfer — and every assertion records what it actually saw, not just pass/fail.

## Capabilities

- **Scripted probes** — multi-step HTTP flows with chaining, captured values,
  cookie jars and redirects. Assert on status, body (literal or JSON schema),
  headers, size, redirect hops, and each timing phase individually.
- **Scheduling** — cron per service, with per-call timeouts and queue policies
  that decide what happens when a run overruns its own interval.
- **Distributed agents** — probes execute on agents you deploy where you need
  them, authenticated with mutual TLS against an internal CA and health-checked
  every minute by running a real probe script.
- **Scoped variables** — define once at the organization, override per
  workspace, project or service. Secrets encrypted at rest; scripts can write
  values back for the next run.
- **Notifications** — email and webhooks, emitted from the probe script itself,
  with per-resource silences, personal quiet hours and maintenance windows.
- **History and aggregation** — every run stored with its steps; hourly and
  daily rollups keep long windows cheap. Retention is yours to set.
- **Access control** — organizations, workspaces, projects and services, with
  per-section permissions, groups, invites and TOTP two-factor.
- **Metrics out** — a Prometheus scrape endpoint and Grafana integration.

## This repository

The backend: Kotlin/JVM microservices plus the schema migrator and the shared
`tracedown-core-common` library.

| Service | Responsibility |
|---|---|
| `api-gateway` | REST API, auth, orgs/workspaces/projects/services, internal CA, agent enrolment, CLI tools |
| `probe-scheduler` | Cron scheduling, agent selection, mTLS dispatch, health challenges |
| `result-ingestor` | Drains results from the queue into Postgres |
| `notification-dispatcher` | Outbox consumer; email and webhook delivery |
| `email-service` | Queue-based email dispatch (SMTP, Mailgun, Resend, file, console) |
| `metrics-service` | Prometheus scrape endpoint, Grafana integrations |
| `aggregate-worker` | Hourly/daily aggregation, retention, purge, cleanup |
| `realtime-service` | WebSocket fan-out to the dashboard |
| `schema-migrator` | Flyway runner — migrations run here, not in app services |

The other pieces live alongside this repository:

| Repository | What |
|---|---|
| `tracedown-core-frontend` | The Vue 3 dashboard |
| `tracedown-probe-agent` | The Python probe agent that executes Lace scripts |
| `tracedown-testbin` | Deterministic HTTP target for testing probes |

## Requirements

Docker with the Compose plugin. The Docker build context is the parent
directory and copies the sibling `lace/` repositories into the image build, so
clone the tree like this:

```bash
mkdir tracedown && cd tracedown
git clone https://github.com/tracedown/tracedown-core-backend core/tracedown-core-backend
git clone https://github.com/tracedown/lacelang-kotlin-validator lace/lacelang-kotlin-validator
git clone https://github.com/tracedown/lacelang-kotlin-executor lace/lacelang-kotlin-executor
git clone https://github.com/tracedown/kotlin-lacetest lace/kotlin-lacetest
```

(Building with Gradle outside Docker needs only this repository — the Lace
libraries resolve from Maven Central.)

## Running

```bash
cd core/tracedown-core-backend/docker
cp .env.example .env
docker compose up --build
```

The stack brings up Postgres, Redis, the migrator, the CA bootstrap, every
service. The gateway publishes on 127.0.0.1:20714 (a host web server exposes it —
see `docker/deploy/`). Then enrol an agent — nothing
probes without one:

```bash
./scripts/bootstrap-agent.sh
```

Full walkthrough: **[Quickstart](https://tracedown.dev/install/quickstart/)**.

## The monolith

For minimal installs there is `tracedown-monolith`: the entire platform in a
single jar. It is a deliberate trade — ease of deployment for the loss of the
microservice properties. One process to run, watch and restart instead of
eight, but no independent scaling (you can't add scheduler replicas or move
ingestion elsewhere), no isolation (one service's failure or memory pressure
is everyone's), and no rolling updates of a single piece. For a small install
probing a handful of services none of that matters, and the operational
simplicity wins; when it starts to matter, the per-service deployment is the
same code — point it at the same database and switch.

Every service runs in one JVM, the dashboard is served from the gateway port,
and probes execute on an embedded Lace executor — no agents to enrol (the
Agents UI is hidden accordingly, and with it the ability to probe from
multiple vantage points). It needs only Postgres and Redis, and migrates its
own schema on boot:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/tracedown \
DATABASE_USER=tracedown DATABASE_PASSWORD=... \
REDIS_A_URL=redis://localhost:6379 \
java -jar tracedown-monolith-<version>-all.jar
```

Grab the jar from the releases page (it ships with the frontend baked in), or
build it yourself — pass `-PmonolithFrontend=latest` (or a `vX.Y.Z` tag, or a
path to a frontend `dist.tar.gz`) to bundle the dashboard:

```bash
./gradlew :tracedown-monolith:fatJar -PmonolithFrontend=latest
```

The gateway CLI tools ride along (`java -jar ... --create-org`, etc.). Ports
follow the standard per-service defaults; `GATEWAY_PORT`, `METRICS_PORT` and
`REALTIME_PORT` override the exposed ones. Distributed agents remain available
in the per-service deployment — the monolith trades them for simplicity.

## Deploying

`docker/deploy/` runs the platform — backend and frontend — from published
GitHub release artifacts instead of building from source, with a full-scope
`.env.example` and host `nginx.conf`/`apache.conf` for the public side. See
its README.

> [!WARNING]
> `docker/.env.example` holds development secrets — a placeholder encryption
> key, a placeholder JWT secret and a known demo password. They make the stack
> run with zero setup and are unacceptable on a network. Read
> [Secrets & Encryption](https://tracedown.dev/admin/secrets/) before exposing
> this to anyone.

## Testing

```bash
./gradlew test        # unit + integration (Testcontainers)
./e2e/run.sh          # end-to-end against a full Docker stack
./test-all.sh         # both
```

The scheduler's integration test dispatches to a real agent container; build
its image once (from the monorepo root, with the sibling repos cloned):

```bash
docker build -f probe-scheduler/src/test/resources/Dockerfile.agent -t test-agent ../..
```

## Lace

Probes are written in Lace, an independently specified language with a public
grammar, a conformance suite, and implementations in Python, TypeScript and
Kotlin. It is Apache 2.0 licensed and not tied to this platform.

- Language docs: [lacelang.dev](https://lacelang.dev)
- Specification and conformance suite: [github.com/tracedown/lacelang](https://github.com/tracedown/lacelang)

## License

Open source under the Apache License 2.0. See `LICENSE`.
