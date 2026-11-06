# Still: instructions for coding agents

Read this file, `README.md`, and `markdown/IMPLEMENTATION.md` before editing. Inspect the relevant code and current Git diff. Treat documentation as context, then verify claims against code and tests. The user is testing on a Lenovo Tab M10 running Android 9 (API 28); a successful build on a newer SDK does not prove runtime compatibility.

All Markdown files other than `README.md` and `AGENTS.md` belong in `markdown/`. Keep new Markdown notes and reports there.

## Product contract

Still is an offline-first, no-login EPUB/PDF discovery and reading app. Preserve downloaded books, progress, notes, highlights, bookmarks, and user settings across updates. Never delete or reset app data to fix a bug. Never fabricate search results, download availability, page counts, file sizes, test results, or tablet performance. Clearly distinguish catalog metadata, a checked file header, a fully validated download, and a book actually opened on the device.

The latest APK is `dist/Still-debug.apk`; the source is in `app/` and `core/`. `core` contains platform-independent discovery and download logic. `app` contains Android UI, WorkManager, SQLite, and readers. See `markdown/API-RESEARCH.md`, `markdown/VERIFICATION.md`, and the version-specific fix notes under `markdown/` for evidence and known gaps. Do not infer that an item in older documentation has since been fixed.

## Before changing code

1. Reproduce or trace the reported failure and identify the exact code path. Check current version and Git status. Preserve unrelated edits.
2. Check API 28 compatibility for Android platform calls and interfaces. This project already had a runtime failure because `SQLiteOpenHelper` implemented `AutoCloseable` in the compile SDK but not on Android 9. Avoid assuming newer API declarations exist on the target device.
3. For external providers and Readium APIs, check the pinned dependency or official documentation and make a real request when network behavior matters. Respect provider conditions. Do not embed API keys or treat previews/catalog records as free files.
4. Keep fixes focused. Do not replace the reader, database, storage model, source adapters, or UI architecture as a side effect of a small bug fix. If a migration is needed, implement and test it before changing the schema version; never use destructive migration.

## Invariants and risky paths

- Download data first goes to a temporary file. Validate integrity, then atomically finalize and register the book. Keep interrupted files resumable and do not discard valid completed files. `LibraryRecovery` can register validated orphan files after prior registration failures.
- The same content hash identifies one stored file. Preserve existing book IDs and annotation relationships. Never delete a book or annotation without an explicit user action.
- WorkManager jobs are persistent and unique by source URL. `ExistingWorkPolicy.KEEP` may ignore a newly created request; observe the accepted unique work, not a presumed new UUID. Display current state and failure reason. Refresh the library on completion and return from a reader.
- Source responses and downloads are untrusted. Keep redirect, private-address, response-size, ZIP traversal/bomb, signature, and full-file checks. Do not bypass paywalls, DRM, authentication, CAPTCHAs, or robots rules.
- Reading and annotations must work offline. EPUB marks use Readium locators. PDF ink coordinates are normalized to each page. Preserve these formats when modifying rendering, zoom, rotation, or exports.
- App-managed storage is primary. The SAF folder currently mirrors downloads; it does not contain the annotation database. Do not describe it as a relocated library.
- Gutendex can time out. Brave web search requires a user key. One provider failing must not fail the whole search. Never claim unlimited no-key web search.

## Verification and delivery

The original user prohibited emulators, UI automation, screenshots, browser frontend tests, and visual inspection. Honor that constraint. Run appropriate JVM tests, focused live API tests when relevant, `:app:assembleDebug`, and lint when Android code/resources change. Use `source scripts/env.sh` for this workspace's local JDK/SDK, then `./gradlew ...`. Live suites require `STILL_LIVE=1`; report failures honestly. `scripts/check_database.py` checks the production SQLite schema on desktop SQLite.

After an app fix, increment `versionCode` and `versionName`, rebuild, verify the APK signature, copy it to `dist/Still-debug.apk`, update `dist/Still-source.zip` and `dist/SHA256SUMS`, and record concise evidence in `docs/`. Install updates with `adb install -r` so the user's data stays intact. Never claim device behavior was verified unless it was tested on the physical tablet. Tell the user what changed, what passed, and what still needs tablet confirmation.
