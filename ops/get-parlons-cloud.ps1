<#
Parlons Cloud - one-command install of your own always-on Parlons account on Windows 10/11.

  irm https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-cloud.ps1 | iex

  Options (run the script from a file to pass them):
    .\get-parlons-cloud.ps1 -Relay        public server: also run a relay for others on port 9501
    .\get-parlons-cloud.ps1 -Uninstall    stop and remove the service (your account data is kept)

What it does:
  1. Java 21 through winget if no Java 11+ is present
  2. downloads the newest parlons-cloud release from GitHub and checks its SHA-256
  3. registers a Scheduled Task that starts the account at logon and keeps it running
  4. starts it, pairs this computer's terminal as the first device, mints a fresh code
  5. prints your address, the invite for your phone and the `parlons` command

It never prints your seed phrase. That lives in %USERPROFILE%\.parlons\seed.txt - back it up like money.
Not yet verified on a Windows machine by the authors: if something is off, please report it.
#>
param([switch]$Relay, [switch]$Uninstall)
$ErrorActionPreference = 'Stop'
$Repo   = 'eurobuddha/maxima'
$Data   = Join-Path $env:USERPROFILE '.parlons'
$Bin    = Join-Path $Data 'bin'
$Client = Join-Path $env:USERPROFILE '.parlons-client'
$Task   = 'Parlons Cloud'
$Heap   = '256m'

function Say($s)  { Write-Host "`n$s" -ForegroundColor White }
function Note($s) { Write-Host "   $s" }

if ($Uninstall) {
    Say "Removing the Parlons Cloud task (your account data in $Data stays)"
    schtasks /End /TN $Task 2>$null | Out-Null
    schtasks /Delete /TN $Task /F 2>$null | Out-Null
    Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*parlons-cloud.jar*' } | Stop-Process -Force -ErrorAction SilentlyContinue
    if (Test-Path $Bin) { Remove-Item -Recurse -Force $Bin }
    Note "Done. To remove the account itself (identity, chats, seed): remove $Data and $Client"
    return
}

# ---------- 1. Java ----------
Say '[1/5] Java'
function JavaOk {
    try { $v = (& java -version 2>&1 | Select-Object -First 1); return ($v -match '"(1[1-9]|[2-9][0-9])') } catch { return $false }
}
if (JavaOk) { Note "present: $(& java -version 2>&1 | Select-Object -First 1)" }
else {
    Note 'installing Java 21 (Eclipse Temurin) with winget'
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) { throw 'winget is not available. Install Java 21 from https://adoptium.net, then re-run.' }
    winget install --id EclipseAdoptium.Temurin.21.JRE -e --accept-package-agreements --accept-source-agreements --silent | Out-Null
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
    if (-not (JavaOk)) { throw 'Java did not install cleanly. Install Java 11+ from https://adoptium.net and re-run.' }
    Note "installed: $(& java -version 2>&1 | Select-Object -First 1)"
}
$JavaExe = (Get-Command java).Source

# ---------- 2. download ----------
Say '[2/5] Newest Parlons Cloud release'
New-Item -ItemType Directory -Force -Path $Bin, (Join-Path $Data 'log') | Out-Null
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases?per_page=50" -Headers @{ 'User-Agent' = 'parlons-installer' }
$cloud = $rel | Where-Object { $_.tag_name -like 'cloud-v*' } | Select-Object -First 1
if (-not $cloud) { throw "No parlons-cloud release found under $Repo." }
$jarAsset = $cloud.assets | Where-Object { $_.name -like 'parlons-cloud-*.jar' } | Select-Object -First 1
$sumAsset = $cloud.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1
$JarName = $jarAsset.name; $Ver = $JarName -replace '^parlons-cloud-','' -replace '\.jar$',''
$JarPath = Join-Path $Bin $JarName
if (Test-Path $JarPath) { Note "already have $Ver" }
else {
    Note "downloading $JarName"
    Invoke-WebRequest -Uri $jarAsset.browser_download_url -OutFile "$JarPath.part" -UseBasicParsing
    if ($sumAsset) {
        $want = ((Invoke-WebRequest -Uri $sumAsset.browser_download_url -UseBasicParsing).Content -split "`n" | Where-Object { $_ -like "* $JarName" } | Select-Object -First 1)
        if ($want) {
            $got = (Get-FileHash "$JarPath.part" -Algorithm SHA256).Hash.ToLower()
            if ($want.Split(' ')[0].ToLower() -ne $got) { Remove-Item "$JarPath.part"; throw "Checksum mismatch for $JarName - download refused." }
            Note 'sha256 verified'
        }
    }
    Move-Item "$JarPath.part" $JarPath
}
Copy-Item $JarPath (Join-Path $Bin 'parlons-cloud.jar') -Force

