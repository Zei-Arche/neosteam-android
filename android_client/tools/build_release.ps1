#requires -Version 7.2
[CmdletBinding()]
param(
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '..\build\signed-release'),
    [string]$ManifestUrl,
    [string]$ReleaseManifestUrl,
    [string]$AssetRun,
    [string]$AtlasRun,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$androidClient = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).ProviderPath
$outputRoot = [IO.Path]::GetFullPath($BuildDirectory)
$required = 'NEOSTEAM_KEYSTORE', 'NEOSTEAM_STORE_PASSWORD', 'NEOSTEAM_KEY_ALIAS', 'NEOSTEAM_KEY_PASSWORD'
$missing = @($required | Where-Object { [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($_)) })
if ($missing.Count -gt 0) { throw ('Missing release signing environment variables: ' + ($missing -join ', ')) }
if (![IO.Path]::IsPathFullyQualified($env:NEOSTEAM_KEYSTORE) -or
        !(Test-Path -LiteralPath $env:NEOSTEAM_KEYSTORE -PathType Leaf)) {
    throw 'Set NEOSTEAM_KEYSTORE to an existing absolute keystore path.'
}

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (!$sdk -and $env:LOCALAPPDATA) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$sdk) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$sdk = (Resolve-Path -LiteralPath $sdk).ProviderPath
$apksigner = Join-Path $sdk 'build-tools\36.0.0\apksigner.bat'
if (!(Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw 'Install Android SDK Build Tools 36.0.0.' }
$jarsigner = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jarsigner.exe' } else {
    (Get-Command jarsigner.exe -CommandType Application).Source
}

$gradleArguments = @('--no-daemon', '--no-configuration-cache', ':app:assembleRelease', ':app:bundleRelease',
    '-PneosteamRequireReleaseSigning=true')
if ($Offline) { $gradleArguments += '--offline' }
$buildEnvironment = @{ ANDROID_HOME = $sdk; ORG_GRADLE_PROJECT_neosteamBuildDir = $outputRoot }
if ($ManifestUrl) { $buildEnvironment.ORG_GRADLE_PROJECT_neosteamOtaManifestUrl = $ManifestUrl }
if ($ReleaseManifestUrl) { $buildEnvironment.ORG_GRADLE_PROJECT_neosteamReleaseManifestUrl = $ReleaseManifestUrl }
if ($AssetRun) { $buildEnvironment.ORG_GRADLE_PROJECT_neosteamAssetRun = (Resolve-Path -LiteralPath $AssetRun).ProviderPath }
if ($AtlasRun) { $buildEnvironment.ORG_GRADLE_PROJECT_neosteamAtlasRun = (Resolve-Path -LiteralPath $AtlasRun).ProviderPath }

$previousEnvironment = @{}
Push-Location -LiteralPath $androidClient
try {
    foreach ($name in $buildEnvironment.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, $buildEnvironment[$name])
    }
    & (Join-Path $androidClient 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed with exit code $LASTEXITCODE." }
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name])
    }
    Pop-Location
}

$apk = Join-Path $outputRoot 'outputs\apk\release\app-release.apk'
$aab = Join-Path $outputRoot 'outputs\bundle\release\app-release.aab'
if (!(Test-Path -LiteralPath $apk -PathType Leaf) -or !(Test-Path -LiteralPath $aab -PathType Leaf)) {
    throw 'Gradle did not produce both signed release artifacts.'
}
& $apksigner verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$bundleVerification = & $jarsigner '-J-Duser.language=en' '-J-Duser.country=US' -verify $aab
if ($LASTEXITCODE -ne 0 -or !($bundleVerification -contains 'jar verified.')) {
    throw 'AAB signature verification failed.'
}

function Test-Arm64Library([string]$Artifact, [string]$Prefix) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Artifact)
    try {
        $libraries = @($zip.Entries | Where-Object {
            $_.FullName.StartsWith($Prefix, [StringComparison]::Ordinal) -and $_.FullName.EndsWith('.so')
        })
        $library = $zip.GetEntry(${Prefix} + 'arm64-v8a/libneosteam.so')
        if ($null -eq $library -or $libraries.Count -eq 0 -or @($libraries | Where-Object {
            !$_.FullName.StartsWith(${Prefix} + 'arm64-v8a/', [StringComparison]::Ordinal)
        }).Count -ne 0) {
            throw "Unexpected native ABI contents in $Artifact."
        }
        $reader = [IO.BinaryReader]::new($library.Open())
        try {
            $elf = $reader.ReadBytes(20)
            if ($elf.Length -ne 20 -or [Convert]::ToHexString($elf[0..5]) -cne '7F454C460201' -or
                    [BitConverter]::ToUInt16($elf, 18) -ne 183) {
                throw "The NeoSteam library is not little-endian ELF64 AArch64 in $Artifact."
            }
        } finally {
            $reader.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
}

Test-Arm64Library $apk 'lib/'
Test-Arm64Library $aab 'base/lib/'
Get-FileHash -LiteralPath $apk, $aab -Algorithm SHA256 | ForEach-Object { "$($_.Hash)  $($_.Path)" }
