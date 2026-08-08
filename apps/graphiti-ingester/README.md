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

## Still to do

- Extraction-result validation is specified and the ontology enums are in place, but the
  ingester currently posts episode bodies for graphiti-mcp to extract from, so the
  entity/edge check has no local extraction output to run against yet. Wire it in when
  extraction moves client-side, or use it to audit what came back.
- Dangling-edge rejection, same reason.
- Dockerfile and the CI-to-Coolify pipeline.
- Depends on [#16](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/16) for real
  data to land in `INGEST_EXPORT_DIR`.
