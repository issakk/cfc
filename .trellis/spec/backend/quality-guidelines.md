# Quality Guidelines

> What "verified" means here, and which patterns are banned.

---

## Overview

Nobody builds this project locally. CI (`.github/workflows/build.yml`) is the compiler and the
signing step, so the bar for a push is: *the static checks pass and the code is written the way
a compiler would need it*. Anything CI cannot see — runtime behaviour — has to be argued from
the code, or tested on a device by hand.

---

## Verification Gates

Run these before every push (no SDK required):

| Check | Why |
|---|---|
| XML well-formed for `app/src/main/**/*.xml` + manifest | a stray tag is a build failure 30 minutes later |
| Every Java `native` declaration has `Java_<package>_MainActivity_<name>` in `jni.cpp` | mismatches are `UnsatisfiedLinkError` at runtime, never a compile error |
| Every `R.id / R.string / R.layout / @drawable / @string` reference resolves | renamed or deleted resources only fail at build time |
| Custom-view FQNs in layouts resolve to a file under `app/src/main/java/` | same class of failure |
| `namespace == applicationId == java package` | package renames are the easiest way to break JNI |
| `.github/workflows/build.yml` parses | a YAML typo means no build at all |
| Braces/parens balance in touched Java files | cheap sanity net for hand edits |
| Calls of our own methods on fields typed as a framework class are printed for review | the checker cannot resolve types; a base-typed field + a subclass-only method is `cannot find symbol` |

The `trellis-check` sub-agent runs this list plus a read-through; keep it in the loop for
anything touching JNI, threading or resources.

---

## Forbidden Patterns

- **Editing `app/src/cpp/libcimbar/`.** It is an upstream subtree (MPL-2.0). Fix upstream or work
  around it in `cfc-cpp/`.
- **Building locally / "it compiles on my machine".** There is no SDK here; do not add steps that
  assume one, and do not commit machine paths.
- **Hardcoding local paths in tracked files.** `gradle.properties` keeps the upstream author's
  `opencvsdk` / `org.gradle.java.home`; CI rewrites them in place with `sed` rather than
  committing runner paths.
- **Blocking the camera thread with IO.** `onCameraFrame` runs per frame: it may call JNI and
  post to the UI thread, nothing else. File copies and storage writes go to `HandlerThread`.
- **Calling `startActivity*` from the camera thread.** The original code did exactly that for the
  save dialog.
- **Touching the inbox list / adapter off the main thread.** Publish results come back through
  `runOnUiThread`.
- **Uncaught exceptions on non-main threads** (a `HandlerThread` throw kills the process).
  `publishLater`'s job body is wrapped for this reason.
- **Java APIs above the desugaring floor in app code:** no `java.util.stream`, `Optional`,
  `List.sort`, `Map.getOrDefault`, `Comparator.comparing*`, `String.join` — minSdk is 21 and
  core library desugaring is not enabled. Plain loops and `Collections.sort` instead.
- **Platform APIs without a version guard:** `MediaStore.Downloads`/`RELATIVE_PATH`/`IS_PENDING`
  (29), `VibrationEffect` (26), `VibratorManager` (31). Guard with `SDK_INT` and keep each
  guarded block in its own method where practical.
- **`ArrayAdapter` with a row layout whose root is not a `TextView`.** Without a
  `textViewResourceId` argument, `ArrayAdapter.getView()` casts the inflated root to `TextView`
  and throws `IllegalStateException: ArrayAdapter requires the resource ID to be a TextView`
  (`ClassCastException: TwoLineListItem -> TextView`). It fires while the `ListView` *measures*
  (i.e. the moment a dialog with a wrap_content list is shown), not when a row is tapped, which
  makes it easy to misdiagnose. Use a `BaseAdapter` with your own row layout instead.
- **Calling one of our methods through a field typed as the framework class.** `MainActivity`
  keeps the camera view in a field typed `OpencvCameraView` (our subclass), not
  `CameraBridgeViewBase`, because it calls `setPreferHighResolution()` on it; through the base
  type that is `cannot find symbol` and cost a red CI run. The local checker cannot resolve
  framework types, so it only *prints* such calls for review -- read that list.
- **Enabling `minifyEnabled` for a release that people will install from CI.** The CI artifact is
  a debug build on purpose: debug-signed and installable.

---

## Required Patterns

- `try/catch` around anything that can throw on a background thread, degrading to a user-visible
  state instead of a crash (see error-handling).
- Delete destructively only after the replacement is known-good: publishing deletes the app's
  copy *after* the file is in Downloads and its content URI exists.
- `Build.VERSION.SDK_INT` guards for every platform API above 21, with the guarded code in a
  small method.
- Keep `MainActivity`'s native declarations, `jni.cpp`'s symbols and the package declaration in
  lockstep — change all three in the same commit.

---

## Testing Requirements

- There is **no test suite** (and no local runner to start one). Justify behaviour changes
  against `prd.md`'s acceptance criteria instead, and say explicitly which criteria were verified
  by CI, which by static checks, and which still need a device.
- If you add pure logic worth a test (name de-duplication, state machine transitions), a plain
  `assert`-based check that runs in the JVM is welcome — but it must not require the Android SDK.

---

## Code Review Checklist

- Does `./gradlew assembleDebug` stand a chance on the first CI run (imports, API levels,
  resource names, JNI signatures)?
- Does any failure path lose data or kill the process?
- Is the main thread free of IO on the receive path?
- If the package or a resource was renamed, did every consumer follow (JNI symbols, layouts,
  `namespace`/`applicationId`)?
- Are acceptance criteria in `prd.md` still accurate after the change?
