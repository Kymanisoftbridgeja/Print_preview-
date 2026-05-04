param(
    [switch]$Clean,
    [switch]$PackageInstaller
)

$ErrorActionPreference = "Stop"

$windowsRoot = $PSScriptRoot
$projectRoot = Split-Path $windowsRoot -Parent
$gradle = Join-Path $projectRoot "gradlew.bat"
$moduleRoot = Join-Path $windowsRoot "ReceiptBridgeDesktop"
$distRoot = Join-Path $windowsRoot "dist"
$appName = "ReceiptBridgeDesktop"
$runtimeImagePath = Join-Path $moduleRoot "build\compose\tmp\main\runtime"
$jarRoot = Join-Path $moduleRoot "build\compose\jars"
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
$staleDistRoots = @(
    (Join-Path $windowsRoot "dist-refresh"),
    (Join-Path $windowsRoot "dist-widthfix"),
    (Join-Path $windowsRoot "dist-calibrated")
)

function Get-AppVersion {
    $buildFile = Join-Path $moduleRoot "build.gradle.kts"
    $match = Select-String -Path $buildFile -Pattern '^version = "([^"]+)"' | Select-Object -First 1

    if (-not $match -or $match.Matches.Count -eq 0) {
        throw "Unable to read the desktop app version from $buildFile."
    }

    return $match.Matches[0].Groups[1].Value
}

$appVersion = Get-AppVersion
$jarName = "ReceiptBridgeDesktop-windows-x64-$appVersion.jar"

function Invoke-GradleTask {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Task
    )

    & $gradle $Task
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle task '$Task' failed with exit code $LASTEXITCODE."
    }
}

function Invoke-JPackage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Type
    )

    if (-not (Test-Path $jpackage)) {
        throw "jpackage was not found at $jpackage."
    }

    $arguments = @(
        "--type", $Type,
        "--dest", $distRoot,
        "--input", $jarRoot,
        "--name", $appName,
        "--main-jar", $jarName,
        "--main-class", "com.receiptbridge.desktop.MainKt",
        "--runtime-image", $runtimeImagePath,
        "--app-version", $appVersion
    )

    & $jpackage @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage '$Type' failed with exit code $LASTEXITCODE."
    }
}

function Stop-ExistingDesktopProcesses {
    $processes = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $processPath = $_.Path
        if ([string]::IsNullOrWhiteSpace($processPath)) {
            return $false
        }

        $normalizedPath = [System.IO.Path]::GetFullPath($processPath)
        if ($normalizedPath.StartsWith($distRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }

        foreach ($candidateRoot in $staleDistRoots) {
            if (
                -not [string]::IsNullOrWhiteSpace($candidateRoot) -and
                $normalizedPath.StartsWith($candidateRoot, [System.StringComparison]::OrdinalIgnoreCase)
            ) {
                return $true
            }
        }

        return $false
    }

    foreach ($process in $processes) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }

    Start-Sleep -Milliseconds 600
}

function Remove-StaleDistRoots {
    foreach ($path in $staleDistRoots) {
        if (Test-Path $path) {
            Remove-Item $path -Recurse -Force
        }
    }
}

New-Item -ItemType Directory -Path $distRoot -Force | Out-Null

if ($Clean) {
    Invoke-GradleTask ":windowsApp:clean"
}

Invoke-GradleTask ":windowsApp:createRuntimeImage"
Invoke-GradleTask ":windowsApp:packageUberJarForCurrentOS"

Stop-ExistingDesktopProcesses

$distAppPath = Join-Path $distRoot $appName
if (Test-Path $distAppPath) {
    Remove-Item $distAppPath -Recurse -Force
}

Remove-StaleDistRoots

Invoke-JPackage -Type "app-image"
$launcherPath = Join-Path $distAppPath "$appName.exe"

Write-Host "Desktop app image ready: $distAppPath"
if (Test-Path $launcherPath) {
    Write-Host "Launcher executable ready: $launcherPath"
} else {
    Write-Warning "The app image was copied, but the launcher executable was not found at $launcherPath."
}

if ($PackageInstaller) {
    try {
        Invoke-JPackage -Type "exe"
        $installerPath = Join-Path $distRoot "$appName-$appVersion.exe"
        if (Test-Path $installerPath) {
            Write-Host "Installer ready: $installerPath"
        }
    } catch {
        Write-Warning "Installer packaging failed: $($_.Exception.Message)"
    }
}
