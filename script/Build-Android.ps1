# SPDX-License-Identifier: GPL-3.0-or-later
[CmdletBinding()]
param(
    [string[]]$Tasks = @(':app:assembleDebug'),
    [string]$Abis = 'arm64-v8a,x86_64'
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$workspacePath = Split-Path -Parent $repoPath
# All native tools must see an ASCII absolute path on Windows.
if ($workspacePath -match '[^\x00-\x7F]') {
    $aliasPath = Join-Path ([System.IO.Path]::GetPathRoot($workspacePath)) 'ime-workspace'
    if (Test-Path -LiteralPath $aliasPath) {
        $existing = Get-Item -LiteralPath $aliasPath
        if ($existing.LinkType -ne 'Junction' -or $existing.Target -ne $workspacePath) {
            throw "Path already exists and is not this workspace junction: $aliasPath"
        }
    } else { New-Item -ItemType Junction -Path $aliasPath -Target $workspacePath | Out-Null }
    $workspacePath = $aliasPath
    $repoPath = Join-Path $workspacePath (Split-Path -Leaf $repoPath)
}
$localJdk = Join-Path $workspacePath '.tools\jdk\jdk-17.0.21.0.2+1'
if (Test-Path -LiteralPath $localJdk) { $env:JAVA_HOME = $localJdk }
$localSdk = Join-Path $workspacePath '.tools\sdk'
if (Test-Path -LiteralPath $localSdk) { $env:ANDROID_HOME = $localSdk; $env:ANDROID_SDK_ROOT = $localSdk }
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Install JDK and Android SDK, then set JAVA_HOME and ANDROID_HOME.' }
$env:BUILD_ABI = $Abis
$localGradle = Join-Path $workspacePath '.tools\gradle\gradle-9.8.1\bin\gradle.bat'
Push-Location $repoPath
try {
    if (Test-Path -LiteralPath $localGradle) { & $localGradle @Tasks --max-workers=4 --console=plain }
    else { & .\gradlew.bat @Tasks --max-workers=4 --console=plain }
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
