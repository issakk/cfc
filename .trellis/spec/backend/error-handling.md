# Error Handling

> How failures are handled in this app.

---

## Overview

Two rules drive everything here:

1. **Receiving must not stop because something else failed.** The camera keeps decoding while a
   file is being published, and a failed publish never takes the process down.
2. **Never destroy the only copy of user data while there is a safer ordering available.**
   Publishing is a move, so the source is deleted last — after the destination exists *and* is
   addressable.

---

## Error Types

There is no exception hierarchy. Failures cross the JNI boundary as data:

- `String[] processImageJNI(...)` — filenames only; an empty array means "nothing finished", and
  a `null` array means JNI could not build the result. Callers must treat `null` as empty and
  skip individual `null` slots.
- `FilePublisher.Result` — `{ok, uri, location, error}`. `error` is a short reason string that
  ends up in the inbox line and in a toast.

---

## Error Handling Patterns

- **Publishing** (`FilePublisher.publish`): every branch returns a `Result`. On API 29+ a failed
  insert/copy deletes the half-written MediaStore row; on the legacy path a failed copy deletes
  its own partial file. The source file is deleted only after the copy succeeded *and*
  `getUriForFile` produced a URI. A failure leaves the app's copy in `filesDir` for a retry.
- **Publish job** (`MainActivity.publishLater`): the whole body is wrapped in `try/catch`. An
  uncaught throw on a `HandlerThread` terminates the process, so anything unexpected is converted
  into `Result.failed(...)` and shown as an unsaved inbox item.
- **Inbox state**: `PENDING → SAVED | FAILED`. Only `FAILED` may be retried; retrying a `PENDING`
  item races the queued job (the first job deletes the source, the second reports
  `source file is gone`, and the item flips to failed even though the file is safely in Downloads).
- **Leftovers**: files left in `filesDir` by a killed session are re-published on startup instead
  of being deleted or ignored.
- **Permissions**: `WRITE_EXTERNAL_STORAGE` is requested only on API < 29 and only at startup. If
  it is denied, publishing returns `storage permission not granted`, the item stays unsaved, and
  the user can retry after granting.
- **Feedback helpers** (`Vibrator`, `ToneGenerator`): wrapped in `try/catch` and allowed to do
  nothing — silence/do-not-disturb must not affect receiving.
- **JNI availability**: `mNativeReady` guards `shutdownJNI()` so a failed `OpenCVLoader.initLocal()`
  cannot turn `onDestroy` into `UnsatisfiedLinkError`.
- **Open/copy of a received file**: `ActivityNotFoundException` (nothing can open the MIME type,
  no file manager for SAF) is caught and reported as a toast, never a crash.

---

## Common Mistakes

- Deleting a temp file in a `finally` on the *receiving* path (the old code did): cancelling the
  save dialog dropped the file for good.
- Assuming a `String[]` from JNI is non-null and fully populated.
- Doing IO on the camera thread and calling `startActivity*` from it.
- Treating `!isSaved()` as "failed" — `PENDING` is not `FAILED`.
- Catching and swallowing an exception without leaving the user a way to recover (the item must
  stay visible and retryable).
