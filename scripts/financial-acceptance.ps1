param()
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$database = "cums_financial_$PID"
$backendProcess = $null
$created = $false
$savedEnvironment = @{}
$taskTemp = [IO.Path]::GetTempPath()
$receiptDirectory = Join-Path $taskTemp "cums-financial-receipts-$PID"
try {
    foreach ($port in @(8085, 5185)) {
        $probe = [Net.Sockets.TcpClient]::new()
        try { $probe.Connect('localhost', $port); throw "Acceptance port $port is already in use." }
        catch [Net.Sockets.SocketException] {} finally { $probe.Dispose() }
    }
    $localEnvironment = Join-Path $workspace '.env'
    if (Test-Path -LiteralPath $localEnvironment) { Get-Content -LiteralPath $localEnvironment | ForEach-Object {
        $parts = $_.Split('=', 2)
        if ($parts.Count -eq 2 -and -not $parts[0].StartsWith('#')) {
            $savedEnvironment[$parts[0]] = [Environment]::GetEnvironmentVariable($parts[0], 'Process')
            [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
        }
    } }
    $overrides = @{
        DB_URL = "jdbc:postgresql://localhost:5432/$database"
        SPRING_PROFILES_ACTIVE = 'dev'
        DEV_SAMPLE_DATA = 'false'
        APP_MAIL_ENABLED = 'false'
        APP_ENCRYPTION_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        AUTH_THROTTLE_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        PAYMENT_PROVIDER = 'fake'
        APP_UPLOAD_DIR = $receiptDirectory
        CLAMAV_HOST = ''
        APP_GOOGLE_CLIENT_ID = ''
        APP_COOKIE_SECURE = 'false'
        APP_ALLOWED_ORIGIN = 'http://localhost:5185'
        SERVER_PORT = '8085'
        DEV_BOOTSTRAP_EMAIL = "financial-maker-$PID@invalid.example"
        DEV_BOOTSTRAP_PASSWORD = 'Initial ' + [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(30)) + '!'
        CUMS_FINANCIAL_DATABASE = $database
    }
    foreach ($key in $overrides.Keys) {
        if (-not $savedEnvironment.ContainsKey($key)) {
            $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        }
        [Environment]::SetEnvironmentVariable($key, $overrides[$key], 'Process')
    }
    docker compose --project-directory $workspace exec -T postgres createdb -U cums $database
    if ($LASTEXITCODE -ne 0) { throw 'Disposable database creation failed.' }
    $created = $true
    $startParameters = @{
        FilePath = 'java'; ArgumentList = @('-jar', 'target/backend-0.0.1-SNAPSHOT.jar')
        WorkingDirectory = Join-Path $workspace 'backend'; PassThru = $true
        RedirectStandardOutput = Join-Path $taskTemp "cums-financial-$PID.out.log"
        RedirectStandardError = Join-Path $taskTemp "cums-financial-$PID.err.log"
    }
    if ($IsWindows) { $startParameters.WindowStyle = 'Hidden' }
    $backendProcess = Start-Process @startParameters
    $ready = $false
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            if ((Invoke-RestMethod 'http://localhost:8085/api/v1/health' -TimeoutSec 2).status -eq 'UP') {
                $ready = $true; break
            }
        } catch {}
        if ($backendProcess.HasExited) { throw 'Disposable backend exited before readiness.' }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Disposable backend did not become healthy.' }
    node (Join-Path $workspace 'frontend/scripts/financial-acceptance.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Financial browser acceptance failed.' }
} finally {
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id -Force }
    if ($created) {
        docker compose --project-directory $workspace exec -T postgres dropdb -U cums --if-exists --force $database | Out-Null
    }
    $resolvedReceipt = [IO.Path]::GetFullPath($receiptDirectory)
    $separator = [IO.Path]::DirectorySeparatorChar
    $resolvedTemp = [IO.Path]::GetFullPath($taskTemp).TrimEnd($separator) + $separator
    if ($resolvedReceipt.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedReceipt)) {
        Remove-Item -LiteralPath $resolvedReceipt -Recurse -Force
    }
    foreach ($key in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
    }
}
