param([switch]$Test)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
$gradle = Join-Path $toolchain 'gradle-8.11.1/bin/gradle.bat'
$tasks = @(':app:assembleDebug', ':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintDebug')
if ($Test) { $tasks += ':app:connectedDebugAndroidTest' }
& $gradle -p $PSScriptRoot @tasks --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Android 构建失败，请检查上面的错误。' }
