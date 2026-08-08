<#
.SYNOPSIS
    Pushes the redacted JSONL export from this workstation to the graphiti-ingester.

.DESCRIPTION
    Runs on a schedule after the export job. The workstation pushes; the server does not pull.
    That means this machine needs no inbound anything — no SSH service, no extra local account,
    no NTFS carve-out, no firewall opening. Only an outbound HTTP call on the LAN.

    Safe to run as often as you like. The server stores each day's file under its own name and
    the ingest ledger is keyed on a deterministic episode key, so re-uploading a day is a no-op
    rather than a duplicate.

.NOTES
    No elevation required. That is the point.
#>

[CmdletBinding()]
param(
    # LAN address of the ingester. Bound to the server's LAN interface only.
    [string]$ServerUrl = 'http://10.0.0.69:18080',

    [string]$ExportDir = "$env:USERPROFILE\.screenpipe\export\redacted-jsonl",

    # Shared secret. Prefer the file; the environment variable is for one-off runs.
    [string]$TokenFile = "$env:USERPROFILE\.screenpipe\.upload-token",

    # Only push files modified within this window. 0 uploads everything present.
    [int]$SinceDays = 7,

    [int]$MaxAttempts = 3
)

$ErrorActionPreference = 'Stop'

function Write-Log { param([string]$Message) Write-Host "[upload-export] $Message" }

# --- token ------------------------------------------------------------------------------
$token = $env:INGEST_UPLOAD_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) {
    if (-not (Test-Path $TokenFile)) {
        Write-Log "FATAL: no token. Put the shared secret in $TokenFile or set INGEST_UPLOAD_TOKEN."
        exit 1
    }
    $token = (Get-Content $TokenFile -Raw).Trim()
}
if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Log "FATAL: token is empty"
    exit 1
}

# --- files ------------------------------------------------------------------------------
if (-not (Test-Path $ExportDir)) {
    Write-Log "nothing to do: $ExportDir does not exist yet"
    exit 0
}

$files = Get-ChildItem -Path $ExportDir -Filter '*.jsonl' -File
if ($SinceDays -gt 0) {
    $cutoff = (Get-Date).AddDays(-$SinceDays)
    # Re-sending recent days is deliberate, not wasteful: a day's file keeps growing until
    # that day ends, and the server replacing it is how the final version arrives.
    $files = $files | Where-Object { $_.LastWriteTime -ge $cutoff }
}

if (-not $files) {
    Write-Log "no export files to upload"
    exit 0
}

# --- upload -----------------------------------------------------------------------------
$uploaded = 0
$failed = 0

foreach ($file in $files) {
    $uri = "$ServerUrl/api/v1/export/uploads/$($file.Name)"
    $attempt = 0
    $delay = 5
    $done = $false

    while (-not $done -and $attempt -lt $MaxAttempts) {
        $attempt++
        try {
            $response = Invoke-RestMethod -Uri $uri -Method Post `
                -Headers @{ Authorization = "Bearer $token" } `
                -ContentType 'application/x-ndjson' `
                -InFile $file.FullName `
                -TimeoutSec 120
            Write-Log "uploaded $($file.Name) ($($response.bytesStored) bytes)"
            $uploaded++
            $done = $true
        } catch {
            $status = $null
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }

            # 4xx will fail identically forever — a bad token or a name the server refuses.
            # Retrying those just delays the real error reaching the log.
            if ($status -ge 400 -and $status -lt 500) {
                Write-Log "REFUSED $($file.Name): HTTP $status — $($_.Exception.Message)"
                $failed++
                $done = $true
            } elseif ($attempt -ge $MaxAttempts) {
                Write-Log "FAILED $($file.Name) after $attempt attempt(s): $($_.Exception.Message)"
                $failed++
                $done = $true
            } else {
                Write-Log "attempt $attempt for $($file.Name) failed, retrying in ${delay}s"
                Start-Sleep -Seconds $delay
                $delay = $delay * 2
            }
        }
    }
}

Write-Log "done: $uploaded uploaded, $failed failed"
if ($failed -gt 0) { exit 1 }
exit 0
