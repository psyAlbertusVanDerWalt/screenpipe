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

**Work and personal never share a partition.** Every episode gets a deterministic
`ActivityDomain` (`WORK`, `PERSONAL`, or `UNCLASSIFIED`) from `ActivityDomainClassifier` —
keyword/domain matching against the app name, window title, and (when the row came from a
browser tab) the URL's domain, the strongest signal available since it's the one thing kept
through redaction. This is deliberately not an LLM call: the whole point is a timesheet or a
"what did I do yesterday" summary that can't mix the two, and the extraction model's own JSON
has been measured malformed often enough in this deployment that it isn't trusted with that
decision. Anything that doesn't clearly match either side lands in its own `UNCLASSIFIED`
partition rather than being guessed — see `IngestProperties.groupIdFor`.

**Episodes, not rows.** A day's export is a few hundred rows but only a handful of
episodes — a real run over `2026-08-07.jsonl` turned 253 rows into 5 episodes. Since each
episode costs one extraction, row-level ingestion would have cost ~50x more and produced a
graph full of fragments. Grouping uses screenpipe's `SemanticKind` backbone and is plain
deterministic code, so no model is spent deciding what belongs together.

**Validate before posting.** Entity and edge types are checked against `EntityType` /
`EdgeType`, which mirror the deployed 13-entity/18-edge ontology. Models invent
out-of-enum types under load; one measured run against a large cloud model produced roughly
ten types that were never in the ontology.

**Episode names come from the window, not the title.** On non-message rows the `title` field
carries the accessibility element's *role*. Over a real day every distinct non-message title
was one of `text` (31), `button` (3), `edit` (2), `Default` (2) — so naming from it produced
episodes called "text" and "button". The window title is what describes the activity
("Inbox (109) - Gmail", "Email - Outlook"), with the trailing app segment trimmed.

**Bodies are deduplicated across the whole episode.** The same on-screen element is
re-captured every frame: measured 31 rows collapsing to 2 distinct lines, and a terminal
episode that was the words "Command Prompt" repeated 32 times. Beyond wasting extraction
tokens, that repetition padded contentless episodes past the minimum-length filter meant to
drop them.

