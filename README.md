# 3DPrintHelper

3DPrintHelper 是一个面向 3D 打印机的 Android 本地视觉巡检应用。它持续显示相机画面，按设定间隔从内存中的最新画面取帧，通过兼容 OpenAI Responses API 的视觉模型分析打印状态，并在发现明确异常时通过 Bark 推送到 iOS 设备。

应用还提供局域网 Web 控制台，可远程查看 MJPEG 实时画面、控制巡检和补光，并修改与手机端统一的设置。

## 主要功能

- 横屏相机预览，画面按 4:3 显示。
- 默认每 5 分钟自动分析，也可立即分析或暂停巡检。
- 图片只在内存中转换为 JPEG 并上传，不主动写入相册、缓存目录或外部存储。
- 自动闪光灯：画面较暗且手动补光关闭时，分析前短暂开启补光，取帧后关闭。
- 支持兼容 OpenAI Responses API 的模型、Base URL 和图像 detail 设置。
- 使用结构化 JSON 分析打印状态，并通过 Bark 推送明确异常。
- 局域网 Web 控制台，提供 MJPEG 预览、状态查看、控制操作和动态设置页。
- 前台 CameraX 预览与后台 Camera2 帧源切换。
- 前台保活服务、Partial WakeLock、WifiLock 和后台相机帧看门狗。
- 通过 Shizuku 调用显示电源隐藏 API，实现息屏但不主动锁屏；不可用时回退到黑色遮罩。
- 手机端与 Web 端共享同一份设置描述文件。

## 兼容性

- 最低系统：Android 7.0 / API 24。
- 重点目标设备：Android 7.1.1、arm64、16:9 横屏设备。
- 编译和目标 SDK：见 [`app/build.gradle.kts`](app/build.gradle.kts)。

后台相机是否能在真正锁屏后持续工作，仍可能受到设备厂商相机 HAL、电池优化和系统后台策略限制。应用会在后台画面长时间不更新时自动重启 Camera2 帧源，并在状态界面显示相机错误。

Android 13 及以上需要通知权限才能在普通通知栏显示保活通知。拒绝通知权限不会阻止前台服务运行，但用户可能无法从通知栏看到运行状态或使用通知退出入口。

## 安装与首次配置

### 1. 构建或安装 APK

使用 Android Studio 打开项目，或在项目根目录运行：

```bash
./gradlew :app:assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

将 APK 安装到目标 Android 设备。

### 2. 授权系统权限

首次启动时允许：

- 相机权限：用于预览、巡检分析和后台 Camera2 取帧。
- 通知权限：Android 13 及以上用于显示后台保活通知。

如果设备启用了严格的电池优化或自启动管理，建议允许应用后台运行。

### 3. 配置 Shizuku

息屏功能依赖 Shizuku：

1. 安装并启动 Shizuku。
2. 打开 3DPrintHelper。
3. 在 Shizuku 授权弹窗中允许本应用。

Shizuku 可用时，息屏按钮会关闭显示电源但不主动锁屏。Shizuku 不可用或调用失败时，应用使用黑色遮罩和最低亮度作为兜底。

### 4. 配置视觉模型

在设置页填写：

- OpenAI API Key。
- 模型名称，例如 `gpt-4.1-mini`。
- Base URL，默认 `https://api.openai.com/v1`。
- 图像 detail。
- 巡检提示词。

应用调用：

```text
POST {Base URL}/responses
```

因此也可使用实现兼容 Responses API 的服务。模型应返回包含以下字段的 JSON：

```json
{
  "status": "normal | warning | abnormal | unknown",
  "confidence": 0.0,
  "summary": "一句中文摘要",
  "abnormalReasons": ["异常原因"],
  "shouldNotify": false
}
```

### 5. 配置 Bark

填写完整 Bark URL，例如：

```text
https://api.day.app/xxxxxxxx/
```

应用只在结果明确异常或模型返回 `shouldNotify=true` 时推送，并遵守异常推送冷却时间。

## 使用说明

### 手机主页

主页包含相机预览和紧凑控制区，可执行：

- 开始或暂停巡检。
- 立即分析。
- 手动开关闪光灯。
- 进入设置。
- 息屏或点亮屏幕。

开启自动息屏后，开始巡检约 10 秒会通过与主页息屏按钮相同的入口触发息屏。黑色遮罩兜底模式下，可点击屏幕或按音量键恢复。

