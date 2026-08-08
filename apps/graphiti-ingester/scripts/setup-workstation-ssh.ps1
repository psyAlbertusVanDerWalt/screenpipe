#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Prepares this workstation to be pulled from by the graphiti-kg server (fork issue #16).

.DESCRIPTION
    Installs Windows OpenSSH Server and locks it down to exactly one job: letting the Coolify
    server read the redacted JSONL export directory, over the LAN, with a key.

    Four independent controls, so no single mistake exposes the machine:

      1. A dedicated local account (graphiti-pull) that owns nothing and is a member of no
         group beyond Users.
      2. NTFS ACLs granting that account read-only access to the export directory and nothing
         else. This is the control that actually bounds what can be read.
      3. ForceCommand internal-sftp for that account — no shell, no arbitrary command
         execution, no port forwarding.
      4. A firewall rule allowing TCP/22 only from the LAN subnet, replacing the permissive
         any-source rule the OpenSSH feature installs by default.

    Password authentication is disabled globally. The account is created with a random
    password that is never used and never displayed.

.NOTES
    Run from an ELEVATED PowerShell. Idempotent — safe to re-run.

    Deliberately does NOT use ChrootDirectory. Win32-OpenSSH requires a chroot target to be
    owned by SYSTEM or Administrators and not writable by the chrooted user, which is not true
    of a directory the export job writes as the logged-in user. Attempting it produces an sshd
    that refuses the session with an error that reads like a key problem. The ACLs in step 2
    already bound reads to the export directory; chroot would only hide the rest of the tree
    from listing, not make it readable.
#>

[CmdletBinding()]
param(
    # LAN subnet permitted to connect. Everything else is refused at the firewall.
    [string]$AllowedSubnet = '10.0.0.0/24',

    # The account the server authenticates as.
    [string]$PullUser = 'graphiti-pull',

    # Directory the export job writes to, and the only thing the server may read.
    [string]$ExportDir = "$env:USERPROFILE\.screenpipe\export\redacted-jsonl",

    # Public key generated for this purpose. The matching private key goes to Coolify.
    [string]$PublicKeyPath = "$env:USERPROFILE\.ssh\graphiti_pull_ed25519.pub"
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$Message) Write-Host "`n==> $Message" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Message) Write-Host "    $Message" -ForegroundColor Green }
function Write-Warn { param([string]$Message) Write-Host "    $Message" -ForegroundColor Yellow }

# ---------------------------------------------------------------------------------------
Write-Step "Checking prerequisites"

if (-not (Test-Path $ExportDir)) {
    throw "Export directory not found: $ExportDir. Run the screenpipe-export-daily task first — there is nothing to serve yet."
}
Write-Ok "Export directory present: $ExportDir"

if (-not (Test-Path $PublicKeyPath)) {
    throw "Public key not found: $PublicKeyPath. Generate it first with:`n  ssh-keygen -t ed25519 -f `"$($PublicKeyPath -replace '\.pub$','')`" -N '`"`"'"
}
$publicKey = (Get-Content $PublicKeyPath -Raw).Trim()
Write-Ok "Public key loaded"

# ---------------------------------------------------------------------------------------
Write-Step "Installing OpenSSH Server"

$capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server*'
if ($capability.State -ne 'Installed') {
    Add-WindowsCapability -Online -Name $capability.Name | Out-Null
    Write-Ok "Installed $($capability.Name)"
} else {
    Write-Ok "Already installed"
}

Set-Service -Name sshd -StartupType Automatic
if ((Get-Service sshd).Status -ne 'Running') { Start-Service sshd }
Write-Ok "sshd running, start type Automatic"

# ---------------------------------------------------------------------------------------
Write-Step "Creating the dedicated pull account"

$existing = Get-LocalUser -Name $PullUser -ErrorAction SilentlyContinue
if (-not $existing) {
    # Random, never displayed, never used — password auth is disabled below. The account
    # exists only as an identity to hang the key and the ACL off.
    Add-Type -AssemblyName 'System.Web'
    $randomPassword = ConvertTo-SecureString ([System.Web.Security.Membership]::GeneratePassword(32, 8)) -AsPlainText -Force
    New-LocalUser -Name $PullUser -Password $randomPassword -FullName 'graphiti-kg server pull' `
        -Description 'Read-only SFTP access to the redacted screenpipe export. Key auth only.' `
        -PasswordNeverExpires -UserMayNotChangePassword | Out-Null
    Write-Ok "Created local user $PullUser"
} else {
    Write-Ok "Local user $PullUser already exists"
}

# Deliberately not added to any group beyond the default Users membership.

# ---------------------------------------------------------------------------------------
Write-Step "Granting read-only access to the export directory only"

# Bypass-traverse-checking is granted to Everyone by default on Windows, so no permission is
# needed on the parent directories — only on the target itself.
$acl = Get-Acl $ExportDir
$rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $PullUser,
    'ReadAndExecute',
    'ContainerInherit,ObjectInherit',
    'None',
    'Allow')
$acl.SetAccessRule($rule)
Set-Acl -Path $ExportDir -AclObject $acl
Write-Ok "Granted $PullUser read-only on $ExportDir"

# ---------------------------------------------------------------------------------------
Write-Step "Installing the authorized key"

# A non-administrator account uses its own profile. Windows OpenSSH only reads
# administrators_authorized_keys for members of the Administrators group — which is exactly
# why this account is deliberately not one.
$userProfile = "C:\Users\$PullUser"
if (-not (Test-Path $userProfile)) {
    New-Item -ItemType Directory -Path $userProfile -Force | Out-Null
}
$sshDir = Join-Path $userProfile '.ssh'
if (-not (Test-Path $sshDir)) { New-Item -ItemType Directory -Path $sshDir -Force | Out-Null }

