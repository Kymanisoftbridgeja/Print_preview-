param(
    [ValidateSet("apk", "aab", "both")]
    [string]$Artifact = "apk",
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

function Get-ReleaseSigningValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PropertyName,
        [Parameter(Mandatory = $true)]
        [string]$EnvironmentName
    )

    $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName)

    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        return $environmentValue.Trim()
    }

    $keystoreFile = Join-Path $PSScriptRoot "keystore.properties"

    if (-not (Test-Path $keystoreFile)) {
        return $null
    }

    $match = Select-String -Path $keystoreFile -Pattern ("^{0}=(.+)$" -f [Regex]::Escape($PropertyName)) | Select-Object -First 1

    if ($match -and $match.Matches.Count -gt 0) {
        return $match.Matches[0].Groups[1].Value.Trim()
    }

    return $null
}

function Test-ReleaseSigningConfigured {
    $storeFile = Get-ReleaseSigningValue -PropertyName "storeFile" -EnvironmentName "RELEASE_STORE_FILE"
    $storePassword = Get-ReleaseSigningValue -PropertyName "storePassword" -EnvironmentName "RELEASE_STORE_PASSWORD"
    $keyAlias = Get-ReleaseSigningValue -PropertyName "keyAlias" -EnvironmentName "RELEASE_KEY_ALIAS"
    $keyPassword = Get-ReleaseSigningValue -PropertyName "keyPassword" -EnvironmentName "RELEASE_KEY_PASSWORD"

    return (
        -not [string]::IsNullOrWhiteSpace($storeFile) -and
        -not [string]::IsNullOrWhiteSpace($storePassword) -and
        -not [string]::IsNullOrWhiteSpace($keyAlias) -and
        -not [string]::IsNullOrWhiteSpace($keyPassword)
    )
}

function Get-VersionName {
    $buildFile = Join-Path $PSScriptRoot "App\app\build.gradle.kts"
    $match = Select-String -Path $buildFile -Pattern 'versionName = "([^"]+)"' | Select-Object -First 1

    if ($match -and $match.Matches.Count -gt 0) {
        return $match.Matches[0].Groups[1].Value
    }

    throw "Unable to read versionName from $buildFile."
}

function Invoke-Gradle {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Task
    )

    & (Join-Path $PSScriptRoot "gradlew.bat") $Task

    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task '$Task' failed with exit code $LASTEXITCODE."
    }
}

function Copy-ApkToDist {
    param(
        [Parameter(Mandatory = $true)]
        [string]$VersionName,
        [Parameter(Mandatory = $true)]
        [string]$DistDir
    )

    $metadataPath = Join-Path $PSScriptRoot "App\app\build\outputs\apk\release\output-metadata.json"

    if (-not (Test-Path $metadataPath)) {
        throw "APK metadata not found at $metadataPath."
    }

    $metadata = Get-Content $metadataPath | ConvertFrom-Json
    $element = $metadata.elements | Select-Object -First 1
    $sourcePath = Join-Path $PSScriptRoot ("App\app\build\outputs\apk\release\" + $element.outputFile)

    if (-not (Test-Path $sourcePath)) {
        throw "APK file not found at $sourcePath."
    }

    $signingSuffix = if ($element.outputFile -like "*unsigned*") { "unsigned" } else { "signed" }
    $targetPath = Join-Path $DistDir ("ReceiptBridge-{0}-release-{1}.apk" -f $VersionName, $signingSuffix)
    Copy-Item $sourcePath $targetPath -Force

    Write-Host "APK ready: $targetPath"
}

function Copy-BundleToDist {
    param(
        [Parameter(Mandatory = $true)]
        [string]$VersionName,
        [Parameter(Mandatory = $true)]
        [string]$DistDir,
        [Parameter(Mandatory = $true)]
        [bool]$SignedRelease
    )

    $bundleDir = Join-Path $PSScriptRoot "App\app\build\outputs\bundle\release"
    $bundle = Get-ChildItem -Path $bundleDir -Filter "*.aab" -ErrorAction Stop | Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $bundle) {
        throw "No release bundle was found in $bundleDir."
    }

    $signingSuffix = if ($SignedRelease) { "signed" } else { "unsigned" }
    $targetPath = Join-Path $DistDir ("ReceiptBridge-{0}-release-{1}.aab" -f $VersionName, $signingSuffix)
    Copy-Item $bundle.FullName $targetPath -Force

    Write-Host "AAB ready: $targetPath"
}

$versionName = Get-VersionName
$distDir = Join-Path $PSScriptRoot "dist"
$hasReleaseSigning = Test-ReleaseSigningConfigured

New-Item -ItemType Directory -Path $distDir -Force | Out-Null

if (-not $hasReleaseSigning) {
    Write-Host "No release keystore detected. Gradle will build unsigned release artifacts."
}

if ($Clean) {
    Invoke-Gradle -Task "clean"
}

switch ($Artifact) {
    "apk" {
        Invoke-Gradle -Task "assembleRelease"
        Copy-ApkToDist -VersionName $versionName -DistDir $distDir
    }
    "aab" {
        Invoke-Gradle -Task "bundleRelease"
        Copy-BundleToDist -VersionName $versionName -DistDir $distDir -SignedRelease $hasReleaseSigning
    }
    "both" {
        Invoke-Gradle -Task "assembleRelease"
        Invoke-Gradle -Task "bundleRelease"
        Copy-ApkToDist -VersionName $versionName -DistDir $distDir
        Copy-BundleToDist -VersionName $versionName -DistDir $distDir -SignedRelease $hasReleaseSigning
    }
}
