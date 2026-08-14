$base = "http://localhost:8080"
$key = "dev-change-me"
$headers = @{ "X-API-Key" = $key; "Content-Type" = "application/json" }

Write-Host "1) Start session"
$startBody = @{
    participant_code = "P_TEST"
    watch_model = "Galaxy Watch 7"
    wrist = "right"
    location = "lab"
} | ConvertTo-Json
$start = Invoke-RestMethod -Uri "$base/api/v1/sessions/start" -Method Post -Headers $headers -Body $startBody
$sid = $start.session_id
Write-Host "Session: $sid"

Write-Host "2) Upload fake raw IMU"
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$samples = @(
    @{ time_ms = $now; sensor = "accelerometer"; x = 1.1; y = 0.2; z = 9.8; source = "test" },
    @{ time_ms = ($now + 10); sensor = "gyroscope"; x = 0.3; y = -0.1; z = 0.5; source = "test" }
)
$rawBody = @{ samples = $samples } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Uri "$base/api/v1/sessions/$sid/raw/batch" -Method Post -Headers $headers -Body $rawBody | Out-Null

Write-Host "3) Label 2 shots"
$ts = (Get-Date).ToUniversalTime().ToString("o")
Invoke-RestMethod -Uri "$base/api/v1/sessions/$sid/shots" -Method Post -Headers $headers -Body (@{ result = "hit"; client_timestamp = $ts; shot_no = 1 } | ConvertTo-Json) | Out-Null
Invoke-RestMethod -Uri "$base/api/v1/sessions/$sid/shots" -Method Post -Headers $headers -Body (@{ result = "miss"; client_timestamp = $ts; shot_no = 2 } | ConvertTo-Json) | Out-Null

Write-Host "4) Finish"
$summary = Invoke-RestMethod -Uri "$base/api/v1/sessions/$sid/finish" -Method Post -Headers $headers -Body "{}"
$summary | ConvertTo-Json -Depth 5

Write-Host "Files:"
Write-Host "  raw:      cloud/data/raw/$sid/"
Write-Host "  protocol: cloud/data/protocol/$sid/"
