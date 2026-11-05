[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status', 'build', 'restart-backend')]
    [string]$Action = 'start',
    [switch]$SkipBackend,
    [switch]$SkipBuild,
    [switch]$OpenBrowser
)

$ErrorActionPreference = 'Stop'
$repoDir = $PSScriptRoot
$infraDir = Join-Path $repoDir 'infra'
$webDir = [IO.Path]::GetFullPath((Join-Path $repoDir '..\free-content-web'))
$runtimeDir = Join-Path $repoDir '.local-dev'
$stateFile = Join-Path $runtimeDir 'backend.json'
$workerFile = Join-Path $repoDir 'scripts\start-backend.ps1'
$entryUrl = 'http://localhost:8088'
$infraArgs = @('compose', '--project-directory', $infraDir, '--env-file', (Join-Path $infraDir '.env'), '-f', (Join-Path $infraDir 'docker-compose.yml'))
$webArgs = @('compose', '--project-directory', $infraDir, '-f', (Join-Path $infraDir 'docker-compose.web.yml'))

function Invoke-Docker([string[]]$DockerArguments) {
    & docker @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE)." }
}

function Test-DockerEngine {
    try {
        & docker info --format '{{.ServerVersion}}' *> $null
        return ($LASTEXITCODE -eq 0)
    } catch { return $false }
}

function Wait-DockerEngine {
    if (Test-DockerEngine) { return }
    $desktop = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop)) {
        $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
    }
    if (-not (Test-Path -LiteralPath $desktop)) { throw 'Start Docker Desktop, then run this command again.' }
    Write-Host 'Starting Docker Desktop (waiting up to 120 seconds)...'
    Start-Process -FilePath $desktop -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(120)
    do {
        if (Test-DockerEngine) { return }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw 'Docker Desktop did not become ready within 120 seconds.'
}

function Test-Http([string]$Url, [switch]$Backend) {
    $response = $null
    try {
        $request = [Net.HttpWebRequest]::Create($Url)
        $request.Proxy = $null
        $request.Timeout = 3000
        $request.ReadWriteTimeout = 3000
        $response = $request.GetResponse()
        if ($Backend) {
            $reader = New-Object IO.StreamReader($response.GetResponseStream())
            try { return (($reader.ReadToEnd() | ConvertFrom-Json).status -eq 'UP') }
            finally { $reader.Dispose() }
        }
        return ([int]$response.StatusCode -eq 200)
    } catch { return $false }
    finally { if ($response) { $response.Dispose() } }
}

function Get-ManagedBackend {
    if (-not (Test-Path -LiteralPath $stateFile)) { return $null }
    $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($state.processId)" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -and
        $process.CommandLine.Contains($workerFile) -and
        $process.CommandLine.Contains($state.instanceId)) { return $process }
    return $null
}

function Stop-ManagedBackend {
    $process = Get-ManagedBackend
    if ($process) {
        # Kill only our verified wrapper and its Maven/Java descendants.
        & taskkill.exe /PID $process.ProcessId /T /F
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the managed backend process tree.' }
    } else { Write-Host 'No managed backend to stop; IDE/manual processes are left running.' }
    if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile }
}

function Start-Backend {
    $healthUrl = 'http://127.0.0.1:8080/actuator/health'
    if (Test-Http $healthUrl -Backend) {
        Write-Host 'Reusing the healthy backend on :8080.'
        return
    }
    $process = Get-ManagedBackend
    if (-not $process) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
        if ($listener) { throw 'Port 8080 is occupied, but backend health is not UP. Check the running application first.' }
        Get-Command mvn.cmd, java -ErrorAction Stop | Out-Null
        $instanceId = [guid]::NewGuid().ToString()
        $process = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f $workerFile), '-InstanceId', $instanceId
        ) -WorkingDirectory (Join-Path $repoDir 'backend') -WindowStyle Hidden -PassThru
        @{ processId = $process.Id; instanceId = $instanceId } | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
    }
    Write-Host "Waiting for backend health (up to 180 seconds). Logs: $runtimeDir"
    $deadline = (Get-Date).AddSeconds(180)
    do {
        if (Test-Http $healthUrl -Backend) { return }
        if (-not (Get-ManagedBackend)) { throw "Backend exited. See $runtimeDir\backend.stderr.log and backend.stdout.log" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Backend health did not become UP. See logs in $runtimeDir"
}

function Build-Frontend {
    Get-Command pnpm.cmd -ErrorAction Stop | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $webDir 'node_modules'))) {
        throw "Install frontend dependencies first: cd '$webDir'; pnpm install --frozen-lockfile"
    }
    Push-Location $webDir
    try {
        & pnpm.cmd run build:local
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed; Nginx has not been started/restarted.' }
    } finally { Pop-Location }
}

