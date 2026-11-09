# Windows 单文件免安装版

成品为 `dist/windows/Shizi.exe`，约 132 MB。只需发送这一个 EXE，不需要项目源码、Python、模型文件夹、安装程序或管理员权限。

支持 Windows 10 / 11 x64，使用系统内置的 .NET Framework 和默认浏览器。双击启动后自动打开本地网页界面，启动窗口提供「打开使用界面」与「退出软件」。关闭启动窗口会停止本地服务。

识别功能与现有网页版本相同：中英文图片、扫描 PDF、文字型 PDF、PDF 页码选择、编辑、复制和 TXT 导出；模型内置，可离线识别。翻译需要联网，失败时保留原文。

## 磁盘位置

为遵守不安装到 C 盘的要求，构建和运行均控制磁盘位置：

- 构建依赖安装在项目 `.venv`，下载缓存位于 `tmp/pip-cache`。
- PyInstaller 工作文件和缓存位于 `tmp/windows-build`。
- EXE 位于 `dist/windows`。
- 程序运行时，用一个小型 Windows 启动器，将内置程序解包到 **EXE 旁边** 的随机 `.shizi-run-*` 目录；内部 Python 环境、OCR 模型和上传文件临时缓存也在其中。正常退出后自动清理。
- 不需要修改系统 PATH、注册表或安装系统服务。放在 D 盘运行，应用解包文件也在 D 盘。EXE 目录必须可写，异常关机/强制终止可能留下临时目录。

Windows 系统组件和默认浏览器仍由操作系统管理。

## 构建

先调研并采用成熟的 [PyInstaller 单文件方案](https://pyinstaller.org/en/stable/usage.html)。沿用现有 RapidOCR / ONNX Runtime / PDFium 引擎，把运行环境、模型、网页资源一起打包；便携启动器负责把运行临时目录绑定到 EXE 所在磁盘。

在项目根目录执行：

```powershell
$env:TEMP = 'D:/AI Coding/Image to Text/tmp'
$env:TMP = $env:TEMP
$env:PIP_CACHE_DIR = 'D:/AI Coding/Image to Text/tmp/pip-cache'
.\.venv\Scripts\python -m pip install pyinstaller==6.22.3
.\tools\build_windows.ps1
```

构建脚本使用 Windows 已有的 .NET Framework 编译器创建便携启动器，不在 C 盘安装编译器。依赖和模型的版权、许可证属于相应项目，分发时保留原有许可。

## 验证

```powershell
.\.venv\Scripts\python -m pytest -q
.\.venv\Scripts\python tools/verify_windows.py
```

独立验证将 EXE 单独复制到一个含中文名称的空文件夹，清除子进程中的 Python 环境变量和 Python PATH，并将对外请求代理指向不可用地址：

- 从与 EXE 不同的工作目录启动，确认实际解包位置在 EXE 旁边。
- 检查网页及静态资源可用。
- 实际识别中英文混合图片和扫描 PDF，提取文字型 PDF。
- 翻译连接失败时返回清晰提示，并保留原文。
- 实际启动打包后的 Tk 窗口，确认 Tcl/Tk 资源齐全并可正常退出。
- 关闭程序后确认空文件夹重新只剩一个 EXE。

验证结果保存在 `dist/windows/verification.json`，SHA-256 校验值在同目录的 `SHA256.txt`。这些文件只用于核验，使用者只需要 EXE。
