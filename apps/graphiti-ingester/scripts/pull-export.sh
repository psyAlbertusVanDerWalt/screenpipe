#!/bin/sh
# Pulls the redacted JSONL export from the workstation into this container's volume,
# for the ingester to pick up on its next run (fork issue #16).
#
# Runs as a Coolify scheduled task. It lives in the image rather than in the task's own
# command field because Coolify stores that field in a varchar(255) and rejects anything
# longer with a bodyless HTTP 500 — a limit that is very easy to trip and hard to diagnose.
#
# Server-initiated pull, not a workstation push: the server decides when data moves, so a
# workstation that was asleep at the scheduled minute simply gets collected on the next run
# instead of losing its window.

set -eu

SSH_HOST="${PULL_SSH_HOST:-10.0.0.30}"
SSH_PORT="${PULL_SSH_PORT:-22}"
SSH_USER="${PULL_SSH_USER:-graphiti-pull}"
SSH_KEY="${PULL_SSH_KEY:-/keys/id_ed25519}"
KNOWN_HOSTS="${PULL_KNOWN_HOSTS:-/keys/known_hosts}"
# Win32-OpenSSH's sftp server presents drive letters as /C:/... — not a typo.
REMOTE_DIR="${PULL_REMOTE_DIR:-/C:/Users/shortie/.screenpipe/export/redacted-jsonl}"
LOCAL_DIR="${INGEST_EXPORT_DIR:-/data/redacted-jsonl}"

log() { echo "[pull-export] $*"; }

if [ ! -f "$SSH_KEY" ]; then
    log "FATAL: no private key at $SSH_KEY — mount it as a Coolify file storage"
    exit 1
fi

# sftp refuses a key readable by anyone else. Coolify file mounts do not guarantee the mode,
# so fix it here rather than depending on how the mount happened to land.
chmod 600 "$SSH_KEY" 2>/dev/null || true

if [ ! -f "$KNOWN_HOSTS" ]; then
    log "FATAL: no known_hosts at $KNOWN_HOSTS"
    log "Pin the workstation's host key rather than trusting first contact:"
    log "  ssh-keyscan -t ed25519 -p $SSH_PORT $SSH_HOST > $KNOWN_HOSTS"
    log "then check it against the fingerprint printed by setup-workstation-ssh.ps1"
    exit 1
fi

mkdir -p "$LOCAL_DIR"

before=$(find "$LOCAL_DIR" -name '*.jsonl' 2>/dev/null | wc -l)
log "pulling ${SSH_USER}@${SSH_HOST}:${REMOTE_DIR} -> ${LOCAL_DIR} (${before} file(s) already local)"

# StrictHostKeyChecking=yes, never accept-new: this link carries a full record of the day's
# work, and silently trusting a new host key is how that ends up somewhere else.
# BatchMode stops it hanging forever on a prompt inside a cron job.
if ! sftp -q \
        -P "$SSH_PORT" \
        -i "$SSH_KEY" \
        -o "UserKnownHostsFile=$KNOWN_HOSTS" \
        -o StrictHostKeyChecking=yes \
        -o BatchMode=yes \
        -o ConnectTimeout=15 \
        -o ServerAliveInterval=30 \
        -b - "${SSH_USER}@${SSH_HOST}" <<SFTP_COMMANDS
cd ${REMOTE_DIR}
lcd ${LOCAL_DIR}
get -a *.jsonl
bye
SFTP_COMMANDS
then
    # Expected outside working hours — the workstation is asleep. The next run collects
    # whatever was missed, so this is a warning, not a failure worth alerting on.
    log "pull failed — workstation unreachable or refused. Next run will retry."
    exit 1
fi

after=$(find "$LOCAL_DIR" -name '*.jsonl' 2>/dev/null | wc -l)
log "done: ${after} file(s) local (was ${before})"

# `get -a` resumes a partial transfer rather than restarting it, which also means a file
# truncated by a mid-write interruption stays truncated. The ingester tolerates that — it
# skips malformed trailing lines — and the next pull completes the file.
exit 0
