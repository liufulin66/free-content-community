$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot "..\infra\.env"
if (-not (Test-Path $envFile)) {
    throw "找不到 $envFile，请先启动 infra 目录下的中间件并确认 .env 存在"
}

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*)\s*$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

Write-Host "已加载 $envFile"
mvn spring-boot:run
