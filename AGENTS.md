# AGENTS.md

本文件定义在此仓库中工作的编码代理必须遵守的工程事实、架构边界和验证流程。

## 项目事实

- 项目名：3DPrintHelper。
- 单模块 Android App：`:app`。
- 语言和 UI：Kotlin、Jetpack Compose、Material 3。
- 最低系统：Android 7.0 / API 24。
- 重点目标：Android 7.1.1 arm64 横屏设备，同时维持新 Android 版本兼容。
- 应用持续监控 3D 打印机，使用视觉 LLM 分析内存帧，并通过 Bark 推送异常。
- 局域网 Web 控制台无认证，只适合可信网络。

## 开始工作前

- 先阅读 `README.md`、相关实现和当前 `git status`。
- 工作区可能包含用户尚未提交的修改。不得重置、覆盖或清理与当前任务无关的改动。
- 使用现有架构和命名，不为局部修改引入新的框架或大范围重构。
- 手动编辑使用补丁方式，保持修改范围紧凑。

## 架构边界

### 业务状态

- `MonitorController` 是应用级业务状态和控制入口。
- Compose UI、`MonitoringService` 和 Web Server 必须通过同一个 `MonitorController` 协调状态。
- 不要在手机 UI、服务或 Web 层分别维护独立的巡检、闪光灯、息屏或服务状态。

### 相机与帧

- 前台预览使用 CameraX `Preview + ImageAnalysis`。
- 后台/锁屏帧源使用 `Camera2BackgroundFrameSource`。
- 前台 CameraX 和后台 Camera2 必须明确交接，禁止同时抢占相机。
- 前台暂停时应释放前台 use case，后台接管需要保留延迟、重试和帧看门狗。
- Web 和 LLM 读取 `MonitorController` 的最后有效内存帧，不直接依赖短暂失效的当前相机源。
- 图片只允许在内存中转换、缓存和上传。除非用户明确改变产品需求，不得新增图片落盘、相册写入或外部存储。
- 处理 Android 7 相机缓冲区时必须容忍异常 stride、短缓冲区和单帧转换失败，不能让分析线程崩溃。

### 巡检与网络

- OpenAI 客户端调用兼容 Responses API 的 `{baseUrl}/responses`。
- 模型返回应解析为 `AnalysisResult`；非 JSON 或未知状态必须安全降级为 `Unknown`。
- Bark 只在明确异常或 `shouldNotify=true` 时发送，并遵守冷却时间。
- API Key、Bark URL、提示词和图片不得写入日志。

## 设置维护规则

- `app/src/main/assets/settings_schema.json` 是手机端与 Web 端设置字段的统一描述来源。
- 手机设置页与 Web 设置页必须继续根据 Schema 动态生成，不得重新维护两份手写字段列表。
- `AppSettings` 是业务层类型化模型；Schema 负责页面描述、默认值和范围。
- 新增、删除或重命名设置字段时，必须同步更新：
  - `AppSettings`
  - `SettingsCodec`
  - `SettingsRepository`
  - `settings_schema.json`
  - `SettingsCodecTest.schemaContainsEveryAppSetting`
- 数值设置必须通过 `SettingsCodec` 按 Schema 范围规范化。
- OpenAI API Key 使用加密存储。Web API 不得明文回显，空白 Web 提交必须保留现有 Key。
- 恢复默认值应使用 Schema 的 `resetAction` 和统一默认设置接口，不在 Web 页面复制默认提示词。

## 后台、息屏与通知

- “后台保活服务”与巡检开关解耦；启用时 App 启动后即运行。
- 服务持有的 Camera2、Partial WakeLock、WifiLock、Handler 回调和通知必须在停止时成对释放。
- 后台 Web 是否持续运行由保活服务状态决定；App 前台时仍可提供 Web 服务。
- Shizuku 显示控制必须保留权限检查、UserService 预绑定、Binder 失效重连和黑色遮罩兜底。
- Shizuku 息屏、自动息屏、手机按钮和 Web 控制必须走同一业务入口。
- 保活通知点击会启动无界面的 `ExitAppActivity`，停止服务并完全结束 App 进程。不要把通知点击改回打开主页，也不要依赖 Android 7 可能不显示的通知 action 按钮。
- 修改通知或前台服务行为时，必须分别考虑：
  - Android 7：普通前台服务通知与 Camera2 后台行为。
  - Android 8+：通知渠道和 `startForegroundService`。
  - Android 12+：不可变 `PendingIntent` 和即时前台服务通知。
  - Android 13+：`POST_NOTIFICATIONS` 权限。
  - Android 14+：相机前台服务类型与权限。

## Web 规则

- Web Server 使用 NanoHTTPD，默认端口由 Schema 定义。
- 保持现有接口向后兼容：
  - `GET /`
  - `GET /stream.mjpg`
  - `GET /api/status`
  - `GET /api/settings`
  - `GET /api/settings/schema`
  - `GET /api/settings/defaults`
  - `POST /api/control`
  - `POST /api/settings`
- MJPEG 必须持续发送最后有效帧或占位帧，连接失败时 Web 页面应自动重连。
- Web 设置保存后应等待服务恢复并自动刷新；普通设置变化不得无条件重启 Web Server。
- 修改 Web 状态字段时，同时更新服务端 JSON 和 Web 页面读取逻辑。
- Web 页面当前无密码，不得在文案或实现中暗示它适合公网暴露。

## UI 规则

- 手机端以横屏 16:9 目标设备为主，预览画面保持 4:3。
- 主要状态放在相机画面左下叠加层，右侧控制区保持紧凑。
- 状态、按钮和文本必须在 Android 7 目标设备上可读，不依赖新系统专属视觉行为。
- Web 设置页分组高度按内容自适应；字段由 Schema 动态渲染。

## 验证要求

每次代码修改至少运行：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

涉及相机、服务、Manifest、通知、权限或 Android 版本分支时还应运行：

```bash
./gradlew :app:lintDebug
```

当前完整 Lint 存在既有 `MainActivity.dispatchKeyEvent` `RestrictedApi` 错误。不得把既有错误误报为当前修改引入的问题；同时必须检查 Lint 报告中是否新增了与当前修改相关的问题。

提交或交付前运行：

```bash
git diff --check
```

涉及相机、后台保活、息屏或 Web 预览时，自动化测试不足以替代真机验证。重点在 Android 7.1.1 真机检查：

- 前台预览持续更新，分析后不会卡帧。
- 前台到后台/锁屏时 Camera2 接管。
- Web MJPEG 和 `lastFrameAtMillis` 在锁屏期间继续更新。
- 后台帧停滞后看门狗自动恢复。
- 解锁后 CameraX 重新绑定且不与后台 Camera2 冲突。
- Shizuku 息屏、音量键/点击遮罩恢复。
- 点击保活通知后通知、服务、Web 和 App 进程全部退出。

## 不要做

- 不要使用 `unbindAll()` 让 UI 和服务互相解绑相机；只释放当前拥有的 use case。
- 不要让 Web Server 直接操作 Compose UI 或相机组件。
- 不要绕过 `SettingsCodec` 手工解析或限制设置值。
- 不要把 OpenAI API Key 返回到 Web 页面。
- 不要在每个相机帧上打印日志或重复压缩仅用于日志/状态展示。
- 不要在没有用户明确要求时保存照片、增加公网访问或添加认证行为。
- 不要清理、回退或重写用户当前工作区中的无关改动与生成文件。