**Verify after posting.** `add_memory` returns as soon as an episode is *queued*, not when
it is processed. A live 10-episode test against this deployment measured a **40% silent-drop
rate** — the extraction model returns a null where Graphiti's schema requires a string, the
background queue logs it, and the episode never appears, while the caller sees a perfectly
normal response. Every post is therefore confirmed by looking the episode up again, and
episodes that vanish are recorded as `DROPPED` rather than counted as success. See
[issue #20](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/20).

**The verify budget must exceed extraction time**, or the service declares its own successes
dropped. At 3 × 20s it reported a 100% drop rate while an episode landed in the graph 27
seconds after it had given up. Extraction measures between 18s and over 180s, so the budget
is 10 × 30s. And because `DROPPED` is non-terminal, a re-run used to re-post episodes that had
actually landed — adding a duplicate copy each time, so retrying created the very thing it
was retrying for. An already-posted episode is now checked for presence before being sent
again.

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
| `INGEST_WORK_GROUP_ID` | `screenpipe-work` | Graphiti partition for work activity |
| `INGEST_PERSONAL_GROUP_ID` | `screenpipe-personal` | Graphiti partition for personal activity |
| `INGEST_UNCLASSIFIED_GROUP_ID` | `screenpipe-unclassified` | Partition for anything the classifier isn't confident about — never guessed into work or personal |
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

### Deployed identifiers

Live as of 2026-08-08, in Coolify project `screenpipe` / environment `production`:

| Resource | UUID | Notes |
|---|---|---|
| Application | `k98clse3y97ecglnqtnhrhdq` | `fqdn: null` — LAN-only, deliberately |
| PostgreSQL 16 | `y2dnm3lqljc14ivy1wyrz0s6` | the uuid is also its internal hostname |
| Volume | `uu97nyp54377a28ev1m0bhkk` | `/data/redacted-jsonl` |
| Pull task | `hwlqr5zil6droewmjhvxii51` | `0 9,14 * * 1-5` |

Creating the app via the API auto-assigns a public `*.sslip.io` FQDN, which would have put
the unauthenticated ingest trigger on the internet. Clearing it with an `fqdn: ""` update
works — worth noting because fork issue #12 records an FQDN as unremovable, but that applies
to *service sub-apps*, not applications.

Coolify's bulk env update writes each variable into both the production and preview scopes.
This app has no preview deployments, so the preview copies are inert.

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

## The push from the workstation (#16)

The workstation pushes over HTTP; the server never reaches back. `screenpipe-export` does
the `POST` itself, in the same process that just wrote the day files:

```
workstation                                        server
  screenpipe-export                                  POST /api/v1/export/uploads/{file}
    write <data-dir>/export/redacted-jsonl/  ──────► bearer token, application/x-ndjson
    then push every recent *.jsonl                     │
                                                       ▼
                                              /data/redacted-jsonl  (volume)
                                                       │
                                                       ▼
                                                 the ingester
```

The workstation needs no inbound anything — no SSH service, no extra local account, no NTFS
carve-out, no firewall opening. One outbound call on the LAN.

The push lives in [`crates/screenpipe-engine/src/export/upload.rs`](../../crates/screenpipe-engine/src/export/upload.rs)
rather than a second scheduled script, for two reasons. A separate scheduler entry flashes a
console window on the desktop every time it fires, and two schedules can disagree about which
files exist — the process that wrote the files always knows.

### Workstation setup — no elevation

`<data-dir>/export.toml`:

```toml
[upload]
url = "http://10.0.0.69:18080"
```

and the shared secret in `<data-dir>/.upload-token`. Both halves are required: a URL with no
resolvable token logs a warning naming the file and pushes nothing, rather than sending a
request that can only 401. `INGEST_UPLOAD_TOKEN` overrides the file for one-off runs.

Other keys, all optional: `token_file` (default `.upload-token`, relative to the data dir),
`since_days` (default 7, `0` for everything), `max_attempts` (3), `request_timeout_secs` (120).

Then one scheduled task, for the export itself:

| Field | Value |
|---|---|
| Command | `screenpipe-export.exe --data-dir C:\Users\shortie\.screenpipe` |
| Frequency | daily |

Pass `--data-dir` explicitly. `$HOME` is unset under Task Scheduler on Windows, and the
default data-dir lookup silently finds nothing without it.

Re-sending recent days is deliberate, not wasteful: a day's file keeps growing until that day
ends, and the server replacing it is how the final version arrives. The ledger is keyed on a
deterministic episode key, so the same content arriving twice is a no-op.

A failed push does not fail the export — the cursor has already advanced and the files on
disk are the durable record — but `screenpipe-export` exits non-zero so the scheduler's
last-result column shows it. The next run retries every file inside the `since_days` window,
so a transient outage self-heals.

### Retiring the old transports

`screenpipe-sync-sftp` (Coolify service `ljr8cr1uwjqb40ctmmpafjom`) and the disabled
`screenpipe-sync-to-homeserver` scheduled task are an older push direction, made redundant by
this one. Remove them once a push has landed.

The SSH pull that this replaced got as far as installing OpenSSH Server on the workstation.
[`scripts/teardown-workstation-ssh.ps1`](scripts/teardown-workstation-ssh.ps1), run elevated,
removes the account, the firewall rule, the ACEs and the `sshd_config` edits.

## Verified

Against a real export on the workstation, in the built container talking to a real Postgres:

- Image builds; runs as non-root (`uid=1001 appuser`); healthcheck reports healthy
- Flyway applies `V1`, both indexes created, `ddl-auto: validate` passes
- `2026-08-07.jsonl` → 253 rows → **5 episodes**, no orphans, no key collisions
- With graphiti-mcp unreachable, all 5 fail cleanly and are recorded as `FAILED` — the run
  itself still completes
- Re-running retries the same 5 and increments `attempts` rather than creating duplicates,
  confirming the episode key is genuinely stable

The push is covered by unit tests in `export::upload`:

- A missing `url`, and a `url` with no resolvable token, both push nothing — the second is
  the one that matters, since it would otherwise send a request that can only 401
- The token is read from the data dir's `.upload-token` and trimmed
- A trailing slash on the URL does not double up in the request path
- Only `*.jsonl` **files** are eligible — a directory named `*.jsonl` is not
- A missing output directory is not an error

### Verified in production

- The full pipeline ran green: test → build → push to `registry.albertusvdw.co.za` → Coolify
  deploy webhook
- The app is `running:healthy` with `fqdn: null` — Flyway applied `V1` against the real
  Postgres and the readiness probe passes

## Still to do

- Extraction-result validation is specified and the ontology enums are in place, but the
  ingester currently posts episode bodies for graphiti-mcp to extract from, so the
  entity/edge check has no local extraction output to run against yet. Wire it in when
  extraction moves client-side, or use it to audit what came back.
- Dangling-edge rejection, same reason.
- The in-process push has not yet run end-to-end against the live ingester from a scheduled
  `screenpipe-export` run — only the endpoint side is verified.

## Known hazard: restarting graphiti-kg rebuilds graphiti-mcp

The `graphiti-kg` Coolify service has no `image:` for `graphiti-mcp` — it builds from
`https://github.com/getzep/graphiti.git#main:mcp_server`. A restart is therefore not a
restart: Coolify logs `graphiti-mcp Skipped No image to be pulled` and rebuilds from source,
and the container simply does not exist until that finishes.

What makes it expensive is the `uv sync --extra providers` layer, which resolves to torch and
the CUDA stack — **~2.3 GB** of wheels (torch 502 MB, cuBLAS 403 MB, cuDNN 349 MB, cuFFT
204 MB, NCCL 196 MB, cuSOLVER 192 MB, Triton 189 MB, cuSPARSELt 162 MB, cuSPARSE 139 MB) on a
server with no GPU. Whenever the uv cache mount misses, that download stands between the
graph and being up, and the endpoint returns nothing the whole time.

Three ways out, in rough order of preference:

1. **Drop `--extra providers`** from the inline Dockerfile's `uv sync`. That extra is what
   drags in torch; this deployment talks to Ollama over the OpenAI-compatible API, which is
   in the base dependencies. Keeps graphiti-core 0.29.3, and the build becomes small enough
   that rebuilding on restart stops mattering.
2. **Build once and push to `registry.albertusvdw.co.za`** (already in use for the ingester),
   then point the compose at that tag. Restarts become pull-and-run, and the running version
   is exactly the one that was tested.
3. **Pin to `zepai/knowledge-graph-mcp:1.0.2-standalone`.** Simplest, but it is a downgrade:
   the sibling tag `1.0.2-graphiti-0.28.2-standalone` shows it ships graphiti-core **0.28.2**
   against the **0.29.3** this service builds. Custom entity/edge types are exactly the area
   that moved between those, so don't take this one without checking the ontology still binds.

The `redis<8.1.0` cap the inline Dockerfile applies is not a concern for the published image:
it was pushed 2026-03-11 and redis 8.1.0 only appeared 2026-07-30, so the resolver could not
have picked it.

Note also the orphaned `graphiti` sub-application (id 121, last online 2026-08-05): it is not
in the current compose, so it can never start, and with `exclude_from_status: false` it holds
the whole service short of `running:healthy` no matter what the real containers do.
