[CmdletBinding()]
param([switch]$IncludeRedis)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
foreach($taskName in @('process.json','redis-process.json')) {
  if($taskName -eq 'redis-process.json' -and !$IncludeRedis) { continue }
  $taskPath=Join-Path $taskRoot ('.local\'+$taskName)
  if(!(Test-Path -LiteralPath $taskPath)) { continue }
  $taskRecord=Get-Content -Raw -LiteralPath $taskPath | ConvertFrom-Json
  $taskProc=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskRecord.pid)" -ErrorAction SilentlyContinue
  if($taskProc) {
    $taskExpected=if($taskName -eq 'process.json'){ $taskRecord.jar }else{ $taskRecord.exe }
    if(([datetime]$taskProc.CreationDate).ToUniversalTime() -ne ([datetime]$taskRecord.startedAt).ToUniversalTime() -or $taskProc.CommandLine -notlike "*$taskExpected*") { throw 'Saved PID belongs to another process. It will not be stopped.' }
    if($taskName -eq 'redis-process.json') { & (Join-Path (Split-Path -Parent $taskRecord.exe) 'redis-cli.exe') -h 127.0.0.1 -p $taskRecord.port shutdown }
    else { Stop-Process -Id $taskRecord.pid -Force; Wait-Process -Id $taskRecord.pid -Timeout 10 -ErrorAction SilentlyContinue }
    Write-Output "Stopped NovaMarket component: $taskName"
  }
  Remove-Item -LiteralPath $taskPath -Force
}
