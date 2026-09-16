# implement.md — 执行计划

约束：本地**不编译**（无 Android SDK），CI 是唯一编译验证途径。因此每个阶段都要用「不需要编译器的静态自检」兜底，功能全部写完、静态自检全过之后再 push 触发 CI。

## 验证手段（不依赖本机 SDK）

| 检查 | 命令（在仓库根执行） |
|---|---|
| XML 良构（manifest + 全部 res） | `python -c "import glob,xml.dom.minidom as m;[m.parse(f) for f in glob.glob('app/src/main/**/*.xml',recursive=True)]"` |
| JNI 符号配对（Java `native` ↔ C++ `Java_*`） | 见阶段 1 的自检脚本 |
| 资源引用完整性（`R.id/R.layout/R.drawable/@string` 是否有定义） | 见阶段 3 的自检脚本 |
| R.java 不会引用已删资源 | 删除 `ModeSelToggle` 后全局 grep `mode_switch\|modesel\|state_mode\|ModeSelToggle` |

## 阶段 1：JNI 契约 + 生命周期（bug 1/2 的根因）

- [ ] `jni.cpp`：`processImageJNI` 返回 `jobjectArray`（该帧全部新完成文件，`_completed` 去重）。
- [ ] `jni.cpp`：新增 `Java_..._getStatusJNI`（`double[]` = 进度 / 状态 / 在传文件数 / 已完成数）与 `Java_..._detectedModeJNI`（int）。
- [ ] `MainActivity`：`native` 声明同步改为 `String[] processImageJNI(...)`、`double[] getStatusJNI()`、`int detectedModeJNI()`。
- [ ] `MainActivity.onPause()` 不再 `shutdownJNI()`；`onDestroy()` 保留。
- [ ] 自检：Java 里每个 `native` 方法名 → C++ 里必须有同名 `Java_org_cimbar_camerafilecopy_MainActivity_<name>`；类型签名（数组维度、参数）逐条比对。
- [ ] 提交点 ①：JNI 契约 + 生命周期（可独立回滚）。

## 阶段 2：发布与收件箱（bug 3 + R1/R4/R8）

- [ ] 新增 `FilePublisher.java`：MediaStore.Downloads（API≥29，两段式 + `IS_PENDING`）/ 旧公共下载目录（API<29）；重名 `name (2).ext`；成功删源文件。
- [ ] 新增 `ReceivedFile.java`：条目模型（名字 / 大小 / 时间 / 状态：待发布·已保存·未保存 / uri / 本地路径）+ `ArrayAdapter` 子类。
- [ ] `MainActivity`：`HandlerThread` 发布线程 + `ConcurrentLinkedQueue`；收件箱 `AlertDialog` + `ListView`；点击打开、长按分享、失败条目可重试。
- [ ] 启动清扫：`filesDir` 里遗留的成品文件进收件箱标「未保存」，不直接删除。
- [ ] `AndroidManifest`：`FileProvider` + `WRITE_EXTERNAL_STORAGE(maxSdkVersion=28)`；新增 `res/xml/file_paths.xml`。
- [ ] 自检：`FileProvider` authority 与 `applicationId` 一致；`file_paths.xml` 覆盖 `filesDir`（收件箱打开未保存文件要用）。
- [ ] 提交点 ②：发布与收件箱。

## 阶段 3：交互（R5/R6/R7）

- [ ] `activity_main.xml`：顶部状态条（模式 / 状态 / 百分比 / 已收 N）+ 底部三个按钮（模式、发送端、收件箱）。
- [ ] `MainActivity`：模式对话框（`AlertDialog` + `setSingleChoiceItems`，文案带适用场景）；模式写入 `SharedPreferences`，`onStart` 不再重置；自动识别只更新提示文案。
- [ ] 完成反馈：`Vibrator`/`VibratorManager` + `ToneGenerator`，全部 `try/catch`。
- [ ] `WebViewActivity`：可见返回按钮。
- [ ] 删除 `ModeSelToggle.java`、`res/values/attrs.xml`、`res/drawable/modesel.xml`，清理 `strings.xml` 中 `mode_*` 旧文案（新文案走 `mode_label_*` / `mode_desc_*`）。
- [ ] `app/build.gradle`：`versionCode 28` / `versionName 0.6.9`（CI 产物可与线上区分）。
- [ ] 自检：`R.id`/`@drawable`/`@string` 引用全部有定义；无残留 `ModeSelToggle` 引用。
- [ ] 提交点 ③：交互。

## 阶段 4：CI（R9）

- [ ] `.github/workflows/build.yml`：checkout(submodules) → JDK 17 → setup-android + NDK/build-tools/platform/cmake → OpenCV SDK 缓存与解压 → `./gradlew assembleDebug -Popencvsdk=… -Dorg.gradle.java.home=…` → 上传 APK artifact。
- [ ] 自检：YAML 可解析；`on:` 触发分支与实际分支名（`master`）一致。
- [ ] 提交点 ④：CI。

## 提交与 push

- [ ] 一个 Trellis 任务的 4 个提交点各自独立 commit（便于回滚）。
- [ ] Trellis 脚手架（`.trellis/`、`.pi/`、`.agents/`、`AGENTS.md`、`.gitattributes`）单独一个 commit，与功能改动分开。
- [ ] push 到 `origin`（`github.com/issakk/cfc`），触发 CI；把构建结果（成功/失败日志）回填到本任务。

## Review gates

- G1（阶段 1 后）：JNI 符号配对自检通过 + 生命周期改动只影响 `onPause`。
- G2（阶段 2 后）：无 SAF 弹窗残留（`ACTION_CREATE_DOCUMENT`、`onActivityResult` 不再用于接收路径）。
- G3（阶段 3 后）：资源引用自检通过，冷启动可见入口。
- G4（push 前）：四个提交点齐全、静态自检全绿、CI workflow 语法正确。

## 回滚点

- 任一阶段失败 → `git revert <commit>` 或 `git checkout HEAD~1 -- <path>`。
- CI 首轮失败且短期内修不动 → 只回滚 `app/build.gradle` 版本号与 workflow，接收可靠性改动可保留。

## 已知风险

1. **无法本地编译** → JNI 签名、资源引用、AGP/API 用法错误只能在 CI 暴露；因此自检脚本要覆盖最高风险面（JNI 符号、资源引用）。
2. **CI 首次运行时间**：NDK + OpenCV 下载 + libcimbar 全量 C++ 编译，预计 20–40 分钟；用缓存降低后续耗时。
3. **目标 SDK 36 / 编译 SDK 34** 的组合是上游现状，不动。
