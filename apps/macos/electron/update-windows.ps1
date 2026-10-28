# SPDX-License-Identifier: AGPL-3.0-only
param([Parameter(Mandatory=$true)][string]$JobFile)
$ErrorActionPreference='Stop'
$j=Get-Content -LiteralPath $JobFile -Raw -Encoding UTF8 | ConvertFrom-Json
$backup=Join-Path (Split-Path -Parent $JobFile) 'previous'
$replaced=$false
try {
  for($i=0;$i -lt 90;$i++) { if(!(Get-Process -Id $j.parentPid -ErrorAction SilentlyContinue)){break};Start-Sleep -Seconds 1 }
  if(Get-Process -Id $j.parentPid -ErrorAction SilentlyContinue){throw 'Application is still running'}
  Copy-Item -LiteralPath $j.target -Destination $backup -Recurse
  $env:LAD_UPDATE_ACK=$j.ack
  if($j.installer) {
    $replaced=$true
    $arg='/S /D='+$j.target
    $p=Start-Process -FilePath $j.file -ArgumentList $arg -Wait -PassThru
    if($p.ExitCode -ne 0){throw 'Installer failed'}
  } else {
    $unpack=Join-Path (Split-Path -Parent $JobFile) 'unpacked'
    Expand-Archive -LiteralPath $j.file -DestinationPath $unpack
    $new=Join-Path $unpack 'Lad-Windows-x64'
    if(!(Test-Path -LiteralPath (Join-Path $new 'Lad.exe'))){throw 'Invalid portable package'}
    $v=[System.Diagnostics.FileVersionInfo]::GetVersionInfo((Join-Path $new 'Lad.exe')).ProductVersion
    if($v -notin @($j.version, ($j.version+'.0'))){throw 'Version mismatch'}
    $replaced=$true
    Remove-Item -LiteralPath $j.target -Recurse -Force
    Move-Item -LiteralPath $new -Destination $j.target
  }
  Start-Process -FilePath (Join-Path $j.target 'Lad.exe')
  for($i=0;$i -lt 90;$i++){if(Test-Path -LiteralPath $j.ack){break};Start-Sleep -Seconds 1}
  if(!(Test-Path -LiteralPath $j.ack)){throw 'Updated app did not confirm startup'}
  '{"ok":true}' | Set-Content -LiteralPath $j.journal -Encoding UTF8
  Remove-Item -LiteralPath $backup -Recurse -Force
} catch {
  if($replaced -and (Test-Path -LiteralPath $backup)) {
    Get-Process Lad,lad-backend -ErrorAction SilentlyContinue | Where-Object {$_.Path -and $_.Path.StartsWith($j.target+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)} | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Remove-Item -LiteralPath $j.target -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath $backup -Destination $j.target
    Remove-Item Env:LAD_UPDATE_ACK -ErrorAction SilentlyContinue
    Start-Process -FilePath (Join-Path $j.target 'Lad.exe')
  }
  '{"ok":false,"message":"Не удалось установить обновление. Предыдущая версия сохранена."}' | Set-Content -LiteralPath $j.journal -Encoding UTF8
  exit 1
}
