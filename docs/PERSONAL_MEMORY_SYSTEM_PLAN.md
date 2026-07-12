# Personal memory system — build plan

## Context

This is a personal fork of screenpipe (`psyAlbertusVanDerWalt/screenpipe`),
not intended to track upstream. The goal is to repurpose screenpipe's
capture engine into a personal "AI memory" system for consulting work:
continuously capture activity (mic, desktop audio, OCR, accessibility text),
discard the raw data after a retention window, and keep a durable,
queryable record of what happened, was decided, and was discussed — so an AI
can answer things like "when did client X first mention the Azure
migration."

A feasibility investigation (see prior planning session, or ask Claude to
regenerate — findings summarized below) found that screenpipe already
provides most of the infrastructure this needs: headless capture, local
SQLite storage, a working retention system, a cron-capable "pipes" scheduler,
native Ollama + cloud LLM switching, a local REST API, and an MCP server.
The genuinely new work is: writing one nightly extraction pipe, and
optionally (v2) real semantic search and a simple entity/relationship
schema — not a five-service architecture.

**Constraints:** data should live on the home server (most storage there);
Ollama is the default LLM, with a cloud preset as a switchable alternative.

## Architecture (revised)

The original plan assumed v1 could run entirely on one machine. That
assumption broke once the home server was confirmed to be a headless
Coolify-managed box (no monitor, mic, or active desktop session) — and
screenpipe's capture (mic, desktop audio, OCR, accessibility tree) requires
a real desktop session with actual audio hardware. A Docker container on a
headless server has neither.

Actual v1 split:

- **Workstation** (the machine you physically work at) — runs
  `screenpipe-engine` natively (not containerized), does all capture, owns
  the local SQLite DB + media files, runs the nightly extraction pipe.