### 后台保活通知

启用“后台保活服务”后，应用启动时会启动前台服务。通知展示巡检状态、相机来源和 Web 地址。

通知正文会明确提示：

```text
点击通知将完全关闭 App
```

点击通知后会停止后台相机和服务、释放 WakeLock/WifiLock、移除通知并结束 App 进程。再次从桌面启动 App 后，会根据已保存的设置重新运行。

### Web 控制台

同一局域网设备访问：

```text
http://手机IP:端口/
```

默认端口由设置描述文件定义，当前默认值为 `8080`。Web 控制台支持：

- 查看 MJPEG 实时画面和分析状态。
- 开始或暂停巡检、立即分析。
- 开关闪光灯、远程息屏或点亮屏幕。
- 测试 Bark。
- 修改手机端的大部分设置。
- 调整 Web 预览分辨率百分比和帧率。

Web 设置页和手机设置页都根据 [`settings_schema.json`](app/src/main/assets/settings_schema.json) 动态生成。

## 安全与隐私

- 相机画面仅在内存中转换与缓存，不主动保存为图片文件。
- 分析时，JPEG 图像会发送到用户配置的 LLM API。
- OpenAI API Key 使用 Android Keystore 支持的 `EncryptedSharedPreferences` 保存。
- Web API 读取设置时不会明文返回 OpenAI API Key；Web 中留空提交表示不修改现有 Key。
- Bark URL 和普通设置保存在本地 DataStore。
- Web 控制台当前没有密码或访问鉴权，只应在可信局域网使用，不能直接暴露到公网。

## 技术架构

主要技术栈：

- Kotlin、Jetpack Compose、Material 3。
- CameraX Preview + ImageAnalysis：前台预览和取帧。
- Camera2 + ImageReader：后台/锁屏帧源。
- OkHttp：视觉模型和 Bark 请求。
- NanoHTTPD：局域网 Web 服务和 MJPEG 流。
- DataStore、EncryptedSharedPreferences：设置存储。
- Shizuku：显示电源控制。

核心数据流：

```text
CameraX/Camera2
    -> 内存 CapturedFrame
    -> MonitorController
    -> OpenAIVisionClient
    -> AnalysisResult
    -> 手机 UI / Web 状态 / BarkNotifier
```

关键模块：

```text
app/src/main/java/com/con11/a3dprinthelper/
├── camera/    前台 CameraX、后台 Camera2、内存帧转换
├── data/      AppSettings、统一设置 Schema、持久化
├── monitor/   应用级 MonitorController
├── network/   视觉模型与 Bark 客户端
├── power/     Shizuku 息屏控制
├── service/   后台保活前台服务
├── ui/        Compose 页面与状态适配
└── web/       NanoHTTPD、MJPEG 和 Web 控制台
```

## 设置维护

[`app/src/main/assets/settings_schema.json`](app/src/main/assets/settings_schema.json) 是手机端和 Web 端设置页面的统一字段描述来源，包含分组、控件类型、范围、单位、默认值和恢复动作。

新增或删除设置字段时，需要同步更新：

- `AppSettings`
- `SettingsCodec`
- `SettingsRepository`
- `settings_schema.json`
- `SettingsCodecTest`

## 开发与验证

构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

运行 Android Lint：

```bash
./gradlew :app:lintDebug
```

当前完整 Lint 会被 `MainActivity.dispatchKeyEvent` 的既有 `RestrictedApi` 错误阻止。通知和前台服务相关 Lint 检查当前无新增问题。

涉及相机、锁屏、后台服务或 Web MJPEG 的修改，除自动化验证外，还应在目标 Android 7.1.1 真机测试：

- 前台预览与立即分析后继续出帧。
- 前台、后台、锁屏和解锁时的相机交接。
- 锁屏期间 Web 状态和 MJPEG 持续更新。
- 后台帧停止后的自动恢复。
- Shizuku 息屏与兜底遮罩恢复。
- 点击保活通知后完全退出 App。

## 已知限制

- 真锁屏后的后台相机能力依赖设备系统与相机 HAL，普通应用无法在所有设备上保证持续取帧。
- Web 控制台无认证。
- 第一版不保存历史照片，仅保留运行状态、最近分析结果和必要设置。
- 应用将 API Key 保存在设备本地，这不等同于通过服务端代理隔离密钥。
