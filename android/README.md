# 拾字 Android

独立安卓应用，支持 Android 6.0 及以上。图片、拍照和 PDF 的中英文识别均在手机本地执行，模型内置 APK，首次使用不需要下载模型，也无需电脑、Python 或 Google Play 服务。

## 给使用者

安装包位于 `../dist/android/Shizi-1.0.0-android.apk`，大小约 44 MB（42 MiB）。将 APK 传到手机，在文件管理器中点击安装；系统提示时允许当前来源安装应用。

- 图片：点击「导入图片 / PDF」或「拍照」，选择文件后点击「开始离线识别」。
- PDF：通过系统 PDF 渲染器逐页转为图片再本地识别，支持扫描 PDF 和文字型 PDF。可填写 `1-5,8` 页码范围，每次最多 100 页。文件上限 40 MB。
- 校对：可直接修改原文、切换页面，并展开当前页文件预览。
- 翻译：点击「翻译当前页为中文」。无可用网络时，提示开启 Wi-Fi 或移动数据，并提供「打开网络设置」。联网后返回应用再次点击翻译。
- 翻译中途断网或服务失败时，识别原文和已有译文保留。服务使用 MyMemory，免费接口可能限流或有额度限制。
- 复制针对当前页；导出 TXT 包含全部已识别页面或已翻译页面。结果在本次会话中保留，重要文字请及时导出。

识别不会上传图片或 PDF。点击翻译时，当前页文本会发送给 MyMemory。手写、模糊照片、公式及复杂排版应对照原文校对；本版输出纯文字。

## 构建与磁盘位置

本项目的工具、SDK、JDK、Gradle 缓存、模拟器、临时文件、签名密钥和 APK 都放在 **D 盘项目目录**。`environment.ps1` 显式设置 Android/Java/Gradle 的用户目录及临时目录，避免使用 C 盘默认构建缓存。

```powershell
# 一次性准备构建工具，下载到 tmp/android-toolchain
.\.venv\Scripts\python tools/bootstrap_android.py

# 编译、单元测试和 Android Lint
.\android\build.ps1

# 签名并输出 dist/android/Shizi-1.0.0-android.apk
.\android\package.ps1
```

这些操作在项目根目录执行，仅开发者需要；手机用户直接安装 APK。

如需模拟器测试：

```powershell
.\.venv\Scripts\python tools/bootstrap_android.py --emulator
. .\android\environment.ps1
# 创建/启动已有的 shizi-offline AVD 后执行：
.\android\build.ps1 -Test
```

发行签名密钥在 `.private/android-signing`，已被 Git 忽略。请单独备份该目录；后续覆盖安装更新必须使用同一密钥，密钥和密码不要随 APK 分发。当前为本地签名的直接安装版，尚未提交应用商店。

## 验证结果

- Debug、Release 编译成功，Android Lint 无错误。
- 6 项单元测试通过：PDF 页码解析、工作量限制、中英文检测、UTF-8 分块上限与内容完整性。
- Android 11 AOSP 模拟器（无 Google Play 服务）上，3 项设备测试通过：断网首次识别中英文图片、断网识别指定 PDF 页码、断网翻译提示与原文保留。
- 已安装最终签名 Release APK，在飞行模式下通过系统文件选择器导入测试图片并正确识别中英文；也验证了最终包的网络提示。
- 已核对最终 APK 含中文、拉丁文模型和 ARM64/ARM32/x86/x86_64 推理库，签名 v1/v2/v3 验证通过。
- 尚未在你的实体手机上验证。相机、系统文件管理器和网络设置入口可能存在厂商差异。

## 方案与来源

先调研了 [PaddleOCR 官方 Android 实现](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/inference_deployment/cross_platform/android_deployment.md) 与 [Google ML Kit 官方开源示例](https://github.com/googlesamples/mlkit/tree/master/android/vision-quickstart)。本版选用后者的内置模型集成方式，搭配原生 Android 界面和系统 PDF 渲染器，减少部署与第三方库体积。

- [ML Kit Android 文字识别文档](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)：`com.google.mlkit:text-recognition-chinese:16.0.1` 内置模型，不使用动态下载模型版本。SDK/模型遵循 Google 相关条款，并非本项目自行训练或拥有。
- [ML Kit 条款与隐私](https://developers.google.com/ml-kit/terms)：SDK 可能收集使用和性能统计，识别输入及结果在本机处理。
- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)：本地渲染 PDF。
- [MyMemory API](https://mymemory.translated.net/doc/spec.php)：在线英译中，按 UTF-8 字节限制分块，使用 HTTPS 和超时处理。
- AndroidX、Kotlin 等依赖由 Gradle 管理，保留各自的许可条款。
