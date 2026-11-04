# Local-first source continuation — phase evidence

Repository `/root/soundwave-provenance-4eb9ffa`, branch `feat/neumorphic-ui-refresh`, baseline `4eb9ffaa48cec1fac4b077abc69ff57b1fef1c04`. Work remains uncommitted. This document supersedes the earlier partial implementation status. **The complete requested plan is not yet finished.**

## Phase 1 — provenance gate and mutation boundary

- `RuntimeProvenanceInventory`: synchronous UI gate no longer performs full scan/commit; cached evidence cannot authorize absence. Explicit data-operation verification refuses main-thread scans.
- Split scan/mutation locks so UI mutations do not wait for filesystem/catalog commit under the scan lock. Epoch invalidation uses immediate in-process `apply` rather than main-thread fsync.
- Exact validated technical catalog/empty snapshot metadata is excluded; empty container roots use child enumeration. Actual unknown children, backup files, unreadable locations and malformed metadata remain conservative.
- `ContextPreferences` is integrated into AppRoot settings/scripts editors, stats, SmartSource, probe cache/scheduling, import checker, list scroll, lyric float, Service, Scheduler/Helper and dislikes. Editor methods return the guarded editor so fluent calls cannot bypass validation/epoch invalidation.
- Not all raw context-based preference/file mutations are migrated. A fresh IO scan remains mandatory; saved absence is not reused after restart.

## Phase 2–3 — explicit owner selectors

- `ProfileCatalog` checks registry/metadata, validated inactive payload and import quarantine. No automatic first-profile/login-account inference.
- `ContextData` production consumers now include AppRoot core/history/download selectors, foreground resolve history, event-captured history, statistics screens/home/search/recommendation and dislike UI/service filtering.
- AppRoot captures explicit immutable legacy runtime identity. Unknown recorded identity does not fall back to legacy.
- Runtime remains legacy-only: immutable process session leases are integrated through settings/scripts, sort/scroll, resolver/script-runtime/lyric caches, controller transactions and service/checkpoint/helper boundaries. Inactive recorded namespaces are not granted runtime write or remote capabilities; strict preview validation is preserved. No activation/switch entrance is added.

## Phase 4–5 — playback and persistent work

- Resolve transactions/tickets and snapshots carry owner context; resolver rejects foreign runtime owner. History writes use captured transaction/event owner.
- Service listener captures owner; natural `STATE_ENDED` checks owner but preserves the existing player-instance/generation exception needed for natural advance.
- Notification service intents carry owner/generation, malformed/foreign identity rejected. Snapshot checkpoint owner envelope preserved; profile overload owner now matches profile namespace.
- Download and script-probe requests serialize owner/generation; foreign owners rejected, missing owner maps only to old legacy tasks. Downloads retain clear-generation fence and original legacy job identity. New task identity uses SHA-256 instead of colliding Java hash, stored in input; old requests derive original old ID.
- **Limit:** recorded profile runtime jobs remain prohibited and activation remains closed. The process-scoped coordinator, owner-bound MediaSession/checkpoint/service recovery, worker/helper identities, resolver/lyric cache keys and controller commit fences are now integrated; device-global SAF/output/files remain intentionally global.

## Phase 6 — explicit inactive profile WebDAV

- Inactive preview exposes separate binding UI and local revoke, but opt-in/test/sync now fail closed at coordinator readiness **before credential namespace reads or requests**. Imported profile remains LOCAL_ONLY; readiness is deliberately not enabled.
- Binding holds independent random document ID, revision (ABA protection), encrypted credentials, scope `core-history`, conditional merge-once conflict policy. Namespace never falls back to legacy credentials.
- Bound sync includes only named-profile independent history, rechecks binding/provenance before PUT/retry/apply, records successful PUT ETag/readback, remerges current local core/history, and validates safe inactive-format projection before commit.
- Existing remote-file-without-ETag compatibility remains; no mandatory ETag change. No real WebDAV requests executed.
- Fake transport source tests now cover readiness denial, revocation races, create/ETag compatibility, one-shot conflict retry, wrong document owner, malformed remote and diagnostic redaction. They are authored but not executed in this task.

## Phase 7 — copy-only inactive migration and rollback

- Explicit in-memory core/history legacy projection → independent confirmation → random new inactive namespace; original prefs/files untouched.
- Durable PREPARED/COMPLETE/ROLLED_BACK journal around local backup copy. Interrupted copies remain quarantined; catalog rejects unregistered local-backup orphan metadata.
- Inactive preview offers separately confirmed rollback: revokes local binding, removes only named registered inactive namespace/index, never deletes original legacy/files/remote documents. Exact owner validation required.
- Selected-domain migration now covers typed settings/stats/dislikes/scripts/SmartSource/sort/scroll projections. Destination preflight, PREPARED/COMPLETE readback, interruption quarantine, explicit rollback and source recheck preserve legacy data and never copy credentials/device grants/files.

## Phase 8 — account/API/proxy detachment prerequisite outcome

Codex read-only audit `/root/build-logs/codex-local-first-review.md` enumerates unresolved typed search, categories, singer/detail/import, cloud-source and proxy call paths. Public literal script egress is now direct-eligible; private/reserved/loopback still fail closed. Pending account saves/pulls capture identity generation and credentials to reject same-name ABA/late snapshots.

**Removal prerequisite not satisfied:** the plan explicitly requires direct/script/backup/WebDAV and device acceptance before deleting account/API fallback or proxy. No SDK/JVM/device/network execution is authorized in this task, and source audit still identifies unimplemented direct capabilities. Existing endpoints/proxy and server data are retained, not declared detached. No VPS edits/deletion/stop.

## Verification actually performed

- Final repository structural runner: **93/93 PASS**, logs `/root/build-logs/local-first-continuation-guards.json`.
- `git diff --check` PASS; 37 untracked files independently whitespace-checked, no diagnostics (final command output; earlier JSON is historical).
- Optional Kotlin tree-sitter syntax parser: newest ABI crashed (SIGSEGV); pinned `tree-sitter==0.25.2` works. **76 modified/new Kotlin files, 0 novel parser diagnostics**; baseline LocalStore grammar diagnostics remain outside changed grammar. This is NOT compilation/type checking.
- JUnit/Robolectric and in-memory transport tests are authored; their RED/GREEN cycle and JVM/lint/Android compilation were NOT RUN. No SDK/Gradle/kotlinc/javac, build/CI/APK/Release, git commit/push, actual account/media/WebDAV requests or VPS modifications.

## Remaining source/external gates (not complete)

Device-global cache/output/SAF permissions and crash diagnostics intentionally remain outside profile migration. Activation and profile remote readiness remain closed. External gates are authorized JVM/lint/Android compilation, device/media/restart acceptance, real direct-platform/script acceptance and WebDAV acceptance. Account/API/proxy removal remains gated by those results and is not claimed complete.
