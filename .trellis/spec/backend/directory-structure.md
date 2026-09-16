# Directory Structure

> How the code is organized in this project.

---

## Overview

One Android module (`app`) plus a git subtree of libcimbar. The Java side is deliberately tiny:
everything that actually decodes lives in C++ behind four JNI functions.

---

## Directory Layout

```
app/
├── build.gradle                     # namespace/applicationId, NDK + OpenCV wiring, cimbar-js SHA check
└── src/
    ├── main/
    │   ├── AndroidManifest.xml      # activities, FileProvider, VIBRATE, legacy storage perm
    │   ├── assets/                  # git submodule: cimbar-js-bits (sender web page + wasm)
    │   ├── java/com/github/issakk/cfc/
    │   │   ├── MainActivity.java        # camera preview, decode loop, inbox, mode, publish queue
    │   │   ├── FilePublisher.java       # move a received file into public Downloads
    │   │   ├── ReceivedFile.java        # inbox item model + ArrayAdapter
    │   │   ├── WebViewActivity.java     # bundled sender page (encode from this device)
    │   │   └── OpencvCameraView.java    # fork of OpenCV's JavaCameraView (frame-size pick)
    │   ├── cpp/
    │   │   ├── CMakeLists.txt
    │   │   ├── cfc-cpp/jni.cpp          # the JNI bridge: 4 exported symbols
    │   │   ├── cfc-cpp/MultiThreadedDecoder.h
    │   │   ├── concurrent/              # thread pool + monitor
    │   │   └── libcimbar/               # git subtree, MPL-2.0 — DO NOT EDIT
    │   └── res/                     # layouts, strings, xml/file_paths.xml
    └── ...
.github/workflows/build.yml        # the only place this project is compiled
fastlane/metadata/android/         # store listing (upstream's)
```

---

## Module Organization

- **Java stays thin.** Anything that knows about fountain codes, deskewing or cell geometry
  belongs in C++/libcimbar. Java does camera plumbing, file handling and UI.
- **One concern per file.** `FilePublisher` (storage), `ReceivedFile` (inbox model), 
  `MainActivity` (glue + UI). Adding a fourth responsibility to MainActivity is a smell.
- **`app/src/cpp/libcimbar/` is a subtree.** Never edit it in place: changes belong upstream
  and come back through `git subtree pull`. `DETAILS.md` there documents its own layout.
- **Assets are a submodule.** `app/src/main/assets` is `cimbar-js-bits`; `preBuild` verifies the
  wasm SHA-256, so a missing submodule fails the build with
  `Missing cimbar-js-bits submodule? (index.html)`.

---

## Naming Conventions

- Java package is `com.github.issakk.cfc` and always matches `namespace` / `applicationId`
  (`trellis-check` asserts this). Renaming the package means also renaming the JNI symbols.
- Resource ids: `btn_*` (buttons), `status_text`, `main_surface`; string keys are `snake_case`
  and grouped by prefix (`mode_*`, `inbox_*`, `toast_*`, `action_*`).
- Log tags: `cfc::<ClassName>` (see logging-guidelines).
- CI env constants live at the top of `.github/workflows/build.yml` (`OPENCV_VERSION`,
  `NDK_VERSION`, `LEGACY_NDK_VERSION`, `BUILD_TOOLS_VERSION`, `COMPILE_SDK`).

---

## Examples

- `FilePublisher.java` — the reference for a file-shaped concern: one public entry point, a
  small `Result` type, all platform branching (API 29+ vs legacy) inside.
- `MainActivity.publishLater()` / `applyPublishResult()` — the reference for "do IO off the main
  thread, apply the outcome on it".
