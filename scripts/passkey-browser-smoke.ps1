param()
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$database = "cums_passkey_$PID"
$profilePath = Join-Path $env:TEMP "cums-passkey-browser-$PID"
$backendProcess = $null
$browserProcess = $null

try {
    docker compose exec -T postgres psql -U cums -d postgres -c "CREATE DATABASE $database OWNER cums" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the disposable PostgreSQL database.' }
    Get-Content -LiteralPath (Join-Path $workspace '.env') | ForEach-Object {
        $parts = $_.Split('=', 2)
        if ($parts.Count -eq 2 -and -not $parts[0].StartsWith('#')) {
            [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
        }
    }
    $alphabet = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(30))
    $newAlphabet = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(30))
    $env:DB_URL = "jdbc:postgresql://localhost:5432/$database"
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:DEV_SAMPLE_DATA = 'false'
    $env:APP_MAIL_ENABLED = 'false'
    $env:DEV_BOOTSTRAP_EMAIL = "passkey-e2e-$PID@invalid.example"
    $env:DEV_BOOTSTRAP_PASSWORD = "Passkey $alphabet!"
    $env:CUMS_E2E_EMAIL = $env:DEV_BOOTSTRAP_EMAIL
    $env:CUMS_E2E_PASSWORD = $env:DEV_BOOTSTRAP_PASSWORD
    $env:CUMS_E2E_NEW_PASSWORD = "Changed $newAlphabet!"
    $backendProcess = Start-Process -FilePath 'java' -ArgumentList '-jar', 'target/backend-0.0.1-SNAPSHOT.jar' `
        -WorkingDirectory (Join-Path $workspace 'backend') -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $env:TEMP "cums-passkey-backend-$PID.out.log") `
        -RedirectStandardError (Join-Path $env:TEMP "cums-passkey-backend-$PID.err.log") -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/health' -TimeoutSec 2
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Disposable backend did not become healthy.' }
    $chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
    if (-not (Test-Path -LiteralPath $chrome)) { throw 'Chrome executable not found.' }
    $browserProcess = Start-Process -FilePath $chrome -ArgumentList '--headless=new', '--remote-debugging-port=9222',
        '--no-first-run', '--no-default-browser-check', '--remote-allow-origins=*',
        "--user-data-dir=$profilePath", 'about:blank' -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $tabs = Invoke-RestMethod -Uri 'http://127.0.0.1:9222/json/list' -TimeoutSec 2
            if ($tabs) { $ready = $true; break }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Chrome CDP did not become available.' }
    node (Join-Path $workspace 'frontend/scripts/passkey-browser-smoke.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Passkey browser smoke test failed.' }
} finally {
    if ($browserProcess -and -not $browserProcess.HasExited) { Stop-Process -Id $browserProcess.Id -Force }
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id -Force }
    docker compose exec -T postgres dropdb -U cums --if-exists --force $database | Out-Null
    $resolvedTemp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    $resolvedProfile = [IO.Path]::GetFullPath($profilePath)
    if ($resolvedProfile.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedProfile)) {
        for ($cleanupAttempt = 0; $cleanupAttempt -lt 5; $cleanupAttempt++) {
            try {
                Remove-Item -LiteralPath $resolvedProfile -Recurse -Force
                break
            } catch {
                if ($cleanupAttempt -eq 4) { throw }
                Start-Sleep -Milliseconds 500
            }
        }
    }
}
