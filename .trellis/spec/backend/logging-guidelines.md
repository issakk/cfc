# Logging Guidelines

> Log tags, levels, and what is worth printing.

---

## Overview

Plain `android.util.Log` on the Java side, `__android_log_print` on the C++ side. No logging
framework, no structured JSON. Logcat is read with `adb logcat -s cfc::MainActivity` etc., so
**the tag is the filter**: keep the `cfc::` prefix or the line becomes invisible in practice.

---

## Log Levels

| Level | Use for |
|---|---|
| `Log.d` | Camera plumbing chatter (OpenCV's own view logs `JavaCameraView`) |
| `Log.i` | Lifecycle milestones: `called onCreate`, `OpenCV loaded successfully`, `recovering <file> from a previous session`, `Shutdown cfc-cpp` |
| `Log.w` | Recoverable oddities the user may notice: `decoder reported X but it is not on disk`, `could not delete the temp copy`, `vibration failed` |
| `Log.e` | A user-visible operation failed: `MediaStore publish failed`, `publish threw for <name>`, `export failed`, `OpenCV initialization failed!` |

---

## Structured Logging

Not used; there is a single device and a human reading Logcat. Keep messages short and include
the variable that identifies the object (filename, path, exception).

```java
Log.e(TAG, "publish threw for " + item.name, e);   // pass the throwable, don't stringify it
Log.w(TAG, "could not clean up " + dest);
```

C++ side, same idea:

```c
__android_log_print(ANDROID_LOG_INFO, TAG, "processImage computation time = %f seconds\n", totalTime);
```

---

## What to Log

- Decoder lifecycle: creation, reuse, shutdown (the JNI side logs shutdown).
- Files: recovered leftovers, publish failures, export failures — always with the name.
- Anything a user will report as "it didn't work": the reason string that the toast showed.

---

## What NOT to Log

- Per-frame values at `Log.i` or above. `processImage` already logs a timing line every frame from
  C++; do not add more per-frame Java logging, it is measurable on the camera thread.
- Full file paths of the user's private storage at `Log.i` when a basename is enough.
- Contents of received files. The app handles arbitrary user files; never dump bytes or names in a
  loop.
- Anything from the debug drawing helpers unless the user asked: `drawDebugInfo()` in `jni.cpp` is
  deliberately left commented out because it burns CPU in the per-frame path.
