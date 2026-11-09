$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'environment.ps1')
$unsignedApk = Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsignedApk)) { throw '请先运行 android/build.ps1。' }
$privateDir = Join-Path $projectRoot '.private/android-signing'
New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$keystore = Join-Path $privateDir 'shizi-release.p12'
$passwordFile = Join-Path $privateDir 'keystore-password.txt'
if (-not (Test-Path -LiteralPath $keystore)) {
    $passwordBytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
    [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($passwordBytes), [Text.Encoding]::ASCII)
    & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -keystore $keystore -storetype PKCS12 -alias shizi -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Shizi Local Build,O=Shizi,C=CN' -storepass:file $passwordFile -keypass:file $passwordFile
    if ($LASTEXITCODE -ne 0) { throw '签名密钥生成失败。' }
}
$dist = Join-Path $projectRoot 'dist/android'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$apk = Join-Path $dist 'Shizi-1.0.0-android.apk'
$signer = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat'
& $signer sign --ks $keystore --ks-key-alias shizi --ks-pass "file:$passwordFile" --out $apk $unsignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败。' }
& $signer verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败。' }
$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $apk
[IO.File]::WriteAllText((Join-Path $dist 'SHA256.txt'), $hash.Hash.ToLowerInvariant() + '  Shizi-1.0.0-android.apk' + [Environment]::NewLine)
Get-Item -LiteralPath $apk | Select-Object FullName,Length
