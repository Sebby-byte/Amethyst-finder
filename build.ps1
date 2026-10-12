# Builds this Fabric mod on Windows. Double-click build.bat (or run this in PowerShell).
# It finds or installs Java 25, downloads Gradle 9.6.0 once, builds, and copies the jar to .\jar-output
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location $PSScriptRoot

$GradleVersion = '9.6.0'
$ToolsDir = Join-Path $env:USERPROFILE '.mod-build-tools'

function Get-JavaMajor([string]$javaExe) {
    try {
        $out = & cmd /c "`"$javaExe`" -version 2>&1" | Out-String
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

function Find-Java25 {
    # 1) java on PATH
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd -and (Get-JavaMajor $cmd.Source) -ge 25) { return (Split-Path (Split-Path $cmd.Source)) }
    # 2) common install folders
    $roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Zulu")
    foreach ($root in $roots) {
        if (Test-Path $root) {
            foreach ($d in (Get-ChildItem $root -Directory | Sort-Object Name -Descending)) {
                $exe = Join-Path $d.FullName 'bin\java.exe'
                if ((Test-Path $exe) -and (Get-JavaMajor $exe) -ge 25) { return $d.FullName }
            }
        }
    }
    return $null
}

Write-Host '== Checking for Java 25 ==' -ForegroundColor Cyan
$javaHome = Find-Java25
if (-not $javaHome) {
    Write-Host 'Java 25 was not found.' -ForegroundColor Yellow
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Host 'Installing Temurin JDK 25 with winget (a permission prompt may appear)...'
        winget install --id EclipseAdoptium.Temurin.25.JDK -e --accept-package-agreements --accept-source-agreements
        $javaHome = Find-Java25
    }
}
if (-not $javaHome) {
    Write-Host 'Could not find or install Java 25 automatically.' -ForegroundColor Red
    Write-Host 'Install "Temurin 25 (JDK)" from https://adoptium.net, then run this again.'
    exit 1
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Write-Host "Using Java at $javaHome"

Write-Host "== Checking for Gradle $GradleVersion ==" -ForegroundColor Cyan
$gradleHome = Join-Path $ToolsDir "gradle-$GradleVersion"
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleBat)) {
    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    $zip = Join-Path $ToolsDir "gradle-$GradleVersion-bin.zip"
    Write-Host "Downloading Gradle $GradleVersion (one time, about 130 MB)..."
    Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip
    Expand-Archive -Path $zip -DestinationPath $ToolsDir -Force
    Remove-Item $zip -Force
}
if (-not (Test-Path $gradleBat)) {
    Write-Host 'Gradle did not unpack correctly.' -ForegroundColor Red
    exit 1
}

Write-Host '== Building (the first build downloads Minecraft and takes a few minutes) ==' -ForegroundColor Cyan
& $gradleBat build --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host ''
    Write-Host 'BUILD FAILED. Copy the error above (the lines with "error:") and send it over.' -ForegroundColor Red
    exit $LASTEXITCODE
}

$out = Join-Path $PSScriptRoot 'jar-output'
New-Item -ItemType Directory -Force -Path $out | Out-Null
Get-ChildItem (Join-Path $PSScriptRoot 'build\libs') -Filter *.jar |
    Where-Object { $_.Name -notmatch 'sources|dev' } |
    ForEach-Object { Copy-Item $_.FullName $out -Force; Write-Host "Built: $out\$($_.Name)" -ForegroundColor Green }

Write-Host ''
Write-Host 'Done. Copy the jar from jar-output into your Minecraft mods folder.' -ForegroundColor Green
