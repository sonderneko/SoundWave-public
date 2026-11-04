# Focused privacy/input consumer regressions (no Android SDK)

Run `python3 scripts/test_privacy_input_closure_jvm.py` from this checkout.
The runner uses existing temporary compiler/JUnit/JSON/Android signature jars in
`/tmp/r657-security-jvm` and coroutine jar `/tmp/r657-migu-jvm/coroutines.jar`.
It does not install dependencies or run Gradle; missing jars are a setup blocker.

`CrashGuard` before `SoundWaveApplication` and the exact `MainActivity` lifecycle
prefix through `onNewIntent` are copied verbatim into temporary compilation units
(only unavailable unused application imports are removed). Actual
`BackupStore`, `BackupPreferenceProjection`, and
`SchemeActionHolder` are compiled directly from production files.

Facades in this directory substitute Android context/preferences/intent/URI,
package metadata, Compose setup, LocalStore/Song/download/history/stat/avatar
storage, unrelated sharing/import parsing and ApiClient manual transport. They
are deliberately outside app/src/test to avoid colliding with real Android types.
The crash auto-upload consumer (automatic HTTP transport, consent toggle and
manual send) was deleted from production on 2026-11-04 by user ruling — crash
reports are on-device only — and its harness tests (PrivacyClosureTest,
AutoUploadConsumerTest) were retired with it. These tests do NOT establish Android builds,
real TLS/network cancellation latency, or physical-device lifecycle behavior.

The independent pure policy JUnit suite is in
`app/src/test/java/xyz/cncnlp/soundwave/BackupPreferenceProjectionTest.kt`.
