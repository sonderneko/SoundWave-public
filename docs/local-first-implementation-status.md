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
- Runtime remains legacy-only and activation/switch remains closed. A real process-scoped `ProfileSessionCoordinator` now issues an immutable random lease, rotates under one lock, and rejects late commits; recorded inactive data is still not a runtime lease. ContextPreferences, scripts, sort/scroll, lyrics, resolver/cache keys, playback checkpoint, MediaSession callbacks, Service recovery and controller transactions retain captured owner/generation; unknown/legacy work is never rebound.

## Phase 4–5 — playback and persistent work

- Resolve transactions/tickets and snapshots carry owner context; resolver rejects foreign runtime owner. History writes use captured transaction/event owner.
- Service listener captures owner; natural `STATE_ENDED` checks owner but preserves the existing player-instance/generation exception needed for natural advance.
- Notification service intents carry owner/generation, malformed/foreign identity rejected. Snapshot checkpoint owner envelope preserved; profile overload owner now matches profile namespace.
- Download and script-probe requests serialize owner/generation; foreign owners rejected, missing owner maps only to old legacy tasks. Downloads retain clear-generation fence and original legacy job identity. New task identity uses SHA-256 instead of colliding Java hash, stored in input; old requests derive original old ID.
- **Limit:** recorded profile runtime jobs remain prohibited and no activation API exists. Full Android compilation/JVM/device acceptance is still pending. Device-global SAF/output/files remain deliberately outside profile data; legacy jobs remain in the legacy queue.

## Phase 6 — explicit inactive profile WebDAV

- Inactive preview exposes separate binding UI and local revoke, but opt-in/test/sync now fail closed at coordinator readiness **before credential namespace reads or requests**. Imported profile remains LOCAL_ONLY; readiness is deliberately not enabled.
- Binding holds independent random document ID, revision (ABA protection), encrypted credentials, scope `core-history`, conditional merge-once conflict policy. Namespace never falls back to legacy credentials.
- Bound sync includes only named-profile independent history, rechecks binding/provenance before PUT/retry/apply, records successful PUT ETag/readback, remerges current local core/history, and validates safe inactive-format projection before commit.
- Existing remote-file-without-ETag compatibility remains; no mandatory ETag change. No real WebDAV requests executed.
- Fake transport tests are authored for readiness denial, revocation, independent history, conditional create, missing ETag compatibility, conflict retry, wrong owner, malformed remote and diagnostic redaction; execution remains pending.

## Phase 7 — copy-only inactive migration and rollback

- Explicit in-memory core/history legacy projection → independent confirmation → random new inactive namespace; original prefs/files untouched.
- Durable PREPARED/COMPLETE/ROLLED_BACK journal around local backup copy. Interrupted copies remain quarantined; catalog rejects unregistered local-backup orphan metadata.
- Inactive preview offers separately confirmed rollback: revokes local binding, removes only named registered inactive namespace/index, never deletes original legacy/files/remote documents. Exact owner validation required.
- Explicit selected-domain projection now covers settings, stats, dislikes, scripts, SmartSource, sort and scroll with typed allowlists; credentials, device grants, URLs and files remain excluded. PREPARED/COMPLETE copy namespaces are preflighted, serialized, verified on read, and rollback is quarantined/fail-closed. Original legacy data is never deleted.

## Phase 8 — account/API/proxy detachment prerequisite outcome

Codex read-only audit `/root/build-logs/codex-local-first-review.md` enumerated unresolved typed search, categories, singer/detail/import, cloud-source and proxy call paths. This continuation implements the independently source-verifiable public direct subset without credentials: `PlaylistCategoriesDirect` (TX/KG/KW/MG native taxonomies), `PlaylistRecommendDirect` (WY/TX/KG squares and the native KW/MG square envelopes when present), `AdditionalAlbumDirect` (KW/KG/MG public album routes), `ArtistDirectFetch` (WY/TX/KG public song/detail routes), `TypedEntitiesDirect` (WY/KW/KG/MG entity search), and `PlaylistImportDirect` (strict public share URL parsing plus native detail). Existing parser samples were copied unchanged into `app/src/test/resources/step8/`; `Step8DirectRegressionTest` is authored but deliberately not executed under the no-compiler/no-JVM cue.

All new routes are direct-first and retain explicit server fallback for empty/unavailable/unsupported upstream behavior. The implementation does not claim that a provider's empty response is capability evidence: KW/MG artist detail stays fallback-only, MG album stays fallback-only when the known upstream envelope is empty, and cloud-source/account/auth, server snapshot, script proxy, and real media resolution remain external or legacy-gated. Public literal script egress is direct-eligible; private/reserved/loopback still fail closed. Pending account saves/pulls capture identity generation and credentials to reject same-name ABA/late snapshots.

**Removal prerequisite not satisfied:** the plan explicitly requires direct/script/backup/WebDAV and device acceptance before deleting account/API fallback or proxy. No SDK/JVM/device/network execution is authorized in this task, and source audit still identifies unimplemented direct capabilities. Existing endpoints/proxy and server data are retained, not declared detached. No VPS edits/deletion/stop.

## Verification actually performed

- Latest step8 structural runner: **92/94 PASS**, logs `/root/build-logs/local-first-continuation-guards.json`. Both failures are historical exact-baseline guards (`home-official-recommendation-registry-guard.sh`, `search-typed-registry-guard.sh`) requiring the old non-suspend/server-only recommendation contract. They were executed unchanged, not disabled or weakened. The new `step8-direct-source-guard.sh` verifies exact copied-fixture provenance, direct production hooks, retained fallback and unchanged HTTP policy.
- `git diff --check` PASS. New step8 source/fixtures have separate source/provenance checks; tracked plus untracked whitespace validation is recorded in the step8 report.
- Optional Kotlin tree-sitter syntax parser: pinned `tree-sitter==0.25.2` works. **90 modified/new Kotlin files, 0 novel parser diagnostics**; baseline LocalStore grammar diagnostics remain outside the changed grammar. This is NOT compilation/type checking.
- JUnit/Robolectric and in-memory transport tests are authored; their RED/GREEN cycle and JVM/lint/Android compilation were NOT RUN. No SDK/Gradle/kotlinc/javac, build/CI/APK/Release, git commit/push, actual account/media/WebDAV requests or VPS modifications.

## Remaining source/external gates (not complete)

Device-global cache/output/SAF permissions and crash diagnostics remain explicit device resources, not copied profile data. ProfileWebDavFakeIntegrationTest now covers transport denial/revocation/conflict/ETag/owner/error behavior. External blockers: authorized JVM/lint/Android compilation, device/media playback and restart acceptance, real direct-platform/script acceptance, and fake/real WebDAV acceptance. Account/API/proxy removal remains gated until direct/script/backup/WebDAV/device acceptance; no claim that it is gone. The historical source-registry guards for the pre-step8 server-only recommendation/category contract now report expected contract drift rather than being bypassed; no guard policy was weakened.
