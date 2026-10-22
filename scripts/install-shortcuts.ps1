$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskDesktop=[Environment]::GetFolderPath('Desktop')
$taskShell=New-Object -ComObject WScript.Shell
foreach($taskItem in @(@{name='NovaMarket.lnk';file='Start-NovaMarket.cmd'},@{name='Stop NovaMarket.lnk';file='Stop-NovaMarket.cmd'})) {
  $taskLink=Join-Path $taskDesktop $taskItem.name
  $taskShortcut=$taskShell.CreateShortcut($taskLink)
  if((Test-Path -LiteralPath $taskLink) -and $taskShortcut.TargetPath -ne (Join-Path $taskRoot $taskItem.file)) { throw 'A shortcut with this name belongs to another project; it will not be replaced.' }
  $taskShortcut.TargetPath=Join-Path $taskRoot $taskItem.file
  $taskShortcut.WorkingDirectory=$taskRoot
  $taskShortcut.Description='NovaMarket local demonstration'
  $taskShortcut.Save()
  Write-Output "Created shortcut: $taskLink"
}
