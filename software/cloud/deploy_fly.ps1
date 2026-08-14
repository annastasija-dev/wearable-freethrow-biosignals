# Always-on deploy helper (run after fly auth login)
$ErrorActionPreference = "Stop"
$fly = "$env:LOCALAPPDATA\fly\flyctl.exe"
if (-not (Test-Path $fly)) { throw "flyctl not found at $fly" }

Set-Location $PSScriptRoot

Write-Host "1) Login (browser) if needed..."
& $fly auth whoami 2>$null
if ($LASTEXITCODE -ne 0) {
    & $fly auth login
}

Write-Host "2) Create app if missing..."
& $fly apps list 2>$null | Out-Null
$apps = & $fly apps list --json 2>$null | ConvertFrom-Json
if (-not ($apps | Where-Object { $_.Name -eq "ft-cloud-vgtu" })) {
    & $fly apps create ft-cloud-vgtu
}

Write-Host "3) Volume..."
$vols = & $fly volumes list -a ft-cloud-vgtu --json 2>$null | ConvertFrom-Json
if (-not $vols) {
    & $fly volumes create ft_cloud_data --region waw --size 3 -a ft-cloud-vgtu -y
}

Write-Host "Set secrets manually before deploy:"
Write-Host '  fly secrets set API_KEY="..." GRAPH_CLIENT_ID="..." GRAPH_REFRESH_TOKEN="..." SHAREPOINT_FOLDER="2026 Shooting data Samsung" -a ft-cloud-vgtu'
Write-Host "Then: fly deploy -a ft-cloud-vgtu"
