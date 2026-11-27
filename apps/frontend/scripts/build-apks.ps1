param(
 [string]$Flutter='flutter',
 [string]$DefinesFile=(Join-Path $PSScriptRoot '../.env')
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$definesPath=(Resolve-Path -LiteralPath $DefinesFile -ErrorAction Stop).Path
foreach($role in @('admin','driver')){
 Push-Location (Join-Path $root $role)
 try{& $Flutter build apk --debug "--dart-define-from-file=$definesPath"; if($LASTEXITCODE -ne 0){throw "$role APK build failed"}; Copy-Item -LiteralPath 'build/app/outputs/flutter-apk/app-debug.apk' -Destination (Join-Path $root "allcheck-$role-flutter-debug.apk") }finally{Pop-Location}
}
