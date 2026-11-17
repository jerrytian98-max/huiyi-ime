# SPDX-License-Identifier: GPL-3.0-or-later
[CmdletBinding()]
param(
    [string]$DestinationDirectory = 'C:\Users\ricar\jerryjwtian的同步盘'
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$workspacePath = Split-Path -Parent $repoPath
$sourceApk = Join-Path $workspacePath '交付\huiyi-android-arm64-debug.apk'
if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
    throw "Verified delivery APK is missing: $sourceApk"
}
if (-not (Test-Path -LiteralPath $DestinationDirectory -PathType Container)) {
    throw "Delivery directory is missing: $DestinationDirectory"
}
$validationPath = Join-Path $workspacePath '交付\validation.json'
if (-not (Test-Path -LiteralPath $validationPath -PathType Leaf)) {
    throw "Delivery validation is missing: $validationPath"
}
$validation = Get-Content -LiteralPath $validationPath -Raw -Encoding UTF8 | ConvertFrom-Json
$version = [string]$validation.version
if ($version -notmatch '^\d+\.\d+\.\d+(-ai)?$') {
    throw "Invalid delivery version: $version"
}
$fileVersion = $version -replace '-ai$', ''
$destinationName = "huiyi-android-v$fileVersion-arm64-debug.apk"
$destinationPath = Join-Path (Get-Item -LiteralPath $DestinationDirectory).FullName $destinationName
if (Test-Path -LiteralPath $destinationPath -PathType Container) {
    throw "Destination is a directory, not an APK file: $destinationPath"
}
$expectedHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash
$validatedHash = @($validation.apk_checksums | Where-Object { $_ -match '^([a-fA-F0-9]{64})\s+huiyi-android-arm64-debug\.apk$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($validatedHash.Count -ne 1 -or $validatedHash[0] -ne $expectedHash) {
    throw 'Source APK checksum does not match the delivery validation.'
}
Copy-Item -LiteralPath $sourceApk -Destination $destinationPath -Force
$actualHash = (Get-FileHash -LiteralPath $destinationPath -Algorithm SHA256).Hash
if ($expectedHash -ne $actualHash) {
    throw 'Copied APK checksum does not match the verified delivery APK.'
}
[PSCustomObject]@{
    Version = $version
    Path = $destinationPath
    Length = (Get-Item -LiteralPath $destinationPath).Length
    SHA256 = $actualHash
}