# ---------- the parlons command ----------
$Args = if ($Relay) { "--data `"$Data`" --relay-port 9501 --no-direct" } else { "--data `"$Data`" --no-relay --no-direct" }
@"
@echo off
rem parlons - drive your Parlons Cloud account from this computer (installed by get-parlons-cloud.ps1)
set PARLONS_ACCOUNT_DIR=$Data
if "%1"=="start" ( schtasks /Run /TN "$Task" & exit /b )
if "%1"=="stop"  ( schtasks /End /TN "$Task" & exit /b )
if "%1"=="log"   ( powershell -NoProfile -Command "Get-Content -Tail 50 -Wait '$Data\log\cloud.log'" & exit /b )
"$JavaExe" -cp "$Bin\parlons-cloud.jar" com.eurobuddha.maxima.cloud.Client --data "$Client" --name "%COMPUTERNAME%" %*
"@ | Set-Content -Path (Join-Path $Bin 'parlons.cmd') -Encoding ASCII

# ---------- 3. scheduled task ----------
Say '[3/5] Service (starts at logon and keeps running)'
$runner = Join-Path $Bin 'run-parlons-cloud.cmd'
@"
@echo off
cd /d "$Data"
"$JavaExe" -Xmx$Heap -jar "$Bin\parlons-cloud.jar" $Args >> "$Data\log\cloud.log" 2>&1
"@ | Set-Content -Path $runner -Encoding ASCII
schtasks /End /TN $Task 2>$null | Out-Null
schtasks /Delete /TN $Task /F 2>$null | Out-Null
$action  = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$runner`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden -StartWhenAvailable
Register-ScheduledTask -TaskName $Task -Action $action -Trigger $trigger -Settings $settings -Description 'Parlons Cloud: my always-on Parlons account' | Out-Null
Start-ScheduledTask -TaskName $Task
Note "scheduled task '$Task' (Task Scheduler shows it; it runs hidden)"

# ---------- 4. wait, pair this computer, mint the phone's invite ----------
Say '[4/5] Waiting for the account to come online'
$invitePath = Join-Path $Data 'invite.txt'
for ($i = 0; $i -lt 90; $i++) { if (Test-Path $invitePath) { break }; Start-Sleep 1 }
if (-not (Test-Path $invitePath)) { Get-Content (Join-Path $Data 'log\cloud.log') -Tail 20 -ErrorAction SilentlyContinue; throw 'Not up after 90 s - fix the cause above and re-run; nothing is lost.' }
$Addr = (Get-Content (Join-Path $Data 'account.txt') -Raw).Trim()
$parlons = Join-Path $Bin 'parlons.cmd'
$env:PARLONS_ACCOUNT_DIR = $Data
& $parlons connect $Addr | Out-Null
$codeFile = Join-Path $Data 'pair-code.txt'
if (Test-Path $codeFile) { & $parlons pair ((Get-Content $codeFile -Raw).Trim()) | Out-Null }
$Invite = (& $parlons newcode 2>$null | Select-String -Pattern 'MAX#\S+\?code=[A-Z0-9-]+' | ForEach-Object { $_.Matches[0].Value } | Select-Object -First 1)
if (-not $Invite) { $Invite = (Get-Content $invitePath -Raw).Trim() }

# ---------- 5. PATH + hand-over ----------
Say '[5/5] The parlons command'
$userPath = [Environment]::GetEnvironmentVariable('Path','User')
if ($userPath -notlike "*$Bin*") { [Environment]::SetEnvironmentVariable('Path', "$userPath;$Bin", 'User') }
$env:Path += ";$Bin"

Write-Host "`n  Your Parlons account is running (Parlons Cloud $Ver).`n" -ForegroundColor White
Write-Host '  Address (share it; it never changes):'
Write-Host "  $Addr`n"
Write-Host '  Pair your phone: open the Parlons Cloud app, tap Paste with this on the clipboard, or make a QR of it'
Write-Host '  (the code half works once):'
Write-Host "  $Invite`n"
Set-Clipboard -Value $Invite -ErrorAction SilentlyContinue
Write-Host '  (it is on your clipboard now)'
Write-Host "`n  Afterwards:  parlons devices | parlons newcode | parlons status | parlons log | parlons stop"
Write-Host '  (open a new terminal for the parlons command to be found)'
Write-Host "`n  BACK UP YOUR SEED NOW:  $Data\seed.txt  -  it is your identity AND a wallet.`n" -ForegroundColor Yellow
