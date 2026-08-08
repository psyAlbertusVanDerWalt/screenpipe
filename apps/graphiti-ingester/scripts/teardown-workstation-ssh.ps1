#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Undoes everything setup-workstation-ssh.ps1 created.

.DESCRIPTION
    The pipeline moved from a server-initiated SSH pull to a workstation-initiated HTTP push,
    so none of the inbound plumbing is needed any more. This removes it rather than leaving a
    dormant SSH service and a privileged-looking local account on the machine forever.

    Removes, in order:
      1. The LAN firewall rule for port 22
      2. The graphiti-pull local account and its authorized_keys
      3. The Deny and Allow ACEs granted to that account
      4. The hardened sshd_config, restoring the most recent backup

    OpenSSH Server itself is left installed but stopped and disabled by default — uninstalling
    a Windows capability is slow and you may want it for something else. Pass -RemoveOpenSSH to
    uninstall it outright.

.NOTES
    Run from an ELEVATED PowerShell. Idempotent — safe to run even if setup never completed.
#>

[CmdletBinding()]
param(
    [string]$PullUser = 'graphiti-pull',
    [string]$ExportDir = "$env:USERPROFILE\.screenpipe\export\redacted-jsonl",
    [switch]$RemoveOpenSSH
)

$ErrorActionPreference = 'Continue'   # keep going; each step is independent

function Write-Step { param([string]$m) Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok   { param([string]$m) Write-Host "    $m" -ForegroundColor Green }
function Write-Skip { param([string]$m) Write-Host "    $m" -ForegroundColor DarkGray }

# ---------------------------------------------------------------------------------------
Write-Step "Removing the firewall rule"
if (Get-NetFirewallRule -Name 'graphiti-pull-ssh-lan' -ErrorAction SilentlyContinue) {
    Remove-NetFirewallRule -Name 'graphiti-pull-ssh-lan'
    Write-Ok "Removed graphiti-pull-ssh-lan"
} else {
    Write-Skip "not present"
}

# ---------------------------------------------------------------------------------------
Write-Step "Stopping sshd"
$sshd = Get-Service sshd -ErrorAction SilentlyContinue
if ($sshd) {
    if ($sshd.Status -eq 'Running') { Stop-Service sshd -Force }
    Set-Service sshd -StartupType Disabled
    Write-Ok "sshd stopped and disabled"
} else {
    Write-Skip "sshd not installed"
}

# ---------------------------------------------------------------------------------------
Write-Step "Removing ACEs granted to $PullUser"

# The Deny on the profile root, and any inherited copies left behind by the propagation that
# an earlier version of the setup script started before it was corrected.
$profileRoot = Split-Path -Parent (Split-Path -Parent $ExportDir)
$profileRoot = Split-Path -Parent $profileRoot

foreach ($path in @($profileRoot, $ExportDir)) {
    if (-not (Test-Path $path)) { Write-Skip "missing: $path"; continue }
    try {
        $acl = Get-Acl $path
        $removed = 0
        foreach ($ace in @($acl.Access | Where-Object { $_.IdentityReference -like "*$PullUser" -and -not $_.IsInherited })) {
            [void]$acl.RemoveAccessRule($ace)
            $removed++
        }
        if ($removed -gt 0) {
            Set-Acl -Path $path -AclObject $acl
            Write-Ok "removed $removed explicit ACE(s) from $path"
        } else {
            Write-Skip "no explicit ACEs on $path"
        }
    } catch {
        Write-Host "    could not clean ACEs on ${path}: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------------------
Write-Step "Removing the authorized key and the account"

$authorizedKeys = Join-Path "$env:ProgramData\ssh" "$PullUser`_authorized_keys"
if (Test-Path $authorizedKeys) {
    Remove-Item $authorizedKeys -Force
    Write-Ok "removed $authorizedKeys"
} else {
    Write-Skip "no authorized_keys at $authorizedKeys"
}

$legacyProfile = "C:\Users\$PullUser"
if (Test-Path $legacyProfile) {
    Remove-Item $legacyProfile -Recurse -Force -ErrorAction SilentlyContinue
    Write-Ok "removed the stub profile directory $legacyProfile"
}

if (Get-LocalUser -Name $PullUser -ErrorAction SilentlyContinue) {
    Remove-LocalUser -Name $PullUser
    Write-Ok "removed local user $PullUser"
} else {
    Write-Skip "local user $PullUser not present"
}

# ---------------------------------------------------------------------------------------
Write-Step "Restoring sshd_config"
$configPath = "$env:ProgramData\ssh\sshd_config"
$backup = Get-ChildItem "$configPath.bak-*" -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
if ($backup) {
    Copy-Item $backup.FullName $configPath -Force
    Write-Ok "restored from $($backup.Name)"
} else {
    Write-Skip "no backup found — leaving sshd_config as is (sshd is disabled anyway)"
}

# ---------------------------------------------------------------------------------------
if ($RemoveOpenSSH) {
    Write-Step "Uninstalling OpenSSH Server (slow)"
    $capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server*'
    if ($capability.State -eq 'Installed') {
        Remove-WindowsCapability -Online -Name $capability.Name | Out-Null
        Write-Ok "uninstalled $($capability.Name)"
    } else {
        Write-Skip "not installed"
    }
} else {
    Write-Skip "`nOpenSSH Server left installed but disabled. Re-run with -RemoveOpenSSH to uninstall it."
}

Write-Host "`nDone. Nothing inbound remains for this pipeline." -ForegroundColor Green
Write-Host @"

The workstation now only pushes outbound. Next:
  1. Put the shared secret in $env:USERPROFILE\.screenpipe\.upload-token
  2. Register scripts\upload-export.ps1 as a scheduled task after the export job
"@ -ForegroundColor Gray