function Assert-LocalBuild {
    $marker = Join-Path $webDir 'dist\local-build.json'
    if (-not (Test-Path -LiteralPath (Join-Path $webDir 'dist\index.html')) -or -not (Test-Path -LiteralPath $marker)) {
        throw 'No real/same-origin build found. Run dev.ps1 build first, or omit -SkipBuild.'
    }
    $metadata = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
    if ($metadata.apiMode -ne 'real' -or $metadata.apiBaseUrl -ne '') { throw 'Invalid local build mode.' }
}

if ($Action -eq 'status') {
    if (Test-DockerEngine) {
        Invoke-Docker ($webArgs + @('ps'))
        Invoke-Docker ($infraArgs + @('ps'))
    } else { Write-Host 'Docker engine is not available.' }
    Write-Host "Nginx ready: $(Test-Http "$entryUrl/nginx-health")"
    Write-Host "Backend UP: $(Test-Http 'http://127.0.0.1:8080/actuator/health' -Backend)"
    Write-Host "Managed backend: $([bool](Get-ManagedBackend))"
    exit 0
}

New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
$lock = $null
try {
    try { $lock = [IO.File]::Open((Join-Path $runtimeDir 'command.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another dev.ps1 command is running. Wait for it to finish.' }

    switch ($Action) {
        'build' { Build-Frontend; break }
        'stop' {
            Stop-ManagedBackend
            if (Test-DockerEngine) { Invoke-Docker ($webArgs + @('stop')) }
            Write-Host 'Stopped managed backend and Nginx. Middleware and data are preserved.'
            break
        }
        'restart-backend' {
            if (-not (Get-ManagedBackend) -and (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)) {
                throw 'Backend :8080 was started outside this script. Restart it in your IDE/terminal.'
            }
            Wait-DockerEngine
            Stop-ManagedBackend
            Start-Backend
            break
        }
        'start' {
            if (-not (Test-Path -LiteralPath (Join-Path $infraDir '.env'))) { throw 'infra/.env is missing. Copy and configure infra/.env.example first.' }
            Wait-DockerEngine
            if (-not $SkipBuild) { Build-Frontend }
            Assert-LocalBuild
            Invoke-Docker ($infraArgs + @('up', '-d', '--wait', '--wait-timeout', '120', 'mysql', 'redis', 'rabbitmq', 'minio', 'mailpit'))
            Invoke-Docker ($infraArgs + @('up', '-d', 'minio-init'))
            if (-not $SkipBackend) { Start-Backend }
            Invoke-Docker ($webArgs + @('up', '-d', '--wait', '--wait-timeout', '45'))
            if (-not (Test-Http "$entryUrl/nginx-health")) { throw 'Nginx did not answer on :8088.' }
            if (-not $SkipBackend -and -not (Test-Http "$entryUrl/actuator/health" -Backend)) {
                throw 'Backend is healthy on :8080, but Nginx forwarding failed. Check Docker Desktop host networking/firewall.'
            }
            Write-Host "Ready: $entryUrl"
            Write-Host "API docs: $entryUrl/swagger-ui.html"
            if ($SkipBackend) { Write-Host 'Backend is managed by your IDE. API requests return 502 until it is listening on :8080.' }
            if ($OpenBrowser) { Start-Process $entryUrl }
            break
        }
    }
} finally { if ($lock) { $lock.Dispose() } }
