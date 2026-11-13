param([string]$Flutter = 'flutter', [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$flutterCommand=(Get-Command $Flutter -ErrorAction Stop).Source
$sdk=Split-Path -Parent (Split-Path -Parent $flutterCommand)
foreach($role in @('admin','driver')){
  $local=Join-Path $root "$role/android/local.properties"
  "sdk.dir=$($AndroidSdk.Replace('\','/'))`nflutter.sdk=$($sdk.Replace('\','/'))`n" | Set-Content -LiteralPath $local -Encoding ASCII
}
Push-Location (Join-Path $root 'shared')
try { & $flutterCommand pub get; if($LASTEXITCODE -ne 0){throw 'shared pub get failed'} } finally {Pop-Location}
foreach($role in @('admin','driver')){
  Push-Location (Join-Path $root $role)
  try{ & $flutterCommand pub get; if($LASTEXITCODE -ne 0){throw "$role pub get failed"} }finally{Pop-Location}
}
Write-Output 'Flutter dependencies and local SDK paths configured. Run scripts/check.ps1 next.'
