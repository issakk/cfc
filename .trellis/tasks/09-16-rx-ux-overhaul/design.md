# design.md — 接收可靠性与操作体验重构

## 1. 边界

改动范围仅限 app 层，`app/src/cpp/libcimbar/`（subtree，MPL-2.0）不动。

| 文件 | 动作 |
|---|---|
| `app/src/main/java/.../MainActivity.java` | 重构：收件队列、后台发布、状态文案、模式选择、可见入口 |
| `app/src/main/java/.../ReceivedFile.java` | 新增：收件箱条目模型 + 列表适配器（含发布状态） |
| `app/src/main/java/.../FilePublisher.java` | 新增：发布到公共存储（MediaStore / 旧路径） |
| `app/src/main/java/.../ModeSelToggle.java` | 删除（自绘状态机开关，被对话框选择器取代） |
| `app/src/main/java/.../WebViewActivity.java` | 加可见返回按钮，保留滑动手势 |
| `app/src/cpp/cfc-cpp/jni.cpp` | 回传契约改为数组 + 状态查询；`shutdownJNI` 只由 `onDestroy` 调用 |
| `app/src/main/res/layout/activity_main.xml` | 顶部状态条 + 底部按钮条 |
| `app/src/main/res/layout/webview.xml` | 叠加返回按钮 |
| `app/src/main/res/values/strings.xml` | 新文案；移除模式开关相关 |
| `app/src/main/res/values/attrs.xml`、`drawable/modesel.xml`、`drawable-*/ic_mode*.png` | 删除（随 ModeSelToggle 一起死掉） |
| `app/src/main/AndroidManifest.xml` | FileProvider + `VIBRATE` +（API<29）旧存储权限声明 |
| `app/src/main/res/xml/file_paths.xml` | 新增：FileProvider 路径 |
| `app/build.gradle` | 版本号递增（CI 产物与线上区分） |
| `.github/workflows/build.yml` | 新增：CI 构建 |

## 2. 契约

### 2.1 JNI（`jni.cpp` ↔ `MainActivity`）

旧契约：`processImageJNI(long mat, String path, int mode) -> String`，用 `/4`、`/66`… 前缀夹带模式，单值只能报一个文件。

新契约：

```java
// 每帧调用；返回该帧新完成的文件名（可能为空数组，从不为 null；个别槽位可能为 null）
private native String[] processImageJNI(long matAddr, String dataPath, int modeVal);
// {maxProgress(0..1), transferStatus(0=idle, 1=partial, 2=full), filesInFlight}
private native double[] getStatusJNI();
// 自动识别到的模式，0 表示尚未识别
private native int detectedModeJNI();
private native void shutdownJNI();
```

- 模式回传不再靠字符串前缀：自动识别结果由 `detectedModeJNI()` 提供，与文件名数组彻底解耦。
- `String[]` 由 `_completed` 集合去重后一次性返回，杜绝「同帧多文件只报一个」。
- `getStatusJNI()`/`detectedModeJNI()` 每帧调用，成本是读几个计数器；`_proc` 为 null 时返回 `{0,0}` 与 0。
- `_transferStatus` 的 32 帧采样逻辑保留在 C++（避免行为变化），只是改为对外暴露。
- 状态条（第三轮修订）：状态词不再由 `_transferStatus` 单独决定。它只反映「最近 32 帧有没有解出东西」，发送端一时不可读就会回落到 0，于是出现「文件已经 80% 但显示等待条码」。现在只要有流在传（`filesInFlight > 0`）或进度非零就算「接收中」，`_transferStatus` 只用来提前进入/保持「完成」。
- `shutdownJNI()`（只由 `onDestroy` 调用）清空 `_completed` 与计数器，因此同名文件在新会话里可以再次被上报。

### 2.2 公共存储发布（`FilePublisher`）

```
publish(Context, File src, String displayName) -> Result{ok, uri, location, error}
```

