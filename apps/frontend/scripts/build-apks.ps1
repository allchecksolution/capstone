param([string]$Flutter='flutter')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
foreach($role in @('admin','driver')){
 $defines=@()
 if($env:API_BASE_URL){$defines+="--dart-define=API_BASE_URL=$env:API_BASE_URL"}
 if($role -eq 'driver' -and $env:KAKAO_NATIVE_APP_KEY){
   $defines+="--dart-define=KAKAO_NATIVE_APP_KEY=$env:KAKAO_NATIVE_APP_KEY"
 }
 Push-Location (Join-Path $root $role)
 try{& $Flutter build apk --debug @defines; if($LASTEXITCODE -ne 0){throw "$role APK build failed"}; Copy-Item -LiteralPath 'build/app/outputs/flutter-apk/app-debug.apk' -Destination (Join-Path $root "allcheck-$role-flutter-debug.apk") }finally{Pop-Location}
}
