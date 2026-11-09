$projectRoot = Split-Path $PSScriptRoot -Parent
$toolchain = Join-Path $projectRoot 'tmp/android-toolchain'
$jdk = Get-ChildItem -LiteralPath $toolchain -Directory -Filter 'jdk-*' | Select-Object -First 1
if (-not $jdk) { throw '请先运行 .\.venv\Scripts\python tools/bootstrap_android.py，或使用 Android Studio 打开 android 目录。' }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $toolchain 'sdk'
$env:GRADLE_USER_HOME = Join-Path $toolchain 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $toolchain 'android-user'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $toolchain 'avd'
$env:TEMP = Join-Path $toolchain 'temp'
$env:TMP = $env:TEMP
$javaUserHome = Join-Path $toolchain 'user-home'
@($env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME, $env:TEMP, $javaUserHome) | ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $env:TEMP + '" -Duser.home="' + $javaUserHome + '"'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
