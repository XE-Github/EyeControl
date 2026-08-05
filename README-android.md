# 开发者构建指南 · 眨眼控制 App

> 这是给**想自己编译源码**的开发者看的构建文档。只想安装使用请看 **[README.md](README.md)** 和 **[使用说明.md](使用说明.md)**。

整条链路:前置摄像头眨眼检测(与 Web 同款算法)→ 检测到手势(连眨 3 下 / 闭眼保持)→ **无障碍模拟滑动**,直接在官方**抖音 / 快手**里翻视频。眨眼检测算法(`BlinkDetector.kt`)从已验证的 Web Demo 逐段移植。

---

## 一、装 Android Studio（一次性）

1. 到 https://developer.android.com/studio 下载并安装 **Android Studio**（选最新稳定版即可）。
2. 首次打开，按向导让它**自动下载 SDK 和 JDK**（默认勾选即可，等它下完）。
3. 无需单独装 Gradle/命令行工具 —— Android Studio 自带。

---

## 二、打开本工程

1. Android Studio → **File → Open** → 选目录 `D:\EyeControl\android-app`。
2. 右下角会开始 **Gradle Sync**（首次要联网下依赖，几分钟）。
   - 如果提示 “Gradle wrapper 缺少 gradle-wrapper.jar”：点它建议的 **“Use Gradle wrapper / OK”**，或菜单 **File → Sync Project with Gradle Files**，Android Studio 会自动补齐 wrapper。
   - 若提示 JDK 版本：选 **JDK 17**（Android Studio 内置的即可）。
3. Sync 成功后，左侧能看到 `app` 模块，代码不报红即 OK。

> **模型文件说明**：本工程默认**首次运行时自动从 Google 下载**人脸模型 `face_landmarker.task`（约 3–4MB）到 App 私有目录，你什么都不用做，手机联网一次即可，之后缓存。
> 若你想**离线打包**（不想运行时下载）：把该文件放到 `app/src/main/assets/face_landmarker.task` 即可，App 会优先用它。下载地址：
> `https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task`

---

## 三、手机装机

1. 手机开 **开发者选项 → USB 调试**（你之前已开）。用 USB 连电脑。
2. 手机弹「是否允许 USB 调试」→ 勾「一律允许」→ 确定。
3. Android Studio 顶部设备下拉框选中你的手机。
4. 点绿色 **▶ Run**。首次编译 + 装机，等它自动装好并打开 App。

> 命令行备选（可选）：工程根目录执行 `gradlew.bat installDebug`（需已配 JDK/SDK 环境变量）。推荐还是用上面的 ▶ Run。

---

## 四、首次授权（App 内三步，缺一不可）

打开 App 后，按界面从上到下开三项，每项右侧应变成 ✅：

1. **① 摄像头** → 点「授权摄像头」→ 允许。
2. **② 悬浮窗** → 点「开启悬浮窗权限」→ 在系统页把「眨眼控制」允许显示在其他应用上层 → 返回。
3. **③ 无障碍** → 点「去开启无障碍」→ 在无障碍列表里找到 **「眨眼控制·滑动」** → 打开 → 系统会警告（这是所有无障碍应用都有的正常提示）→ 确认开启 → 返回。

三项都 ✅ 后，「▶ 开始」按钮可点。

---

## 五、实测

1. 点 **「▶ 开始」** → App 自动缩到后台，屏幕上出现一个**小悬浮胶囊**（显示「学习中」→ 正常看屏幕约 2 秒变「就绪」）。
2. 打开 **抖音** → 正常刷视频。
3. **快速连眨 3 下** → 向上滑到**下一个**视频，悬浮窗闪「⬆ 下一个」。
4. **闭眼保持约半秒** → 滑到**上一个**，悬浮窗闪「⬇ 上一个」。
5. 再打开 **快手**重复 3、4，确认跨 App 通用。
6. 停止：拉下通知栏，点「眨眼控制运行中」通知里的「停止」；或回 App 点「■ 停止」。

