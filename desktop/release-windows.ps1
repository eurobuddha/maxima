<# Signed Windows app image and MSI. Run after :desktop:jpackageImage.
   Reuses PhotoCraft packaging/windows/sign.ps1's SDK discovery, exit checks and cleanup.
   Requires WINDOWS_CERT_PFX, WINDOWS_CERT_PASSWORD and JAVA_HOME. #>
param([Parameter(Mandatory = $true)][string] $Version)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected release version X.Y.Z' }
if (-not $env:WINDOWS_CERT_PFX -or -not $env:WINDOWS_CERT_PASSWORD) { throw 'Windows signing credentials are required' }

function Find-SignTool {
    if ($env:SIGNTOOL) { return (Resolve-Path $env:SIGNTOOL).Path }
    $onPath = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    $found = Get-ChildItem -Path $kits -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $found) { throw 'signtool.exe not found (install the Windows SDK)' }
    return $found.FullName
}
function Invoke-SignTool([string[]] $Arguments) {
    & $script:SignTool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "signtool $($Arguments[0]) failed with exit code $LASTEXITCODE" }
}
function Sign-AndVerify([string] $File) {
    Invoke-SignTool @('sign', '/fd', 'SHA256', '/td', 'SHA256', '/tr', 'http://timestamp.digicert.com',
        '/f', $script:Pfx, '/p', $env:WINDOWS_CERT_PASSWORD, $File)
    Invoke-SignTool @('verify', '/pa', '/all', '/tw', $File)
}
$script:SignTool = Find-SignTool
$out = Join-Path (Get-Location) 'desktop/build/jpackage'
$app = Join-Path $out 'MaximaNode'
$launcher = Join-Path $app 'MaximaNode.exe'
if (-not (Test-Path $launcher -PathType Leaf)) { throw "Missing app image: $launcher" }
$msi = Join-Path $out "MaximaNode-$Version.msi"
if (Test-Path $msi) { throw "Installer already exists: $msi" }
$temp = Join-Path ([IO.Path]::GetTempPath()) ('parlons-sign-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$verified = $false
try {
    $script:Pfx = Join-Path $temp 'signing.pfx'
    [IO.File]::WriteAllBytes($script:Pfx, [Convert]::FromBase64String($env:WINDOWS_CERT_PFX))
    Sign-AndVerify $launcher
    & (Join-Path $env:JAVA_HOME 'bin/jpackage.exe') --type msi --app-image $app --name MaximaNode `
        --app-version $Version --dest $out --vendor eurobuddha --win-menu --win-dir-chooser --win-per-user-install
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path $msi -PathType Leaf)) { throw "Missing installer: $msi" }
    Sign-AndVerify $msi
    $verified = $true
}
finally {
    if (-not $verified -and (Test-Path $msi)) { Remove-Item -Force $msi }
    Remove-Item -Recurse -Force $temp
}
