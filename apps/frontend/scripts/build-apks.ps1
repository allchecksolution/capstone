param([string]$Flutter='flutter')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
foreach($role in @('admin','driver')){
 Push-Location (Join-Path $root $role)
 try{& $Flutter build apk --debug; if($LASTEXITCODE -ne 0){throw "$role APK build failed"}; Copy-Item -LiteralPath 'build/app/outputs/flutter-apk/app-debug.apk' -Destination (Join-Path $root "allcheck-$role-flutter-debug.apk") }finally{Pop-Location}
}
