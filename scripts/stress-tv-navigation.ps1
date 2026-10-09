param([ValidateSet('Baseline','Stress')][string]$Mode='Stress', [string]$Device='emulator-5556', [string]$ReportDirectory='.\verification\navigation', [string]$Adb=(Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
$ErrorActionPreference='Stop'


$root=[IO.Path]::GetFullPath($ReportDirectory)
New-Item -ItemType Directory -Path $root -Force | Out-Null
$apps=@(
 @{package='com.example.ezchupdate.tvtest';activity='com.example.ezchupdate.MainActivity';name='update'},
 @{package='com.example.homeezch.usb';activity='com.example.homeezch.MainActivity';name='launcher'}
)
$results=@()
foreach($app in $apps){
 $package=$app.package
 foreach($round in 1..5){
  & $adb -s $device shell am force-stop $package | Out-Null
  $out=& $adb -s $device shell am start -W -n "$package/$($app.activity)"
  if(($out -join [Environment]::NewLine) -notmatch 'Status: ok'){throw "Launch failed: $package"}
  $total=($out | Select-String '^TotalTime:').ToString() -replace '\D',''
  $results+= [pscustomobject]@{App=$app.name;Mode=$Mode;Round=$round;TotalTimeMs=[int]$total}
 }
 if($Mode -eq 'Stress'){
  & $adb -s $device shell dumpsys gfxinfo $package reset | Out-Null
  & $adb -s $device shell dumpsys meminfo $package | Out-File "$root/020-$($app.name)-memory-before.txt" -Encoding utf8
  $log=& $adb -s $device shell monkey -p $package --pct-nav 100 --pct-majornav 0 --pct-syskeys 0 --throttle 20 -s 9020 -v 3000 2>&1
  $log | Out-File "$root/020-$($app.name)-monkey.txt" -Encoding utf8
  if(($log -join [Environment]::NewLine) -notmatch 'Events injected: 3000' -or ($log -join [Environment]::NewLine) -match '// CRASH:|// NOT RESPONDING:'){throw "Navigation stress failed: $package"}
  & $adb -s $device shell dumpsys gfxinfo $package | Out-File "$root/020-$($app.name)-frames.txt" -Encoding utf8
  & $adb -s $device shell dumpsys meminfo $package | Out-File "$root/020-$($app.name)-memory-after.txt" -Encoding utf8
  & $adb -s $device shell screencap -p /sdcard/ezch-stress.png
  & $adb -s $device pull /sdcard/ezch-stress.png "$root/020-$($app.name).png" | Out-Null
 }
}
$results | Export-Csv "$root/020-$Mode-launches.csv" -NoTypeInformation -Encoding utf8
$results | Format-Table

