param(
    [switch]$Clean,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$installerRoot = $PSScriptRoot
$windowsRoot = Split-Path $installerRoot -Parent
$buildScript = Join-Path $windowsRoot "build-windows-app.ps1"
$distRoot = Join-Path $windowsRoot "dist"
$outputRoot = Join-Path $installerRoot "output"
$configPath = Join-Path $installerRoot "installer.config.json"

if (-not (Test-Path $buildScript)) {
    throw "Windows build script was not found at $buildScript."
}

if (-not (Test-Path $configPath)) {
    throw "Installer configuration was not found at $configPath."
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$buildStartedAt = Get-Date

if (-not $SkipBuild) {
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $buildScript,
        "-PackageInstaller"
    )
    if ($Clean) {
        $arguments += "-Clean"
    }

    & powershell @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Desktop installer build failed with exit code $LASTEXITCODE."
    }
}

$installer = Get-ChildItem -Path $distRoot -Filter "ReceiptBridgeDesktop-*.exe" -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $installer) {
    throw "No ReceiptBridgeDesktop installer was found in $distRoot. Run without -SkipBuild to generate one."
}

if (-not $SkipBuild -and $installer.LastWriteTime -lt $buildStartedAt.AddSeconds(-5)) {
    throw "The newest installer in $distRoot is older than this build attempt. Check the jpackage/WiX output above."
}

$targetPath = Join-Path $outputRoot $installer.Name
Get-ChildItem -Path $outputRoot -Filter "ReceiptBridgeDesktop-*.exe" -File |
    Remove-Item -Force
Copy-Item -LiteralPath $installer.FullName -Destination $targetPath -Force

Write-Host "Installer copied to: $targetPath"
Write-Host "After installation, verify the app with:"
Write-Host "  Invoke-RestMethod http://127.0.0.1:9900/integration/status"
