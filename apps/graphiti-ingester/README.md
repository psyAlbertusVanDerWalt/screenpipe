# graphiti-ingester

Reads screenpipe's redacted JSONL export, groups the rows into episodes, and posts them
into the Graphiti knowledge graph.

Phase 4 of the graphiti-kg build-out — [issue #17](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/17).

## Where it sits

```
workstation                          server (Coolify)
-----------                          ----------------
screenpipe capture
  └─ screenpipe-export (#15)
       └─ ~/.screenpipe/export/redacted-jsonl/YYYY-MM-DD.jsonl
             └── pulled by #16 ──────▶ /data/redacted-jsonl
                                          └─ THIS SERVICE
                                               └─ graphiti-mcp ──▶ FalkorDB
```

This service must only ever be pointed at the **redacted** export. The pipeline's whole
privacy guarantee is that unredacted capture text never leaves the workstation, and nothing
here can tell redacted from raw by inspection.

## Design notes

**Episodes, not rows.** A day's export is a few hundred rows but only a handful of
episodes — a real run over `2026-08-07.jsonl` turned 253 rows into 5 episodes. Since each
episode costs one extraction, row-level ingestion would have cost ~50x more and produced a
graph full of fragments. Grouping uses screenpipe's `SemanticKind` backbone and is plain
deterministic code, so no model is spent deciding what belongs together.

**Validate before posting.** Entity and edge types are checked against `EntityType` /
`EdgeType`, which mirror the deployed 13-entity/18-edge ontology. Models invent
out-of-enum types under load; one measured run against a large cloud model produced roughly
ten types that were never in the ontology.

**Verify after posting.** `add_memory` returns as soon as an episode is *queued*, not when
it is processed. A live 10-episode test against this deployment measured a **40% silent-drop
rate** — the extraction model returns a null where Graphiti's schema requires a string, the
background queue logs it, and the episode never appears, while the caller sees a perfectly
normal response. Every post is therefore confirmed by looking the episode up again, and
episodes that vanish are recorded as `DROPPED` rather than counted as success. See
[issue #20](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/20).

**The ledger is the cursor.** `ingested_episode` holds one row per episode ever attempted,
keyed on a deterministic `episode_key`. A re-run of an already-processed file is a no-op, and
a partly-failed batch resumes at exactly the episodes that still need work. A file-offset
cursor would only answer "how far did we get", which is the wrong question when a post can be
accepted and then silently dropped.

## Building

**Requires JDK 21.** Lombok cannot parse a newer JDK's AST — building under JDK 26 fails
with `java.lang.ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN`,
which says nothing about its cause. If your `JAVA_HOME` points elsewhere:

```powershell
$env:JAVA_HOME = "C:\myprograms\jdk-21.0.6"
mvn test
```

```bash
mvn test        # 15 tests
mvn spring-boot:run
```

## Configuration

Every value is env-overridable; nothing is hardcoded to one machine.

| Variable | Default | Notes |
|---|---|---|
| `INGEST_EXPORT_DIR` | `/data/redacted-jsonl` | Where #16 drops the pulled files |
| `INGEST_GROUP_ID` | `screenpipe` | Graphiti partition |
| `INGEST_DAY_BOUNDARY_ZONE` | `Africa/Johannesburg` | Decides which local day a row belongs to |
| `INGEST_SCHEDULE_CRON` | `0 0 10,15 * * MON-FRI` | Must stay inside working hours — see below |
| `GRAPHITI_MCP_URL` | `http://10.0.0.69:18000/mcp/` | LAN-only |
| `GRAPHITI_REQUEST_TIMEOUT` | `5m` | Sized for the slow case, not the median |
| `GRAPHITI_VERIFY_AFTER_POST` | `true` | Turn off only if the drop rate is ever fixed upstream |
| `DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/graphiti_ingester` | |

**The schedule must land inside working hours.** Both the export files and the Ollama
instance behind graphiti-mcp live on the workstation. A 03:00 run finds an asleep machine and
burns its whole attempt budget on connection failures.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/ingest/runs` | Force a run now. Synchronous and slow — maintenance only. |
| `GET` | `/api/v1/ingest/report` | Ledger totals, including the observed drop rate. |
| `GET` | `/actuator/health` | Liveness/readiness. |

## Deployment

Push to `main` → GitHub Actions runs the tests, builds the image, pushes it to the
self-hosted registry, then calls Coolify's deploy webhook.
Workflow: [`.github/workflows/deploy-graphiti-ingester.yml`](../../.github/workflows/deploy-graphiti-ingester.yml).

The workflow is **path-filtered** to `apps/graphiti-ingester/**`. This is a Java service
inside a Rust/Tauri monorepo, so without the filter every unrelated screenpipe commit would
rebuild and redeploy it. Use the *Run workflow* button to force a build.

### Local image build

```bash
cd apps/graphiti-ingester
docker build -t graphiti-ingester:local .
```

Maven comes from the `maven:3.9-eclipse-temurin-21` base image rather than `./mvnw`. The
wrapper is kept for local CLI use, but in the image it only adds a Maven download plus an
`unzip` dependency the JRE image doesn't ship, for no reproducibility gain over a pinned tag.

### Required GitHub configuration

Variables (Settings → Secrets and variables → Actions → Variables):

| Name | Example |
|---|---|
| `REGISTRY_URL` | `registry.albertusvdw.co.za` (no scheme, no trailing slash) |
| `REGISTRY_USERNAME` | registry user |
| `COOLIFY_URL` | `https://coolify.albertusvdw.co.za` (no trailing slash) |

Secrets:

| Name | Notes |
|---|---|
| `REGISTRY_PASSWORD` | registry password |
| `COOLIFY_API_TOKEN` | Coolify → Keys & Tokens → API tokens. **Deploy** permission is enough. |
| `COOLIFY_APP_UUID` | from the app's URL in the Coolify dashboard |

### Coolify application setup

1. **Type**: Docker Image
2. **Image**: `registry.albertusvdw.co.za/graphiti-ingester` — no `:latest`, Coolify appends
   the tag itself
3. **Exposed port**: `8080`. Must match the container exactly; a wrong value here resolves
   but fails every request silently.
4. **Port mappings**: leave empty — Traefik routes over the Docker network
5. **No public FQDN.** This service has no reason to be reachable from the internet: its only
   HTTP surface is a maintenance trigger and a stats endpoint, both unauthenticated. Keep it
   LAN-internal, consistent with graphiti-mcp itself. Note that Coolify 4.1.2 cannot actually
   remove an auto-assigned FQDN once created (fork issue #12), so avoid creating one rather
   than planning to delete it.
6. **Volume**: mount the pulled export at `/data/redacted-jsonl`, matching where #16 lands it.
7. Create the PostgreSQL 16 database **in the same Coolify project** so the two share a
   network and can talk by internal hostname.

Coolify hands out a `postgres://user:pass@host:5432/db` connection string. JDBC needs a
different scheme — convert it by hand:

```
# Coolify gives you
postgres://graphiti:PASSWORD@internal-hostname:5432/graphiti_ingester

# set as
DATASOURCE_URL=jdbc:postgresql://internal-hostname:5432/graphiti_ingester
DATASOURCE_USERNAME=graphiti
DATASOURCE_PASSWORD=PASSWORD
```

Never point it at `localhost` — inside a container that resolves to the container itself.
`SPRING_DATASOURCE_URL` also works via relaxed binding if you prefer the Spring-native name.

Schema is managed by Flyway, not Liquibase, and `V1__create_ingested_episode.sql` builds
everything from scratch, so a fresh database needs no special handling. `ddl-auto` is
`validate`, so a drift between the entity and the migration fails at startup rather than
silently reshaping the table.

## Verified

Against a real export on the workstation, in the built container talking to a real Postgres:

- Image builds; runs as non-root (`uid=1001 appuser`); healthcheck reports healthy
- Flyway applies `V1`, both indexes created, `ddl-auto: validate` passes
- `2026-08-07.jsonl` → 253 rows → **5 episodes**, no orphans, no key collisions
- With graphiti-mcp unreachable, all 5 fail cleanly and are recorded as `FAILED` — the run
  itself still completes
- Re-running retries the same 5 and increments `attempts` rather than creating duplicates,
  confirming the episode key is genuinely stable

## Still to do

- Extraction-result validation is specified and the ontology enums are in place, but the
  ingester currently posts episode bodies for graphiti-mcp to extract from, so the
  entity/edge check has no local extraction output to run against yet. Wire it in when
  extraction moves client-side, or use it to audit what came back.
- Dangling-edge rejection, same reason.
- Never yet run against the **live** graphiti-mcp endpoint — every test so far deliberately
  pointed at a dead port to avoid writing into the real graph.
- Depends on [#16](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/16) for real
  data to land in `INGEST_EXPORT_DIR`.
