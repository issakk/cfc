# Database Guidelines

> Not applicable — recorded here so nobody goes looking.

---

## Overview

This app has no database and no ORM. There is nothing to migrate and nothing to query.

State lives in three places:

| State | Where | Lifetime |
|---|---|---|
| In-progress fountain streams | RAM, inside `MultiThreadedDecoder` (libcimbar) | per decoder instance, dropped on `onDestroy` |
| Received files not yet published | `filesDir` (app-private), written by libcimbar's `decompress_on_store` | until the publish succeeds |
| Published files | `Downloads/CameraFileCopy/` via MediaStore (API 29+) or public storage | user-owned |
| User's decoder mode | `SharedPreferences("cfc")`, key `mode` | across launches |

The inbox (`ArrayList<ReceivedFile>` in `MainActivity`) is deliberately **in-memory only**: it
describes this session, and anything that outlives the process is rediscovered from `filesDir`
at startup.

---

## Common Mistakes

- Reaching for Room/SQLite to "remember received files". The startup sweep over `filesDir` already
  covers the case that matters (an interrupted session), and a database would need its own
  consistency story against the files on disk.
- Treating `filesDir` as scratch space to clear at will. It holds the only copy of a received file
  until publishing succeeds — delete it only after the destination is confirmed.