- API ≥ 29：`MediaStore.Downloads` + `IS_PENDING` 两段式写入（insert → 拷贝 → 置 0），`RELATIVE_PATH = Download/CameraFileCopy`。无需权限。
- API < 29：`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/CameraFileCopy/`，需要 `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28）。
- 重名：旧路径用 `name (2).ext` 递增；MediaStore 自行改名，发布后回查 `DISPLAY_NAME` 以显示真实落盘名。
- 失败即失败：**只有拷贝成功、且拿到了可分享的 content uri 之后**才删除源文件；任何一步失败都保留源文件，让收件箱能重试。

### 2.3 收件箱状态机

`PENDING`（已入队/发布中）→ `SAVED`（已在 Downloads，持有 uri）或 `FAILED`（源文件仍在，可重试）。

- 只有 `FAILED` 允许「重试保存」；`PENDING` 重试会与队列里的作业竞争（第一份已删源文件 → 第二份报 source file is gone → 把已保存误标为未保存）。
- 源文件删除只发生在 `SAVED`，因此 `FAILED`/`PENDING` 的条目永远有本地副本可读（打开/分享/另存为都走 `contentUri()`：优先用已保存的 uri，否则用 FileProvider 包 `filesDir` 里的临时文件）。

### 2.4 模式持久化

`SharedPreferences("cfc", MODE_PRIVATE)`，键 `mode`（int，0/4/66/67/68）。`onCreate` 读取并 `setMode`，用户改动即写回；`onStart` 不再重置。

## 3. 数据流与线程

```
相机线程 onCameraFrame
   └─ processImageJNI(...) → String[] newFiles
        ├─ 逐个 runOnUiThread → onFileReceived()（主线程建条目、入队）
        ├─ getStatusJNI()/detectedModeJNI() → runOnUiThread 更新状态条（≥200ms 节流）
        └─ 发布线程 (HandlerThread, 单线程串行)
              └─ FilePublisher.publish → runOnUiThread applyPublishResult → 刷新列表 + 震动/提示音
