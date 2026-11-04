$ErrorActionPreference = "Stop"

$dockerExe = "$env:LOCALAPPDATA\Programs\DockerDesktop\Docker Desktop.exe"

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker 引擎未运行，正在启动 Docker Desktop..."
    Start-Process $dockerExe
    while ($true) {
        Start-Sleep -Seconds 3
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Write-Host "等待 Docker 引擎..."
    }
}

Set-Location $PSScriptRoot
docker compose up -d
docker compose ps