$authorizedKeys = Join-Path $sshDir 'authorized_keys'
$existingKeys = if (Test-Path $authorizedKeys) { Get-Content $authorizedKeys } else { @() }
if ($existingKeys -notcontains $publicKey) {
    Add-Content -Path $authorizedKeys -Value $publicKey -Encoding ascii
    Write-Ok "Key added to $authorizedKeys"
} else {
    Write-Ok "Key already present"
}

# sshd refuses an authorized_keys file that anyone other than the owner and SYSTEM can write.
# This is the single most common reason a correct key is silently rejected.
icacls $authorizedKeys /inheritance:r | Out-Null
icacls $authorizedKeys /grant "${PullUser}:R" | Out-Null
icacls $authorizedKeys /grant 'SYSTEM:F' | Out-Null
icacls $authorizedKeys /grant 'Administrators:F' | Out-Null
Write-Ok "Tightened permissions on authorized_keys"

# ---------------------------------------------------------------------------------------
Write-Step "Hardening sshd_config"

$configPath = "$env:ProgramData\ssh\sshd_config"
$backup = "$configPath.bak-$(Get-Date -Format 'yyyyMMddHHmmss')"
Copy-Item $configPath $backup
Write-Ok "Backed up existing config to $backup"

# Written wholesale rather than patched: the shipped config has commented defaults that are
# easy to think you have overridden when a later Match block silently wins.
$sshdConfig = @"
# Managed by apps/graphiti-ingester/scripts/setup-workstation-ssh.ps1 (fork issue #16).
# Serves exactly one purpose: read-only SFTP of the redacted screenpipe export to the
# graphiti-kg server over the LAN.

Port 22
AddressFamily inet

PubkeyAuthentication yes
PasswordAuthentication no
PermitEmptyPasswords no
KbdInteractiveAuthentication no
GSSAPIAuthentication no

PermitRootLogin no
PermitTunnel no
AllowAgentForwarding no
AllowTcpForwarding no
X11Forwarding no

# Only this account may log in at all.
AllowUsers $PullUser

LoginGraceTime 30
MaxAuthTries 3
MaxSessions 4
ClientAliveInterval 60
ClientAliveCountMax 3

SyslogFacility LOCAL0
LogLevel INFO

Subsystem sftp sftp-server.exe -l INFO

Match User $PullUser
    # No shell and no arbitrary commands: this account can move files and nothing else.
    ForceCommand internal-sftp -l INFO
    AllowTcpForwarding no
    PermitTTY no
"@

Set-Content -Path $configPath -Value $sshdConfig -Encoding ascii
Write-Ok "Wrote hardened sshd_config"

# ---------------------------------------------------------------------------------------
Write-Step "Restricting the firewall to the LAN"

# The OpenSSH feature installs a rule accepting port 22 from any address. Disable it rather
# than delete it, so a Windows update that re-enables it is visible as a changed state.
Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue |
    Set-NetFirewallRule -Enabled False
Get-NetFirewallRule -DisplayName 'OpenSSH SSH Server (sshd)' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne 'graphiti-pull-ssh-lan' } |
    Set-NetFirewallRule -Enabled False

$ruleName = 'graphiti-pull-ssh-lan'
Remove-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
New-NetFirewallRule -Name $ruleName `
    -DisplayName 'SSH from LAN (graphiti-kg pull)' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort 22 `
    -RemoteAddress $AllowedSubnet `
    -Profile Any | Out-Null
Write-Ok "Port 22 allowed from $AllowedSubnet only"

# ---------------------------------------------------------------------------------------
Write-Step "Restarting sshd and verifying"

Restart-Service sshd
Start-Sleep -Seconds 2

$service = Get-Service sshd
if ($service.Status -ne 'Running') {
    throw "sshd failed to start after configuration. Check: Get-WinEvent -LogName OpenSSH/Operational -MaxEvents 20"
}
Write-Ok "sshd is running"

$listening = Get-NetTCPConnection -LocalPort 22 -State Listen -ErrorAction SilentlyContinue
if ($listening) { Write-Ok "Listening on port 22" } else { Write-Warn "Nothing listening on port 22 — check the service log" }

# ---------------------------------------------------------------------------------------
Write-Step "Host key fingerprints — pin one of these on the server"

# The server should verify this rather than blindly trusting first contact. Compare the
# ed25519 line below against what the server records in known_hosts.
Get-ChildItem "$env:ProgramData\ssh\ssh_host_*_key.pub" | ForEach-Object {
    ssh-keygen -lf $_.FullName
}

Write-Host "`nDone." -ForegroundColor Green
Write-Host @"

Next, on the server side:
  1. Add the PRIVATE key ($env:USERPROFILE\.ssh\graphiti_pull_ed25519) to Coolify as a
     file storage mount on the graphiti-ingester app, at /keys/id_ed25519.
  2. Pin the ed25519 host key fingerprint above into /keys/known_hosts.
  3. Enable the pull-export scheduled task.

Verify from the server before relying on it:
  sftp -i /keys/id_ed25519 $PullUser@$((Get-NetIPAddress -AddressFamily IPv4 |
      Where-Object { `$_.IPAddress -like '10.*' } | Select-Object -First 1).IPAddress)

Confirm the lockdown actually holds — this must FAIL:
  sftp> get C:/Users/$env:USERNAME/Documents/anything
"@ -ForegroundColor Gray
