# r675: listening statistics must reach disk before success

The inherited implementation could report success after both synchronous disk commits failed: SharedPreferences had already updated its in-memory map, and matching that map was treated as confirmation. Reopening the Android framework preferences from their XML file reproduced the loss: memory held 17 seconds while the disk still contained the earlier acknowledged 5 seconds.

## What changed

Only `ListenStats.kt` changes production behavior. Statistics persistence now requires `commit() == true` plus readback agreement. It no longer returns success merely because memory already matches. The existing bounded retry writes the same absolute snapshot, not the listening delta, so it cannot count the duration or play twice. Android's synchronous writer retries a pending disk generation even when the editor values are unchanged; already-confirmed generations avoid a physical rewrite. No journal, schema key, migration, or additional dependency was introduced.

The three daily-statistics snapshot exports re-confirm persistence under the existing statistics lock, even when initialization is cached or the original queue failure latch is no longer available. Confirmation uses the current storage date, independently of an export's requested projection date. No-change history backfill also confirms persistence rather than acknowledging memory alone. A newly generated per-device identity is accepted only after a successful commit.

Account attribution is still captured at queue submission. Local totals, summaries, and the account-owned contribution remain in the same preferences snapshot. The existing FIFO queue, immutable sync snapshot boundaries, sticky failure diagnostics, retention/merge rules, and automatic-sync settings are unchanged. The existing queue's failure latch deliberately remains fail-closed even if a later operation manages to persist the in-memory snapshot.

## Evidence

`ListenStatsDurabilityTest` adds nine deterministic tests. They cover acknowledged restart reload with local and account-owned facts, repeated disk failure, identical-snapshot retry without double counting, simulated death between memory application and disk write, device-ID failure, real framework XML reload, real framework filesystem failure, interrupted XML replacement recovery from Android's `.bak`, and snapshot revalidation without the old queue latch. Reconstructing preferences or throwing a simulated process-death error is a simulation, not an Android app force-stop.

The older failed-commit fixture also needed correction: its delegated editor returned the underlying editor from fluent setters, bypassing the overridden `commit()`. It now actually intercepts the write and expects an unconfirmed result rather than success.

| Run | Result |
| --- | --- |
| Final regressions against original production code | 10 tests; 5 failures, 0 errors, 0 skipped |
| Expanded regressions against the fix | **262 tests across 37 classes; 0 failures, 0 errors, 0 skipped** |
| Separate Linux JVM SIGKILL before next update | Fresh JVM recovered the acknowledged 5-second snapshot and complete ownership data |
| Separate Linux JVM SIGKILL after acknowledged 5+12-second updates | Fresh JVM recovered all 17 seconds and the exact complete preference map, including account/device data |

The SIGKILL probes execute the compiled production update against Android SDK 34 `SharedPreferencesImpl` hosted by Robolectric. The writer waits after the selected acknowledged update, is killed with SIGKILL, and a separate verifier JVM reopens the real temporary XML. No service destruction or shutdown flush runs. These are actual **Linux JVM process kills**, not Android OS force-stop, emulator app lifecycle, hardware filesystem/power-loss, or real-device validation.

The first focused post-fix run had two fixture failures: the framework filesystem failure was injected before initialization rather than at the delta commit, and the older fluent-editor fixture bypassed its commit override. Both were corrected before the final original-code reproduction and green expanded run. The first standalone probe launch used the repository root instead of the Android module's resource root and failed before any write; final launches from `app/` passed. These setup failures are retained, not counted as successful validation.

Expanded regression command (from the repository root):

```sh
ANDROID_HOME=/home/ubuntu/Android/Sdk bash gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' testDebugUnitTest \
  --tests 'xyz.cncnlp.soundwave.*ListenStats*' \
  --tests 'xyz.cncnlp.soundwave.*CloudSync*' \
  --tests 'xyz.cncnlp.soundwave.*WebDav*' \
  --tests xyz.cncnlp.soundwave.ComprehensiveSyncRegressionTest \
  --tests xyz.cncnlp.soundwave.Round3SyncFieldCoverageTest \
  --tests xyz.cncnlp.soundwave.PlaylistOrderSyncTest \
  --tests xyz.cncnlp.soundwave.R658cDataLaneTest \
  --tests xyz.cncnlp.soundwave.UserDataSaveClientTest \
  --tests xyz.cncnlp.soundwave.ListeningCalendarTest \
  --tests 'xyz.cncnlp.soundwave.*ProfileSessionCoordinatorTest'
```

Raw evidence: `/workspace/soundwave-stats-durability-20261023/`. `final-red-results/`, `expanded-results/`, and `test-summary.json` preserve the final red/green counts. The SIGKILL harness is under `process-probe/`; its final passing runs are `runs/20261023-070024-1253934-write/` and `runs/20261023-070025-1253975-before-update/`. Run its `run_probe.py` from `/workspace/soundwave-675-improve/app` with the exported `test-classpath.txt`, using `--mode write` or `--mode before-update`.

## What is still not guaranteed

“Success” here means a successful storage acknowledgement or a confirmed sync snapshot, not merely admission to the asynchronous writer queue or a value appearing on screen. Accumulator time below the normal flush threshold, accepted-but-not-executed queued work, and an update interrupted before a successful disk commit can still be lost on process death. There is no durable outbox in this slice. Failed writes may remain visible in memory and can be durably recovered by a later successful absolute-snapshot commit, but a kill before that recovery can discard them.

Android's existing XML/backup recovery preserves the last confirmed snapshot; it cannot reconstruct history already absent from both disk and a valid backup. This work does not claim recovery of previously lost history, full SW-02-10/SW-02-11 completion, real-device force-stop validation, cross-device conflict acceptance, or full-suite/release validation.

No APK/AAB was assembled, installed, or pushed. No build machines were inspected or operated. No detail/player spacing, unrelated sync coverage, automatic sync, version, or signing configuration was changed.
