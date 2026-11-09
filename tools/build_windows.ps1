$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$buildTemp = Join-Path $projectRoot 'tmp/windows-build'
New-Item -ItemType Directory -Path $buildTemp -Force | Out-Null
$env:TEMP = $buildTemp
$env:TMP = $buildTemp
$env:PYINSTALLER_CONFIG_DIR = Join-Path $buildTemp 'pyinstaller-cache'
$env:PIP_CACHE_DIR = Join-Path $projectRoot 'tmp/pip-cache'
& ./.venv/Scripts/python -m PyInstaller desktop.spec --noconfirm --distpath tmp/windows-build/runtime --workpath tmp/windows-build/work
if ($LASTEXITCODE -ne 0) { throw 'Windows EXE 打包失败。' }
$exe = Join-Path $projectRoot 'dist/windows/Shizi.exe'
$innerExe = Join-Path $buildTemp 'runtime/Shizi.exe'
New-Item -ItemType Directory -Path (Split-Path $exe -Parent) -Force | Out-Null
$csc = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw '系统缺少 Windows 自带的 .NET Framework 编译器。' }
$launcherSource = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'PortableLauncher.cs')).Path
& $csc /nologo /target:winexe /platform:x64 /optimize+ /reference:System.Windows.Forms.dll "/resource:$innerExe,ShiziRuntime.exe" "/out:$exe" $launcherSource
if ($LASTEXITCODE -ne 0) { throw '便携启动器编译失败。' }
$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $exe
[IO.File]::WriteAllText((Join-Path $projectRoot 'dist/windows/SHA256.txt'), $hash.Hash.ToLowerInvariant() + '  Shizi.exe' + [Environment]::NewLine)
Get-Item -LiteralPath $exe | Select-Object FullName,Length
