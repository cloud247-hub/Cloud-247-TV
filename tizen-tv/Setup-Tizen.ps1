param(
    [switch]$OpenVSCode
)

$ErrorActionPreference = "Stop"

$projectPath = (Resolve-Path $PSScriptRoot).Path
$configPath = Join-Path $projectPath "config.xml"

if (-not (Test-Path $configPath)) {
    throw "config.xml was not found in $projectPath. Run this script from the Cloud247 TV tizen-tv folder."
}

# The current Tizen Extension rejects project/location path segments containing
# characters outside letters, numbers, dots, dashes, underscores and spaces.
$segments = $projectPath -split '[\\/]'
$invalid = @(
    $segments |
        Where-Object { $_ -and $_ -notmatch '^[A-Za-z0-9._ -]+:$' -and $_ -notmatch '^[A-Za-z0-9._ -]+$' }
)

if ($invalid.Count -gt 0) {
    Write-Host ""
    Write-Host "Tizen cannot use this project path:" -ForegroundColor Red
    Write-Host "  $projectPath" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Problematic path segment(s): $($invalid -join ', ')" -ForegroundColor Yellow
    Write-Host "Move/extract the project to a clean path, for example:" -ForegroundColor Cyan
    Write-Host "  C:\Dev\Cloud247-TV-Tizen\tizen-tv" -ForegroundColor Cyan
    Write-Host ""
    exit 2
}

$vscodeDir = Join-Path $projectPath ".vscode"
$settingsPath = Join-Path $vscodeDir "settings.json"

New-Item -ItemType Directory -Path $vscodeDir -Force | Out-Null

$settings = [ordered]@{
    "tizen.v2.working.project" = $projectPath
}

$settings |
    ConvertTo-Json |
    Set-Content -Path $settingsPath -Encoding UTF8

Write-Host ""
Write-Host "Cloud247 TV Tizen project configured." -ForegroundColor Green
Write-Host "Working project: $projectPath"
Write-Host "VS Code setting:  $settingsPath"
Write-Host ""
Write-Host "Next:" -ForegroundColor Cyan
Write-Host "  1. Open this exact tizen-tv folder in VS Code."
Write-Host "  2. Run: Developer: Reload Window"
Write-Host "  3. Verify your Samsung TV and Cloud247TV certificate are active."
Write-Host "  4. Tizen -> Build Project"
Write-Host "  5. Tizen -> Run Project"
Write-Host ""

if ($OpenVSCode) {
    $code = Get-Command code -ErrorAction SilentlyContinue
    if ($code) {
        & $code.Source $projectPath
    } else {
        Write-Warning "The 'code' command was not found in PATH. Open the folder manually in VS Code."
    }
}