> 手势阈值(连眨下数、闭眼保持时长、灵敏度)均可在 App 设置内调整。

---

## 六、如果不灵，多半是这些（各家 ROM 的坑）

安卓各品牌（小米/红米、华为、OPPO/vivo、荣耀）对后台和悬浮窗限制不同，若「切到抖音后不翻页 / 悬浮窗消失 / 一会就失灵」，到系统设置里对本 App 打开：

- **自启动 / 允许后台活动**（小米叫「自启动」，华为叫「应用启动管理→手动管理」）。
- **省电策略 → 无限制 / 不受限**（关掉对本 App 的省电限制）。
- **允许后台弹出界面 / 显示在其他应用上层**（悬浮窗相关）。
- 把 App **锁进最近任务**（下拉锁图标），防止被一键清理。

无障碍被系统关掉的话，回第四步重新开启即可。

---

## 七、隐私

- 全程**本地处理**：摄像头画面只喂给本机的 MediaPipe 模型算眨眼，**不保存、不上传**。
- 唯一联网：首次下载模型文件（仅下载，不回传任何图像）。选择离线打包后可完全断网使用。
- 另有一个**可选的匿名活跃统计**（v1.1 起），首次打开会征求同意，不同意完全不影响功能。详见下节「匿名活跃统计」。

---

## 七·五、匿名活跃统计（v1.1 起，可选）

作者想知道**有多少人在用、常见机型/版本**，判断这东西对普通大众值不值得继续做——就这一个目的。整套设计围绕「诚实」和「隐私红线」两条原则展开。

### 诚实原则（看板绝不造假）

- 看板只展示**真实聚合数**：数据从设备直连上报到 Gitee 私库，GitHub Actions 每小时聚合成 `stats.json`，看板读这一个静态文件。拉不到就显式写「数据加载失败」，**永不填假数字**。
- 口径写在脚注里、不藏着：`did` 随机匿名，卸载重装会变 → **活跃数偏高**；只统计**能连通 Gitee** 的设备 → 偏低；按**设备本地时区**计日、每小时刷新**非实时**。
- 「每设备每天一个文件」= 目录文件数即当天 DAU，天然去重，不需要服务端逻辑，也没有可以做手脚的中间计数器。

### 隐私红线（不可违反）

- 摄像头画面**永远只在本机处理**，埋点**绝不上报任何图像 / 人脸 / 个人身份 / IP**。
- 只上报四个匿名字段：随机安装标识 `did`（与真实身份无关）、App 版本 `ver`、机型 `model`、时间戳 `ts`。
- **同意门**：未同意 = 零采集（连 `did` 都不生成、不上报）。同意后每台设备每天最多上报一次。见 `ConsentDialog.kt` / `Prefs.consent`。
- 埋点是**旁路**：daemon 线程 fire-and-forget，全程 `try/catch` 静默吞，失败即丢，**绝不阻塞 / 拖累眨眼翻页主功能**。

### 安全红线（令牌纪律）

- **令牌绝不进源码 / git**：编译期从 `local.properties` 的 `analyticsToken` 注入 `BuildConfig.ANALYTICS_TOKEN`；`local.properties` 已 `.gitignore`。空令牌 = **灰度模式**（只写 logcat 不上报），填入即接通。
- **小号隔离**：上报令牌属于**专用采集小号** `jaxinleon_1`，该小号下只有这一个统计私库。令牌一旦泄露，爆炸半径隔离在小号——**碰不到主账号** `jaxinleon` **的代码库，更碰不到签名 keystore**，且可随时作废重发。APK 内含令牌是已知取舍（只污染小号统计数据）。
- CI 侧令牌（`GITEE_TOKEN` 只读采集小号、`GITEE_PUSH_TOKEN` 主账号回传 stats.json）只存 GitHub 仓库 **Secrets**，绝不写进代码 / 不落盘 / 不回显。

