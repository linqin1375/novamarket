[CmdletBinding()]
param([int]$Port=6382)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskExe=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools\redis-8.2.3') -Filter redis-server.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if(!$taskExe) { throw 'Portable Redis was not found. See docs/DEPLOYMENT.md, or start without -Redis for the memory implementation.' }
$taskRecordPath=Join-Path $taskRoot '.local\redis-process.json'
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot '.local'),(Join-Path $taskRoot 'data\redis'),(Join-Path $taskRoot 'logs') | Out-Null
$taskCli=Join-Path (Split-Path -Parent $taskExe) 'redis-cli.exe'
if(Test-Path -LiteralPath $taskRecordPath) {
  $taskRecord=Get-Content -Raw -LiteralPath $taskRecordPath | ConvertFrom-Json
  $taskProc=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskRecord.pid)" -ErrorAction SilentlyContinue
  if($taskProc -and $taskProc.CommandLine -like "*$taskExe*" -and ([datetime]$taskProc.CreationDate).ToUniversalTime() -eq ([datetime]$taskRecord.startedAt).ToUniversalTime()) {
    if($taskRecord.port -ne $Port) { throw 'Owned Redis is already running on a different port.' }
    if((& $taskCli -h 127.0.0.1 -p $Port ping) -ne 'PONG') { throw 'Owned Redis failed its health check.' }
    Write-Output "NovaMarket Redis already running on port $Port"; return
  }
}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Redis port $Port is occupied; no existing service was changed." }
$taskConfig=Join-Path $taskRoot '.local\redis.conf'
function To-MsysPath([string]$taskPath) { return '/'+$taskPath.Substring(0,1).ToLower()+$taskPath.Substring(2).Replace('\','/') }
$taskData='data/redis'
@("bind 127.0.0.1","port $Port","protected-mode yes","daemonize no","dir `"$taskData`"",'appendonly yes','appendfsync everysec','save 60 1','dbfilename dump.rdb') | Set-Content -LiteralPath $taskConfig -Encoding ASCII
$taskChild=Start-Process -FilePath $taskExe -ArgumentList '.local/redis.conf' -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRoot 'logs\redis.out.log') -RedirectStandardError (Join-Path $taskRoot 'logs\redis.err.log') -PassThru
$taskCim=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskChild.Id)"
if(!$taskCim) { Get-Content -LiteralPath (Join-Path $taskRoot 'logs\redis.out.log') -Tail 10; throw 'Redis exited before its process record could be saved.' }
@{pid=$taskChild.Id;exe=$taskExe;port=$Port;startedAt=([datetime]$taskCim.CreationDate).ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $taskRecordPath -Encoding UTF8
for($taskAttempt=0;$taskAttempt -lt 20;$taskAttempt++) {
  $taskChild.Refresh(); if($taskChild.HasExited) { Get-Content -LiteralPath (Join-Path $taskRoot 'logs\redis.out.log') -Tail 10; throw 'Portable Redis failed to start.' }
  if((& $taskCli -h 127.0.0.1 -p $Port ping 2>$null) -eq 'PONG') { Write-Output "NovaMarket Redis running on port $Port";return }
  Start-Sleep -Milliseconds 500
}
throw 'Redis startup timeout. Inspect logs/redis.out.log.'
