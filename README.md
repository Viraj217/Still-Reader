# Still

Native Android 9+ EPUB/PDF reader. Kotlin, Android Views, Readium 3.0.3, platform PdfRenderer and PDFBox Android. No account or server required.

This is an implemented first version, not a claim that every requirement of the original specification is complete. See `markdown/IMPLEMENTATION.md` for important limits and physical-device checks.

## Build and verify

JDK 17, Android SDK platform 35/build tools 35, Gradle 8.10.2. Toolchain installed locally in `.tools` in this workspace (excluded from source).

```sh
source scripts/env.sh                    # this workspace's local toolchain
./gradlew :core:test                    # deterministic JVM tests; live suites opt in
STILL_LIVE=1 ./gradlew :core:test --tests still.core.LiveTest --rerun-tasks
STILL_LIVE=1 ./gradlew :core:test --tests still.core.QuickLiveTest --rerun-tasks
./gradlew :app:assembleDebug :app:lintDebug
```

On another machine use an installed JDK/SDK and set `ANDROID_HOME`; do not source the local environment script. The wrapper downloads Gradle. Live checks require public internet; results are written to `docs/live-results.txt` and `docs/live-extended.txt`. LiveTest records individual provider failures without failing the harness; QuickLiveTest asserts an actual verified search result and sample integrity. The deterministic test `benchmark` logs deduplication timing. No emulator or UI test is needed.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Search a title/author, or paste a direct public HTTPS PDF/EPUB or public download page. Configure optional Brave credentials or OPDS in Settings. A failed catalog does not suppress other results. All displayed editions passed a public HTTP GET and bounded format signature check; full validation occurs when downloaded.

Books and notes are private app data. Uninstalling deletes them. Export annotations from each reader. Optional folder selection mirrors new downloads to a Storage Access Framework folder (including an SD card); the active library remains internal. Long-press a library card to delete it and its annotations.

Additional database check (desktop SQLite, no Android UI):

```sh
python3 scripts/check_database.py
```

Source research, live endpoint evidence, build/test logs and device checklist are in `docs/`. The bundled dependencies retain their upstream licenses; this project does not include private credentials.
