---
name: screenpipe-issue-board
description: Where this project's work is tracked and what the phase numbering means. Use whenever the user asks "what's on the board", "what's left to do", "pick up the next issue", "what were we working on", or refers to an issue by number (#15, #17) or by phase ("Phase 4", "the Spring Boot one"). Also use before opening, closing, or commenting on any issue for this repo.
tools:
  - Bash
  - Read
  - Grep
---

# screenpipe issue board

## Where the board is

**`https://github.com/psyAlbertusVanDerWalt/screenpipe/issues`** — the user's
own fork, `origin`. This is the only board for this work.

```bash
gh issue list --repo psyAlbertusVanDerWalt/screenpipe --state open
```

Or via the GitHub MCP tools with `owner: psyAlbertusVanDerWalt`, `repo: screenpipe`.

**Never touch `screenpipe/screenpipe` (upstream).** Don't open issues there,
don't PR there, don't comment there. `upstream` exists as a git remote for
pulling only. The user has stated this explicitly.

## The board tracks two different projects

Don't assume every issue is screenpipe Rust code:

1. **screenpipe itself** — the Rust/Tauri capture app in this repo
   (e.g. #13 single-instance fratricide, #14 MSVC link.exe PATH).
2. **graphiti-kg** — a separate personal knowledge-graph project (Graphiti +
   FalkorDB) deployed **only on Coolify, with no local repo**. Most of the
   numbered "Phase N" issues belong to this one. Its issues live here purely
   because it's the same personal tracker.

## The graphiti-kg phase chain

Data flows workstation → server → graph → query. Each phase depends on the
one before it, so read the chain before picking anything up.

| Issue | Phase | What | Where it runs |
|---|---|---|---|
| #15 | 1 | Redacted JSONL export pipeline | Workstation (Rust, this repo) |
| #16 | 2 | Server-side pull from workstation | Coolify + Windows OpenSSH |
| #17 | 4 | **Spring Boot ingester** — episode grouping, entity/edge validation | Coolify (separate repo, not yet created) |
| #18 | 5 | Register Graphiti MCP with Claude — **closed, done out of order** | Local MCP config |
| #19 | 6 | Grafana CRM dashboards (optional) | Coolify |

**#17 is the Spring Boot one.** If the user says "that Spring Boot project",
they mean this issue — not any of the Java repos under `C:\dev` (staff-engagement,
psybergate-initialiser, etc., which are unrelated client/Psybergate work).

There is no Phase 3 issue — the numbering comes from a plan doc, not a
contiguous issue range.

## Ground truth beats issue state

Issues on this board are not reliably closed when the work lands. **Verify
before believing an open issue is unstarted:**

- **#15 is functionally complete and live** despite being open — built as
  `screenpipe-export.exe` from `crates/screenpipe-engine/src/export/`, run
  daily at 23:00 by Windows Task Scheduler task `screenpipe-export-daily`,
  writing to `~/.screenpipe/export/redacted-jsonl/YYYY-MM-DD.jsonl`.

```powershell
Get-ScheduledTaskInfo -TaskName screenpipe-export-daily
Get-ChildItem "$env:USERPROFILE\.screenpipe\export\redacted-jsonl"
```

- The #15 source was still **untracked in git** as of 2026-08-08 (`git status`
  showed `?? crates/screenpipe-engine/src/export/`). Check `git status` before
  assuming committed work exists in history.

Closed issues carry real decisions in their comments (#9-#12, #20 especially).
Read the closed ones before re-litigating an ontology, model, or Coolify choice.

## Repo traps that cost real time

**`.gitignore` has a bare `data/` rule** (for screenpipe's capture directories) that
also matches any source package path containing `data/` — it silently swallowed
the whole `core/data/` tree of the Java ingester, and `git add` reported nothing.
After adding files under a new `data/` directory, always confirm with
`git status` that they were actually staged, or `git check-ignore -v <file>`.

**Java code here needs JDK 21, not the machine default.** `JAVA_HOME` points at
Temurin 26 while `java` on PATH is 21. Lombok cannot parse a JDK 26 AST and fails
with `ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag :: UNKNOWN`,
which names nothing relevant. Build with:

```powershell
$env:JAVA_HOME = "C:\myprograms\jdk-21.0.6"; mvn test
```

**Pushing is blocked** for the agent by the permission classifier — commits land
locally but the user must run `git push origin main` themselves.

## Related memories

`graphiti-kg-project`, `screenpipe-export-pipeline`, `never-pr-upstream-screenpipe`
in the auto-memory directory hold the deployment specifics (service UUIDs,
ontology location, model reliability findings) that these issues reference but
don't restate.
