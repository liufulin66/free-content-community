param([Parameter(Mandatory = $true)][string]$InstanceId)

$ErrorActionPreference = 'Stop'
# InstanceId is a process ownership marker; it is not a credential.
# The outer process is launched using ShellExecute, with no redirected handles.
# Keep log redirection inside this detached process, so callers do not wait on
# pipes inherited by the long-running Maven/Java process (Windows PowerShell 5).
$backendDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\backend'))
$runtimeDir = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\.local-dev'))
$runner = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"{0}"' -f (Join-Path $backendDir 'run-local.ps1'))
) -WorkingDirectory $backendDir -WindowStyle Hidden -PassThru -Wait `
    -RedirectStandardOutput (Join-Path $runtimeDir 'backend.stdout.log') `
    -RedirectStandardError (Join-Path $runtimeDir 'backend.stderr.log')
exit $runner.ExitCode
