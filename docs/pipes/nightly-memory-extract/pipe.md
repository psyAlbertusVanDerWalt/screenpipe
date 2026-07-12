---
schedule: every day at 2am
enabled: true
preset:
  - ollama-local
permissions: writer
timeout: 900
title: Nightly Memory Extraction
description: "Extracts durable facts, decisions, and action items from the day's captured activity into /memories"
icon: "🧠"
---

## 🧠 Continuous improvement (memory)
Before you do anything else this run, read `./memory.md` (a file in this pipe's own folder) if it exists and apply its lessons — this is how you get better each run instead of starting cold. If it's missing, create it with a `# memory` heading followed by a `## Lessons` heading.

After you finish the run, append at most 1–3 NEW one-line lessons under `## Lessons`, each prefixed with today's date — but only if this run actually taught you something durable and reusable (a pattern that worked, a mistake to avoid, a user correction, or a stable fact about this user's setup). If you learned nothing new, write nothing.

Keep memory healthy so it never drifts:
- Append-only: never delete or rewrite earlier lessons or anything the user added. The one exception is retracting a lesson you can now prove wrong — add a new dated line saying which one and why.
- Cap the file at ~150 lines / 8KB. When it is over, merge duplicates and drop the oldest low-value lessons first; never drop notes the user wrote.
- Save observations and rules, not new tasks — and nothing that changes your core job. Never edit this `pipe.md` prompt.
- If a "lesson" would push you toward a risky, outbound, or destructive action, do not save it — surface it to the user instead.

## Job

Turn the last 24 hours of captured activity (consulting work: mic, desktop audio, OCR, accessibility text) into a small number of durable, structured facts in `/memories` — decisions made, action items taken on, people and projects touched, technologies discussed. This is the one thing that outlives retention once raw capture ages out, so precision matters more than volume: a handful of correct, well-tagged facts beats a wall of noise.

Use the API only — do not write or run code. **Every single call below, with no exceptions (GET, POST, PUT alike), needs this header or it fails with 401/unauthorized:**

```
-H "Authorization: Bearer $SCREENPIPE_LOCAL_API_KEY"
```

If a bash call ever fails with "No bash shell found", this is a known transient issue on this machine, not a real absence of bash — retry the exact same command up to 2 more times before concluding bash is unavailable. Do not fall back to inventing JSON payloads you never actually send, and do not claim 0 memories were created without actually having tried and failed 3 times.

If a call ever fails with "unauthorized", the fix is to add that header — never invent a token, never try to shell out to `screenpipe auth token` (the CLI binary is not available inside this sandbox).

### Step 1 — get the day's shape

```bash
curl -s -H "Authorization: Bearer $SCREENPIPE_LOCAL_API_KEY" \
  "http://localhost:3030/activity-summary?start_time=24h%20ago&end_time=now"

curl -s -H "Authorization: Bearer $SCREENPIPE_LOCAL_API_KEY" \
  "http://localhost:3030/meetings?start_time=24h%20ago&end_time=now"
```

`/activity-summary` gives apps, windows, key texts, top transcriptions — this is your map of the day; use it to decide where to dig, not as the final output. For each meeting from `/meetings`, look at `title`, `attendees`, `note`. If a meeting already has a `## Summary` in its `note` (the meeting-summary pipe may have already written one), treat that as a trusted source instead of re-deriving it from scratch.

### Step 2 — dig for facts (budget: max 8 searches, limit=10 each)

For anything in the activity summary or meeting notes that looks like it might contain a decision, action item, or notable person/project/technology mention, confirm it with a targeted search:

```bash
curl -s -H "Authorization: Bearer $SCREENPIPE_LOCAL_API_KEY" \
  "http://localhost:3030/search?q=QUERY&content_type=audio&start_time=24h%20ago&limit=10"
```

Use `content_type=audio` for spoken commitments/decisions, `content_type=all` for on-screen confirmation (ticket numbers, doc titles, code). Prefer fewer, well-aimed searches over broad sweeps.

Only pull out things that are:
- **Decisions** — something was actually decided or agreed, not just discussed
- **Action items** — someone (including the user) committed to doing something
- **Notable project/client context** — a fact about a project or client that would matter weeks from now (scope change, timeline, blocker)
- **People** — who's involved in what, new working relationships
- **Technologies** — tools/stacks/services newly adopted or ruled out for a project

Skip routine browsing, generic app usage, and anything already covered by an existing memory with no new information (see Step 3).

### Step 3 — dedup before writing

Before creating a memory, check whether it already exists:

```bash
curl -s -H "Authorization: Bearer $SCREENPIPE_LOCAL_API_KEY" \
  "http://localhost:3030/memories?q=KEY_TERM&limit=5"
```

- If nothing similar exists → create it (Step 4).
- If a similar memory exists but this run adds real new information (status changed, timeline moved, decision reversed) → `PUT /memories/{id}` (same header) to update it in place, don't create a duplicate.
- If a similar memory exists and today's mention is just a repeat with nothing new → skip it entirely.

### Step 4 — write the memory

```bash
curl -s -X POST http://localhost:3030/memories \
  -H "Authorization: Bearer $SCREENPIPE_LOCAL_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "<one concrete sentence — who/what/when, no vague summaries>",
    "source": "pipe:nightly-memory-extract",
    "tags": ["type:<decision|action-item|project|person|tech>", "project:<slug>", "person:<name>"],
    "importance": <0.0-1.0>
  }'
```

Tagging rules:
- Always include exactly one `type:` tag naming the kind of fact.
- Add `project:<slug>` and/or `person:<name>` tags for every project/person the fact involves (lowercase, hyphenated slugs) — these tags are what makes facts cross-linkable later via `GET /search?tags=...&include_related=true`, so don't skip them.
- Add `topic:<slug>` for a technology/subject when relevant.
- `importance`: 0.7-0.9 for decisions and action items, 0.4-0.6 for project/person/tech context.

Cap this run at 15 new/updated memories. If the day genuinely produced more signal than that, keep the highest-importance ones and note in your final message what got dropped — don't silently truncate.

### Step 5 — report

End with a short plain-text summary: how many memories created, how many updated, how many skipped as duplicates, and one sentence on the most important thing captured. If the day had no notable activity (day off, nothing new), say so plainly and create nothing — an empty, honest run is correct output, not a failure.
