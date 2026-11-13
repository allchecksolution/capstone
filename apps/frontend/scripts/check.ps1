param([string]$Flutter='flutter')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $root 'shared')
try{ & $Flutter analyze; if($LASTEXITCODE -ne 0){throw 'Flutter analysis failed'}; & $Flutter test; if($LASTEXITCODE -ne 0){throw 'Flutter tests failed'}; & $Flutter test --dart-define=ALLCHECK_DEV_ACCESS=true test/development_access_test.dart; if($LASTEXITCODE -ne 0){throw 'Development access tests failed'} } finally{Pop-Location}
foreach($role in @('admin','driver')){Push-Location (Join-Path $root $role);try{& $Flutter analyze; if($LASTEXITCODE -ne 0){throw "$role analysis failed"}; & $Flutter build web; if($LASTEXITCODE -ne 0){throw "$role web build failed"}}finally{Pop-Location}}
