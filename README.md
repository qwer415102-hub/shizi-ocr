# 拾字 · 图片与 PDF 文字工作台

支持 **Windows 免安装 EXE、安卓 APK 和本地网页** 的中英文文字识别工具。导入图片或 PDF，离线提取文字，编辑校对后联网翻译成中文，并复制或导出 TXT。

## 直接下载

| 平台 | 下载 | 使用方式 |
| --- | --- | --- |
| Windows 10/11 64 位 | [免安装 EXE（约 132 MB）](https://github.com/qwer415102-hub/shizi-ocr/releases/latest/download/Shizi.exe) | 放在可写文件夹，双击即用，无需 Python |
| Android 6.0 及以上 | [安卓 APK（约 44 MB）](https://github.com/qwer415102-hub/shizi-ocr/releases/latest/download/Shizi-1.0.0-android.apk) | 安装后直接使用，无需电脑或 Google Play 服务 |

[查看发行版与校验文件](https://github.com/qwer415102-hub/shizi-ocr/releases/latest)。安装包已内置运行环境和识别模型，首次使用也可断网识别；翻译需要联网。安卓端断网点击翻译时，会提示开启 Wi-Fi 或移动数据，并提供网络设置入口。

Windows 版自动在默认浏览器打开界面，关闭「拾字」启动窗口即可退出。运行时在 EXE 旁边临时解包，正常退出后清理；放在 D 盘使用时，解包文件也在 D 盘。

另已提供独立 **Android APK**：`dist/android/Shizi-1.0.0-android.apk`。手机直接安装，无需 Python、电脑或 Google Play 服务；支持首次断网识别、PDF 分页识别，以及翻译断网时提示开启网络。详见 [Android 使用与构建说明](android/README.md)。Android 构建工具、缓存和输出均在 D 盘当前项目内。

**Windows 免安装版**已提供：只需 `dist/windows/Shizi.exe` 一个文件，双击后自动打开界面，运行环境和离线识别模型均已内置。放在 D 盘运行，临时解包也在 EXE 旁边，退出后清理。无需执行下面的 Python 安装步骤，详情见 [Windows 说明](WINDOWS.md)。

## 启动

本机已安装运行依赖，双击 `start.bat` 即可。启动后会打开 <http://127.0.0.1:8765>，关闭运行窗口即可停止服务。

新电脑需要 **64 位 Python 3.14**。首次运行会自动创建 `.venv` 并安装 `requirements.txt` 中的依赖，安装时需要联网。

也可以在项目目录运行：

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python run.py
```

## 使用

1. 点击上传或拖入一个文件。支持 PNG、JPG、WEBP、BMP、TIFF、PDF，最大 40 MB，图片最大 4000 万像素。多帧图片目前只处理第一帧。
2. PDF 可填写 `1-5,8` 这样的页码范围。留空处理全部页面，每次最多 100 页。
3. 点击「开始识别」。智能模式优先提取 PDF 的原生文字，文字不足的扫描页自动使用 OCR。图文混合页或有错误文字层的 PDF，可改用「全页 OCR」。
4. 切换页面，按需对照文件预览校对原文。置信度低于 85% 的 OCR 行会触发提示。
5. 点击「翻译为中文」翻译当前页。默认使用 MyMemory，Google 翻译为备用。原文编辑后，旧译文会清空，以免与原文不一致。
6. 「复制」复制当前页；「导出 TXT」导出全部已识别页面，或全部已翻译页面。尚未翻译的页面不会出现在译文导出中。

## 数据与效果边界

- 识别和文件预览在本机进行；应用不主动保存上传文件和识别记录，刷新页面后结果丢失，重要内容请导出。上传文件在请求期间可能由框架暂存到系统临时目录，请求结束后关闭清理。
- RapidOCR 当前安装包已包含默认中英文模型，模型就绪后可离线识别。更换模型或缺少模型时可能需要联网下载。
- 翻译需要联网，会发送当前页文字到用户所选的第三方服务。免费服务有配额、限流和网络可达性限制，失败时会保留原文并显示错误。当前环境实测 MyMemory 可用，Google 返回 HTTP 429，因此默认使用 MyMemory。
- 这版面向清晰印刷体和普通文档，输出纯文字。手写、模糊照片、复杂多栏、公式和表格无法保证准确或完整还原版式，应对照原图校对。OCR 置信度不是准确率承诺。
- 带密码的 PDF 请先解密。界面仅监听 `127.0.0.1`，供当前电脑使用。

## 开源方案选择

按需求先调研并复用成熟方案，没有从零训练 OCR 模型：

| 方案 | 用途与选择 |
| --- | --- |
| [RapidOCR](https://github.com/RapidAI/RapidOCR) | 本项目采用的 OCR 引擎，基于 PaddleOCR 模型，使用 ONNX Runtime 在 CPU 上运行，Apache-2.0 |
| [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) | 调研对照；功能和文档解析能力更全面，本版通过 RapidOCR 复用其模型，降低部署复杂度 |
| [pypdfium2](https://github.com/pypdfium2-team/pypdfium2) | PDF 原生文字提取、扫描页渲染；遵循 PDFium 不可并发调用的要求，识别过程加锁 |
| [FastAPI](https://github.com/fastapi/fastapi) | 本地服务；界面使用原生 HTML / CSS / JavaScript，无 Node 构建步骤 |
| [MyMemory](https://mymemory.translated.net/doc/spec.php) | 默认在线英译中服务，按免费接口字节上限分块 |

依赖及模型版权属于各自权利人；分发打包版本时需保留依赖对应的许可证和模型声明。

## 验证

```powershell
.\.venv\Scripts\python -m pip install -r requirements-dev.txt
.\.venv\Scripts\python -m pytest -q
```

13 项自动测试覆盖真实中英文 OCR、扫描 PDF、文字型 PDF 分页提取、页码和非法文件处理、翻译断网提示与分块完整性。中英文 OCR 测试使用 Windows 微软雅黑字体。

另外已在 Chrome 中实测上传、识别、在线翻译、TXT 下载，以及 390px 手机宽度布局；无 JavaScript 错误或横向溢出。
