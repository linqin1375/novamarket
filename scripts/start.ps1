[CmdletBinding()]
param([int]$Port=8082,[switch]$NoBrowser,[switch]$Build,[switch]$Redis,[int]$RedisPort=6382)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
if($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }
$taskLocal=Join-Path $taskRoot '.local'
New-Item -ItemType Directory -Force -Path $taskLocal,(Join-Path $taskRoot 'logs'),(Join-Path $taskRoot 'data') | Out-Null
$taskJavaCandidates=@()
if($env:JAVA_HOME) { $taskJavaCandidates += Join-Path $env:JAVA_HOME 'bin\java.exe' }
$taskOnPath=Get-Command java.exe -ErrorAction SilentlyContinue
if($taskOnPath) { $taskJavaCandidates += $taskOnPath.Source }
foreach($taskJdkRoot in @((Join-Path $taskRoot '.tools'),"$env:ProgramFiles\Java","$env:ProgramFiles\Eclipse Adoptium","$env:USERPROFILE\.jdks")) {
  if(Test-Path -LiteralPath $taskJdkRoot) { $taskJavaCandidates += Get-ChildItem -LiteralPath $taskJdkRoot -Filter java.exe -Recurse -Depth 4 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName }
}
$taskJava=$null
foreach($taskCandidate in $taskJavaCandidates) {
  if(Test-Path -LiteralPath $taskCandidate) {
    $taskProbe=New-Object System.Diagnostics.Process
    $taskProbe.StartInfo.FileName=$taskCandidate
    $taskProbe.StartInfo.Arguments='-version'
    $taskProbe.StartInfo.UseShellExecute=$false
    $taskProbe.StartInfo.CreateNoWindow=$true
    $taskProbe.StartInfo.RedirectStandardError=$true
    [void]$taskProbe.Start()
    $taskVersion=$taskProbe.StandardError.ReadToEnd()
    $taskProbe.WaitForExit();$taskProbe.Dispose()
    if($taskVersion -match 'version "(\d+)' -and [int]$Matches[1] -ge 17) { $taskJava=$taskCandidate;break }
  }
}
if(!$taskJava) { throw 'JDK 17 or newer was not found. Set JAVA_HOME to your JDK directory.' }
$env:JAVA_HOME=Split-Path -Parent (Split-Path -Parent $taskJava)
$taskJar=Join-Path $taskRoot 'target\novamarket-1.0.0.jar'
$taskPidPath=Join-Path $taskLocal 'process.json'
$taskUrl="http://127.0.0.1:$Port"
if(Test-Path -LiteralPath $taskPidPath) {
  $taskExisting=Get-Content -Raw -LiteralPath $taskPidPath | ConvertFrom-Json
  $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskExisting.pid)" -ErrorAction SilentlyContinue
  if($taskProcess -and $taskProcess.CommandLine -like "*$taskJar*" -and ([datetime]$taskProcess.CreationDate).ToUniversalTime() -eq ([datetime]$taskExisting.startedAt).ToUniversalTime()) {
    if($Build -or $taskExisting.port -ne $Port -or [bool]$taskExisting.redis -ne [bool]$Redis) { throw 'NovaMarket is already running with different options. Stop it before changing options or rebuilding.' }
    $taskHealthy=Invoke-RestMethod "$taskUrl/api/metrics" -TimeoutSec 5
    if($taskHealthy.application -ne 'novamarket') { throw 'Unexpected service on the saved port.' }
    Write-Output "NovaMarket already running: $taskUrl"
    if(!$NoBrowser) { Start-Process $taskUrl }
    exit 0
  }
}
$taskPortUse=Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if($taskPortUse) { throw "Port $Port is occupied. Choose -Port with another port; no existing process was stopped." }
if($Build -or !(Test-Path -LiteralPath $taskJar)) {
  $taskMaven=Join-Path $taskRoot '.tools\apache-maven-3.9.16\bin\mvn.cmd'
  if(Test-Path -LiteralPath $taskMaven) { & $taskMaven -q package } else { & (Join-Path $taskRoot 'mvnw.cmd') -q package }
  if($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
}
if($Redis) { & (Join-Path $PSScriptRoot 'start-redis.ps1') -Port $RedisPort }
$taskArgs=@('-Dfile.encoding=UTF-8','-jar',('"'+$taskJar+'"'),'--spring.profiles.active=local',"--server.port=$Port")
if($Redis) { $taskArgs += '--novamarket.stock=redis',"--spring.data.redis.port=$RedisPort" }
$taskOut=Join-Path $taskRoot 'logs\server.out.log';$taskErr=Join-Path $taskRoot 'logs\server.err.log'
$taskChild=Start-Process -FilePath $taskJava -ArgumentList $taskArgs -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput $taskOut -RedirectStandardError $taskErr -PassThru
$taskCim=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskChild.Id)"
@{pid=$taskChild.Id;startedAt=([datetime]$taskCim.CreationDate).ToUniversalTime().ToString('o');port=$Port;redis=[bool]$Redis;redisPort=$RedisPort;jar=$taskJar;url=$taskUrl} | ConvertTo-Json | Set-Content -LiteralPath $taskPidPath -Encoding UTF8
for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++) {
  $taskChild.Refresh()
  if($taskChild.HasExited) { Get-Content -LiteralPath $taskOut -Tail 25; Get-Content -LiteralPath $taskErr -Tail 10; throw 'NovaMarket failed to start. Check logs.' }
  try {
    $taskHealth=Invoke-RestMethod "$taskUrl/api/metrics" -TimeoutSec 2
    if($taskHealth.application -eq 'novamarket') { Write-Output "NovaMarket running: $taskUrl ($($taskHealth.stockImpl))";if(!$NoBrowser){Start-Process $taskUrl};exit 0 }
  } catch { }
  Start-Sleep -Seconds 1
}
throw 'Startup health check timed out. Process details are saved in .local/process.json; use stop.ps1 before retrying.'