- **Coolify home server** — hosts Ollama (reachable over LAN for GPU
  inference from the workstation's pipe runs), and is the destination for
  long-term storage via screenpipe's existing SSH/SFTP directory-sync
  feature (`crates/screenpipe-connect/src/remote_sync.rs`) — no custom
  client/server protocol needs to be built for this.

This means the "multi-machine split" originally deferred to "not in scope
for now" is actually required from Phase 0, just via an existing sync
primitive rather than new capture/processing infrastructure.

Issue tracking: GitHub Issues are now enabled on this fork. Each phase below
has a corresponding issue (#1–#6) at
https://github.com/psyAlbertusVanDerWalt/screenpipe/issues — treat the
issues as the source of truth for task checkboxes; this doc is the narrative
context.

---

## Phase 0 — Engine bring-up on the workstation
**Estimate: ~half a day active work, budget up to a full day for friction**

The highest-uncertainty phase — not because the code is hard, but because
OS/hardware permission setup (mic access, screen recording permission,
first-run driver issues) tends to have a few unexpected snags regardless of
how the code is written.

- [x] Build `screenpipe-engine` on the workstation (`cargo build -p screenpipe-engine --release`)
- [x] Run it headless via CLI (`screenpipe record ...`, see `crates/screenpipe-engine/src/cli/mod.rs` for flags) — no Tauri app involved
- [x] Confirm mic capture, desktop audio capture, OCR, and accessibility-tree capture all actually produce data (check via `/search` API or `screenpipe status`)
- [ ] First transcription model download completes and local transcription works — default engine is Parakeet, not Whisper; weights were still downloading when this was checked, revisit before Phase 3
- [x] Confirm data lands under the workstation's local data dir (`~/.screenpipe/data`) with the amount of disk you expect

**Toolchain gaps found (none of this was installed on the workstation) — fixed via:**
- Rust itself: installed via `rustup` (project pins `1.93.1` via `rust-toolchain.toml`)
- MSVC C++ Build Tools + Windows SDK: installed via the VS Build Tools bootstrapper (`Microsoft.VisualStudio.Workload.VCTools`), required for linking — Visual Studio was present but without the C++ workload
- LLVM/`libclang`: required by `bindgen` for `whisper-rs-sys`; installed the portable `clang+llvm` archive (not the installer — it demanded UAC elevation with no interactive session to approve it) to `%USERPROFILE%\tools`, set `LIBCLANG_PATH`
- CMake: needed for `libsamplerate-sys`; already present under the VS Build Tools install (pulled in by `--includeRecommended`) but not on `PATH`
- `unzip`: needed by `screenpipe-audio`'s build script; found bundled with the existing Git for Windows install rather than installing a new one

**Verified via a live ~90s recording session:** OCR (Windows OCR, en-GB), accessibility tree walks, both audio devices (mic + desktop loopback via Windows process loopback), and UI event capture (clipboard, input, app focus) all produced real rows, confirmed via `screenpipe status` and the `search` CLI subcommand (reads SQLite directly, no API auth needed — the HTTP API requires a bearer token even from localhost in this build).

Note for Phase 1: OCR captures literally everything on screen, including code and any sensitive on-screen content — retention/redaction config matters before running this unattended long-term, not just for disk space.

## Phase 1 — Configure retention + sync to the home server
**Estimate: 1–2 hours**

Retention config is a pure config change (`crates/screenpipe-engine/src/retention.rs`).
Getting data onto the home server uses the existing SSH/SFTP directory-sync
feature (`crates/screenpipe-connect/src/remote_sync.rs`, `--enable-sync`),
which mirrors the whole `~/.screenpipe` data directory to a remote host on
an interval — this is what satisfies "storage lives on the home server."

**Ordering matters:** sync must run more frequently than local retention
deletes raw media, or data gets purged locally before it ever reaches the
server. Set the sync interval well inside the retention window (e.g. sync
every few hours if `retention_days` is 14+).

- [x] Set `retention_days` (21) and mode `media` (delete raw video/audio/screenshots, keep all text/transcripts)
- [x] Verify via `GET /retention/status` that it's enabled and picks up the config — confirmed `{"enabled":true,"mode":"media","retention_days":21}`
- [ ] Force a run against data old enough to actually delete something — not yet exercised (would need fabricated old timestamps; deferred until real data ages naturally)
- [x] Deploy a dedicated SFTP endpoint on the Coolify server (`screenpipe-sync-sftp` service, `atmoz/sftp` image) and use `screenpipe sync remote now` (SSH key auth, target path) — **not** the `--enable-sync` CLI flag, which is a separate screenpipe-cloud feature; the actual self-hosted mechanism is the `sync remote {test,now,discover}` subcommand family
- [x] Confirm a sync cycle actually lands data on the server — verified 26 files / 2.74MB transferred and landed correctly (audio chunks, config, `SCREENPIPE.md`)
- [x] Schedule recurring sync — Windows Scheduled Task `screenpipe-sync-to-homeserver`, daily at 18:00 (comfortably inside the 21-day retention window)

**What this actually required (more than a config change):**
- The SSH/SFTP sync target is a brand-new, purpose-built Coolify service, not the bare host — deployed `atmoz/sftp` (key-only auth, chrooted, dedicated keypair generated just for this link, not the personal SSH key) via `docker_compose_raw`, with a persistent volume for data.
- **Bug found in `atmoz/sftp`:** its own directory-auto-creation (`chown -R uid:users`) left the target directory `root`-owned rather than owned by the sftp user — first sync attempt silently transferred 0 files (write permission errors weren't surfaced; the CLI subcommand doesn't wire up tracing/logging, so nothing printed). Fixed with a small init container that `chmod 777`s the shared data volume before the sftp container starts.
- `$HOME` is unset in this Windows PowerShell/Task Scheduler environment (Windows convention is `USERPROFILE`) — the sync command silently found nothing without `--data-dir` passed explicitly. Always pass `--data-dir` explicitly on Windows; don't rely on the default.
- Git Bash mangles Unix-style absolute path arguments (`/screenpipe-data` → `C:/myprograms/Git/screenpipe-data`) when calling a native Windows exe — use PowerShell (or Task Scheduler, which isn't affected) for anything involving `--remote-path`.

**Known gap, not yet solved:** the SFTP container's SSH host key is **not persisted** across container restarts/redeploys. If Coolify ever recreates the container (redeploy, host reboot, crash), the host key changes and the next scheduled sync will fail with a host-key-verification error until someone runs `ssh-keygen -R "[10.0.0.69]:2222"` to clear the stale entry. An attempt to persist host keys via a custom entrypoint wrapper got tangled in compose/shell escaping and was reverted rather than risk leaving the service in a broken state. Worth fixing properly before relying on this long-term — otherwise a silent, unattended sync failure is likely eventually. Tracked in [issue #7](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/7).

**Reorganized (2026-07-11):** moved out of the "Infrastructure" project into a dedicated **"screenpipe"** Coolify project. Coolify's API doesn't support moving a service between projects, so this was a delete+recreate (Ollama had already been removed from Coolify by this point — see Phase 2 — so it wasn't affected). Lost the Phase 1 test-data volume in the process (not real capture data, no loss). Hit the known host-key-change gap immediately as expected — re-trusted once via `ssh-keygen -R`, confirmed connectivity and write access both still work. Current service uuid: `ljr8cr1uwjqb40ctmmpafjom`. The scheduled Windows task didn't need changes (same host/port/user/key-path/remote-path throughout).

## Phase 2 — Ollama + cloud fallback preset
**Estimate: 1–2 hours active — actual: closer to 1.5 hours, mostly lost to a stuck Coolify deploy**

**Plan changed mid-flight.** The original plan was to deploy Ollama on the
Coolify home server (GPU). That deploy got stuck for 25+ minutes in
`starting:unknown` with no usable logs available through the Coolify MCP
toolset (the `ollama/ollama` image bundles GPU libraries even for CPU-only
use, so it's a multi-GB pull — genuinely slow on this connection, or
something else was wrong; never got a definitive answer). Rather than keep
debugging blind, pivoted: **it turned out Ollama was already installed and
running natively on the workstation** (`10.0.0.30`, the same machine as the
capture engine), with a large existing model library already pulled —
including several **cloud-hosted models proxied through the same local
Ollama API** via an `ollama.com` account (`gemini-3-flash-preview`,
`glm-5:cloud`, `gpt-oss:120b-cloud`, etc.).

That local install directly satisfies "switch between local and cloud
models" — both go through `native-ollama` pointed at `localhost:11434`, no
separate Anthropic/OpenAI API key needed. The Coolify `ollama` service was
stopped (not deleted — parked in case GPU-backed server-side inference is
worth revisiting later) rather than left running/stuck.

- [x] ~~Deploy Ollama as a Coolify service~~ — superseded; used the existing native workstation install instead
- [x] Confirmed reachable and working: `curl http://localhost:11434/api/version` and a real chat completion both succeeded
- [x] Confirmed screenpipe's preset config supports arbitrary Ollama URLs — `native-ollama` just takes `--url`, nothing localhost-specific (`crates/screenpipe-engine/src/cli/presets.rs`)
- [x] Created two presets via `screenpipe pipe models create`:
  - `ollama-local` (default) — `native-ollama`, `http://localhost:11434/v1`, model `gemma3:4b`
  - `ollama-cloud` — `native-ollama`, `http://localhost:11434/v1`, model `gemini-3-flash-preview` (ollama.com-hosted)
- [x] Verified both end-to-end with real chat completions via the OpenAI-compatible `/v1/chat/completions` endpoint — both returned correct responses
- [x] Pipes select a preset via `preset:` in frontmatter (per `screenpipe pipe set-preset`), so switching per-pipe is already fully supported

## Phase 3 — Write and iterate the nightly extraction pipe
**Estimate: half a day to a day of active prompt work, spread over ~1 week of calendar time (needs real captured days to test against) — actual: ~1 session, but most of it went to two real bugs, not prompt writing**

This is the one piece of custom logic in v1 — everything around it
(scheduling, execution, API access, LLM selection) already exists. Based
on the existing `day-recap` / `meeting-summary` pipes in
`crates/screenpipe-core/assets/pipes/` as a starting pattern.

- [x] Drafted `pipe.md` at `docs/pipes/nightly-memory-extract/pipe.md` — `schedule: every day at 2am`, `permissions: writer` (read-everything + `/memories` write, nothing else), `timeout: 900`
- [x] Prompt: query `/activity-summary` + `/meetings` for the past 24h, then targeted `/search` (budget: 8 calls, limit 10) for decisions/action items/people/projects/tech
- [x] Prompt: dedup against existing `/memories` before writing — create, update in place, or skip
- [x] Prompt: write structured rows into `/memories` with a `type:` tag plus `project:`/`person:`/`topic:` tags (these tags already give tag-based cross-linking via `GET /search?tags=...&include_related=true` — a rudimentary graph today, ahead of the optional Phase 5 schema)
- [x] Ran it repeatedly against real captured data and iterated based on what broke — see findings below
- [x] **Validated end-to-end (2026-07-12):** a clean run against real captured activity produced 4 correct, well-tagged memories (SFTP reorg work, a code refactor, a 3D-print job, a gaming session with a named person) in 80 seconds. Confirms the pipe design, dedup logic, and tagging scheme all work as intended once the execution-layer bugs below were fixed.

**Bugs found and fixed (not just prompt issues):**

1. **`screenpipe pipe run <name>` never authenticated against the local API — every pipe run via this CLI path 403'd on its first call.** `crates/screenpipe-engine/src/cli/pipe.rs`'s `handle_pipe_command` builds a standalone `PipeManager` (by design — "does NOT require a running server") but never called `set_local_api_key`, so `SCREENPIPE_LOCAL_API_KEY` was never exported into the pipe's subprocess env, and the bash auto-auth shim (`crates/screenpipe-core/src/agents/bash_env.rs`) silently sent empty bearer tokens. This affected every pipe, not just this one. Fixed by resolving the key via `auth_key::find_api_auth_key()` and calling `manager.set_local_api_key(...)` before `load_pipes()`.
2. **`--append-system-prompt` buries a pipe's actual instructions after pi-agent's own generic "you are a coding assistant" preamble instead of replacing it.** This was the real cause of runs where the model seemed to receive no instructions at all — confirmed via direct instrumentation (on a scratch copy of the vendored dependency, not the real install) that the full instruction chain was correct end-to-end every time; the bug was architectural, not data loss. Fixed in `crates/screenpipe-core/src/agents/pi.rs`: switched to `--system-prompt` (full replace) for every provider except Anthropic, where the append behavior is kept intentionally for prompt-caching. See [issue #8](https://github.com/psyAlbertusVanDerWalt/screenpipe/issues/8) for the full investigation.
3. **`ollama-local` preset (`gemma3:4b`) can't run pipes at all** — no tool-calling support. Fixed by swapping to `qwen3:8b`, then discovered even 8b didn't reliably follow instructions post-fix (kept mistaking the pipe for an MCP tool) while `qwen3:14b` did, on the same real pipe.
4. **`ollama-cloud` preset had drifted to a local model** (`qwen3.5:9b`, a real 6.5GB download) instead of the actual cloud-hosted model. Fixed to point at `gemini-3-flash-preview` (the genuine ollama.com-hosted model set up in Phase 2).

**Current setup:** `nightly-memory-extract` uses the `ollama-cloud` preset (`gemini-3-flash-preview`), not local — chosen after the local models proved unreliable at instruction-following even with the architecture fix. This is a real privacy tradeoff (nightly activity now goes to a hosted model) — worth revisiting once a bigger local model is confirmed reliable, but the user explicitly chose to proceed with cloud for now to get the pipe actually working.

**Known remaining gap, not blocking:** intermittent bash-sandbox-to-localhost connectivity failures (`curl` exit 7, "couldn't connect") were observed during local-model testing but did not reproduce with the cloud model. Documented on issue #8 as a separate, unresolved issue if it recurs.

## Phase 4 — Live with it and judge usefulness
**Estimate: ~1 week calendar time, near-zero active effort**

This is the gate for whether Phase 5 is worth doing at all — no amount of
speed shortens it, since it depends on accumulating real daily use.

- [ ] Let capture + retention + nightly pipe run for a full work week untouched
- [ ] Each day, spot-check whether the `/memories` entries actually capture what mattered
- [ ] Decide: is keyword/full-text search over this data (already built, FTS5) good enough, or do you need semantic recall / structured relationships?

## Phase 5 (optional v2) — semantic search + entity/relationship schema
**Estimate: 2–4 days active dev — only start if Phase 4 justifies it**

Real new code, not config. Don't start this before validating Phase 3/4.

- [ ] Populate the currently-dormant `sqlite-vec` extension with real embeddings for memories/summaries (`crates/screenpipe-db/src/db/mod.rs` already loads the extension; the old embeddings table was dropped as dead code and would need to be reintroduced properly)
- [ ] Add simple SQLite tables for entities and relationships (not a graph DB — plain relational tables, per screenpipe's existing SQLite-everywhere pattern in `screenpipe-db`)
- [ ] Extend the nightly pipe (or add a second one) to populate these tables alongside `/memories`
- [ ] Wire semantic queries into the MCP server (`packages/screenpipe-mcp`) or a new query surface

---

## Not in scope for now

A custom client/server capture protocol (capture-only agent streaming
events live to a remote engine that owns the DB) is intentionally out of
scope. Screenpipe has no built-in mode for this, and it's not needed — the
SSH/SFTP directory-sync primitive (`crates/screenpipe-connect/src/remote_sync.rs`)
covers "get data onto the home server" well enough for a nightly/interval
sync, without inventing new infrastructure. Revisit only if interval sync
proves too coarse (e.g. you want near-real-time queries against server-side
data).
