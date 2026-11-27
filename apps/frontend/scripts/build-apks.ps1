param(
 [string]$Flutter='flutter',
 [string]$DefinesFile=(Join-Path $PSScriptRoot '../.env'),
 [ValidatePattern('^[a-z0-9]+(-[a-z0-9]+)*$')]
 [string]$BuildLabel
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$definesPath=(Resolve-Path -LiteralPath $DefinesFile -ErrorAction Stop).Path
foreach($role in @('admin','driver')){
 # .env values are the default; API_BASE_URL/KAKAO_NATIVE_APP_KEY env vars override them.
 $defines=@()
 if($env:ALLCHECK_PUSH_ENABLED){$defines+="--dart-define=ALLCHECK_PUSH_ENABLED=$env:ALLCHECK_PUSH_ENABLED"}
 if($env:API_BASE_URL){$defines+="--dart-define=API_BASE_URL=$env:API_BASE_URL"}
 if($role -eq 'driver' -and $env:KAKAO_NATIVE_APP_KEY){
   $defines+="--dart-define=KAKAO_NATIVE_APP_KEY=$env:KAKAO_NATIVE_APP_KEY"
 }
 Push-Location (Join-Path $root $role)
 try{
  & $Flutter build apk --debug "--dart-define-from-file=$definesPath" @defines
  if($LASTEXITCODE -ne 0){throw "$role APK build failed"}
  $destination=Join-Path $root "allcheck-$role-flutter-debug.apk"
  Copy-Item -LiteralPath 'build/app/outputs/flutter-apk/app-debug.apk' -Destination $destination
  if($BuildLabel){
   Copy-Item -LiteralPath $destination -Destination (Join-Path $root "allcheck-$role-$BuildLabel-flutter-debug.apk")
  }
 }finally{Pop-Location}
}