### 数据链路（一图）

```
App（同意门通过）
  └─ 每设备每天首次打开 → Gitee Contents API 创建文件
       私库 jaxinleon_1/eyecontrol-analytics : data/YYYY-MM-DD/<did>.ndjson
  ↓
GitHub Actions（每小时 cron）
  └─ aggregate.mjs 读 Gitee 私库（Contents API base64，私库 download_url 会 403）
       → 聚合 DAU / WAU / 版本 / 机型 → docs/dashboard/stats.json
       → 提交回 GitHub + 同步回 Gitee（备份）
  ↓
GitHub Pages 看板（docs/dashboard/index.html 读 stats.json）
```

> Gitee Pages 已对新站点停用（`has_page:false`、菜单入口消失、站点 404），故看板**统一在 GitHub Pages 展示**；Gitee 侧仅作数据源 + stats.json 备份。

### 相关文件

- `app/.../Analytics.kt` — 埋点上报（同意门 + 每日节流 + fire-and-forget）。
- `app/.../ConsentDialog.kt` / `Prefs.kt` — 同意门 UI + did 生成 / 同意态 / 节流键。
- `app/build.gradle.kts` — 令牌从 `local.properties` 注入 `BuildConfig`（灰度开关）。
- `docs/dashboard/aggregate.mjs` — GitHub Actions 聚合脚本（读 Gitee 私库）。
- `docs/dashboard/index.html` — 看板页（读 `stats.json`，加载失败显式提示不造假）。
- `.github/workflows/aggregate-analytics.yml` — 每小时聚合 + 提交 + 同步 Gitee。

---

## 文件导览
- `BlinkDetector.kt` — 眨眼算法（EAR + 自适应基线 + AGC + 峰谷检测 + 连眨补偿），从 Web 移植。
- `FaceAnalyzer.kt` — CameraX 帧 → MediaPipe FaceLandmarker → 关键点。
- `CameraService.kt` — 前台服务：常开摄像头 + 悬浮窗 + 把命中转给无障碍。
- `SwipeAccessibilityService.kt` — 无障碍 `dispatchGesture` 竖直滑动。
- `OverlayView.kt` — 悬浮窗 UI。
- `MainActivity.kt` — 三项权限引导 + 设置 + 开始/停止 + 检查更新。
- `ModelProvider.kt` — 模型文件首次下载/缓存。
- `UpdateChecker.kt` — 应用内检查更新:并发请求 GitHub + Gitee 的 releases/latest,取先返回的成功结果;逐段数值比较版本。
- `UpdateDownloader.kt` — 下载新版 APK 到 externalCacheDir + FileProvider 拉起系统安装。

---

## 发版约定（⚠️ 每次发新版必须遵守，否则应用内检查更新会失效）

应用内「检查更新」靠比对 GitHub / Gitee 上 **latest release 的 tag** 与本地 `versionName`。发版时务必:

1. **递增版本号** —— 改 `app/build.gradle.kts`:`versionName`(如 `1.0`→`1.1`)**和** `versionCode`(如 `1`→`2`),两者一起加。
2. **用同一个 keystore 签名** —— 升级包必须与线上包同源签名，否则用户无法覆盖安装(签名信息见私有备份仓库 `EyeControl-keys`)。
3. **两平台都发 Release**，且遵守命名:
   - **tag = `v` + versionName**，如 `v1.1`、`v2.0`。逐次递增(检查更新按此解析版本)。
   - **APK 附件名以 `.apk` 结尾**，建议 `EyeControl-v<版本>.apk`。App 只认 `.apk` 结尾的附件(Gitee 的 Release 会自动附带源码 zip，会被自动跳过)。
4. 两平台内容保持一致(同一个 APK 传两边)。国内用户走 Gitee、海外走 GitHub，App 会并发取先返回的。

只要 tag 规范、versionCode 递增、签名同源，老用户打开 App(或点「检查更新」)就能收到新版并一键更新。
