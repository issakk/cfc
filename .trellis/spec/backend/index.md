# Backend Development Guidelines

> Conventions for the app layer of CameraFileCopy (Android, Java + JNI + OpenCV).

---

## Overview

This is not a service: the "backend" here is the Android app module (`app/`) plus the thin
JNI bridge into libcimbar. Everything below is what this repository actually does, including
the parts enforced only by CI.

**The single most important rule: this project is never built locally.** There is no Android
SDK on the development machine and no matching JDK, so `./gradlew` is not run by hand.
`.github/workflows/build.yml` is the compiler. Write code that compiles on the first push,
and lean on the static checks in `trellis-check` / the task's `implement.md` instead of a
local build.

---

## Guidelines Index

| Guide | Description | Status |
|-------|-------------|--------|
| [Directory Structure](./directory-structure.md) | Module organization and file layout | Filled |
| [Database Guidelines](./database-guidelines.md) | (no database in this project) | N/A |
| [Error Handling](./error-handling.md) | Failure paths: JNI, publishing, permissions | Filled |
| [Quality Guidelines](./quality-guidelines.md) | Verification gates, forbidden patterns | Filled |
| [Logging Guidelines](./logging-guidelines.md) | TAGs, log levels, what to log | Filled |

---

## How to Fill These Guidelines

When you learn something the hard way (a version pin, a platform quirk, a failure mode that
only shows up on a device), write it into the relevant file. Prefer the actual command or the
actual error text over prose.