```

- 发布放在独立线程：媒体写入是 IO，绝不能占用相机线程（旧代码在相机线程里 `startActivityForResult`，既违规又会丢帧）。
- 单线程串行保证收件箱顺序 = 接收顺序，且不需要加锁；`mInbox` 与 adapter 只在主线程读写，发布结果通过 `runOnUiThread` 回主线程。
- 发布线程的作业体整段 `try/catch`：非主线程的未捕获异常会直接杀掉进程，捕获后退化成一条可重试的 `FAILED` 条目。
- 震动用 `Vibrator`/`VibratorManager`（API 31 分支）+ `VibrationEffect`（API 26 分支），提示音用长生命周期的 `ToneGenerator`（`onDestroy` 释放），两者都在 `try/catch` 内，静音/勿扰下失败不影响接收。

## 4. 临时文件生命周期

- 接收中的文件由 libcimbar 直接写在 `filesDir`（`decompress_on_store`，只在整份文件收全时才落盘）。
- 发布成功后立即删除；失败保留。
- 启动时清扫：`filesDir` 里的遗留文件全部登记进收件箱并立刻重新发布（上次被杀的会话也能把文件交到用户手里），而不是删除或忽略。

## 5. 生命周期修复（bug 1 的根因）

- `onPause()`：只 `mOpenCvCameraView.disableView()`，**不再** `shutdownJNI()`。相机视图停掉后帧不再进来，解码线程池自然空转，`_proc` 与 fountain 状态原样保留。
- `onDestroy()`：`shutdownJNI()`，释放线程池（并用 `mNativeReady` 守卫，OpenCV 初始化失败时不触发 `UnsatisfiedLinkError`）。
- 效果：弹窗、切后台、跳发送端页面都不再重置进度。

## 6. UI 结构

```
activity_main.xml (ConstraintLayout)
├── OpencvCameraView          全屏预览（沿用）
├── status_text (TextView)    顶部左侧，半透明：模式 + 状态 + 百分比
├── button_bar (LinearLayout) 右下角竖排三个按钮
│   ├── btn_send   进入发送端（替代纯手势入口）
│   ├── btn_mode   "Mode: X" → 模式说明对话框
│   └── btn_inbox  "Inbox (N)"
└──（垂直滑动仍进发送端，作为快捷方式保留）
```

模式对话框：`AlertDialog` + `setSingleChoiceItems`，条目文案自带适用场景（B=112×112 正方 / Bm=112×78 宽屏 / Bu=80×69 小码 / 4C=旧版四色），底部说明「要和发送端显示的模式一致」。自动模式下按钮显示用户选的 Auto，识别结果只出现在状态条（`Auto -> Bm`），不改变选项。

`WebViewActivity`：左下角「Back to camera」按钮（`finish()`），滑动手势保留。

## 7. CI（GitHub Actions）

`.github/workflows/build.yml`，触发 `push`(master/main) + `pull_request` + `workflow_dispatch`：

1. `actions/checkout` + `submodules: recursive`（取 `cimbar-js-bits`，否则 `preBuild` 的 SHA 校验会抛 `GradleException`）。
2. `actions/setup-java` temurin 17。
3. `android-actions/setup-android`，安装 `platform-tools`、`platforms;android-34`、`build-tools;35.0.1`、`ndk;29.0.14206865`、`ndk;25.1.8937393`、`cmake;3.22.1`，自动接受 license。
   - 两个 NDK 的原因：app 模块 pin 29.x，而 `:opencv` 模块（OpenCV 4.12 SDK 自带）没有 `ndkVersion`，AGP 8.2.2 会用它的默认值 25.1.8937393。显式安装可避免构建中途依赖 `ENABLE_SDK_DOWNLOAD` 再拉 ~600MB。
4. 缓存 `~/.gradle/caches`、`~/.gradle/wrapper`、OpenCV SDK 目录（key 带固定版本号）。
5. 下载并解压 `opencv-4.12.0-android-sdk.zip`（约 314MB，来自 opencv/opencv release）。
6. 构建：`./gradlew --no-daemon assembleDebug`，构建前用 `sed` 就地把 `gradle.properties` 里的 `opencvsdk` 与 `org.gradle.java.home`（上游作者的本机路径）改写成 runner 路径——不把只能在本机工作的路径提交进仓库，本地 Android Studio 用户不受影响。
7. 上传 `app/build/outputs/apk/debug/*.apk` 为 artifact（debug 签名，可直接安装）。

取舍：debug 构建而非 release —— release 开了 `minifyEnabled`/`shrinkResources` 且未签名，产物不可直接安装。

首次运行实测：**成功**，产出 `CameraFileCopy-debug-apk`（15.1 MB）。第二轮起复用 gradle 与 OpenCV 缓存。

## 8. 兼容性

- `minSdk 21`：`MediaStore.Downloads`、`VibrationEffect`、`VibratorManager` 分别用 `SDK_INT >= Q/O/S` 守卫；Java 源码保持 Java 8 语法（不用 lambda/var/多重 catch），不依赖 desugaring。
- `FileProvider` authority 用 `${applicationId}.fileprovider`，与 `FilePublisher.authority()` 一致，避免与 F-Droid/Play 构建冲突。
- 保留 `WebViewActivity` 现有 `WebViewAssetLoader` 行为与 `onShowFileChooser`。

## 9. 取舍与被否方案

- **否**：保留 SAF 弹窗但后台排队（用户选 A）。理由：弹窗仍会打断视线与手势，且每文件一次选择对「接收方」是纯负担。
- **否**：把收件箱做成 Room/SQLite。理由：一次会话几十个文件，内存列表 + 启动清扫足够；持久化需求出现再说。
- **否**：改 `processImageJNI` 为回调（C++ → Java 的 callback）。理由：相机线程同步返回数组更简单，且不引入 JNI 全局引用管理的坑。
- **否**：`_completed` 改成按 mtime 或 fountain id 去重。理由：sink 的 done 列表在整个会话内累积，逐帧 stat 或按 id 去重都会把已上报的文件重复上报，比「同名重发要等下次启动」更糟。该上限写在 `jni.cpp` 注释里。
- **保留**：C++ 侧继续把引导线/进度条画在预览 Mat 上（工作正常，改动无收益）。

## 10. 回滚（实施后修订）

原计划按 4 个关注点分别提交（① JNI ② 发布 ③ UI ④ CI）。实际实施时发现：`MainActivity` 的收件队列、发布、状态条、模式对话框是同一批编辑的产物，拆成 3 个中间提交既无法单独编译、也不具备真实的回滚价值。

因此改为 3 个自洽提交：

1. `chore: trellis`（脚手架，与功能无关，可整体丢弃）
2. `feat: 接收可靠性 + 交互重做`（`jni.cpp` + 4 个 Java 文件 + 资源 + manifest + 版本号）
3. `ci: GitHub Actions`（新增 workflow，可独立回滚而不影响功能）

回滚粒度：

- CI 出问题 → `git revert <ci commit>` 或直接改 workflow 文件。
- 功能出新问题 → `git checkout <feat commit>~1 -- <path>` 可只回滚单个文件（例如只回滚 `jni.cpp` + `MainActivity.java` 的 native 声明，同时保留 UI 改动）。
- 无数据迁移、无 schema 变更、无外部状态，因此不需要数据回滚脚本。

## 11. 复查后的修订（第二轮）

独立复查（`trellis-check`）报出 3 个问题，均已修：

1. **PENDING 条目可被当成失败重试**：`isSaved()` 对 PENDING/FAILED 都为 false，点一下「正在保存」的条目（启动补齐时最常见）会排第二份作业；第一份已删源文件 → 第二份报错 → `markFailed` 覆盖 `markSaved`，文件其实在 Downloads 里却显示未保存且永远重试失败。
   修法：新增 `isFailed()`，只有 `FAILED` 才提供/允许重试；`retryPublish` 自身再加一道守卫。
2. **发布线程未捕获异常会杀进程 + legacy 路径删源文件早于取 uri**：`FileProvider.getUriForFile` 在非标准存储路径上会抛 `IllegalArgumentException`，而旧代码在它之前就 `deleteSource`，源文件与 uri 双双失去。
   修法：作业体整段 `try/catch`，异常退化成 `FAILED`；legacy 分支改为「拷贝 → 取 uri → 删源文件 → 通知媒体库」。
3. **`:opencv` 模块需要 AGP 默认 NDK**：见 §7 第 3 条，显式安装 `ndk;25.1.8937393`。

顺带处理：`getStatusJNI` 只回传真正被使用的两个值；MediaStore 落盘名改为回查 `DISPLAY_NAME`（消除「显示名可能不等于真实文件名」）；PRD 的 AC4 措辞与 R4 对齐。

## 12. 第二轮：竖屏 + 包名

### 12.1 横屏改竖屏的连带影响

`OpencvCameraView.bestCameraFrameSize()` 原来是「短边落在 960–1080 且宽高都不超过 Surface 宽高」的筛选。相机上报的预览尺寸恒定是横向的（1920×1080），而竖屏下 Surface 是 1080 宽 2280 高，于是 1920 > 1080 被判不合格，整个循环筛不出候选，回退到基类 `calculateCameraFrameSize()`，在 1080 宽的面板下大概率落到 1024×768 —— 送给解码器的像素直接砍半。

改法：按 Surface 的**长边/短边**比较（`surfaceLongest/surfaceShortest`）。横屏时长边=宽、短边=高，行为完全不变；竖屏时长边=高，1920×1080 重新可选。这是纯筛选逻辑的修改，不动 OpenCV 的旋转/缩放代码。

其余部分天然适合竖屏：`CameraBridgeViewBase.getFrameRotation()` 读 display rotation 自己算旋转角，`mFrameWidth/mFrameHeight` 按 `frameRotation % 180` 交换，`RotatedCameraFrame` 负责预览旋转 —— 无需改动。

### 12.2 包名迁移清单

| 位置 | 改动 |
|---|---|
| `app/build.gradle` | `namespace` + `applicationId` → `com.github.issakk.cfc` |
| `AndroidManifest.xml` | 删掉 `package="..."`（AGP 8 已废弃，改用 `namespace`）；`.MainActivity` / `.WebViewActivity` 相对名不变 |
| Java 源码 | 5 个文件移到 `app/src/main/java/com/github/issakk/cfc/`，`package` 声明同步（用 `git mv` 保留历史） |
| `jni.cpp` | 4 个符号前缀 `Java_org_cimbar_camerafilecopy_MainActivity_` → `Java_com_github_issakk_cfc_MainActivity_`（包名点换下划线，本包无下划线所以不需要 `_1` 转义） |
| `activity_main.xml` | 自定义 View 全限定名 `<org.cimbar.camerafilecopy.OpencvCameraView>` → `<com.github.issakk.cfc.OpencvCameraView>` |
| FileProvider authority | 走 `${applicationId}.fileprovider` 与 `getPackageName()`，自动跟随，无需改 |

风险点：JNI 符号漏改 → 运行时 `UnsatisfiedLinkError`（编译器不会报）。因此静态自检里新增「符号前缀由 `MainActivity.java` 的 `package` 声明推导」这一步，而不是硬编码旧包名。
