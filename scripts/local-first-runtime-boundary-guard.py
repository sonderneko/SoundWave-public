#!/usr/bin/env python3
"""Structural assertions only; this does not run Kotlin/JVM behavior tests."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/xyz/cncnlp/soundwave'
gate = (base / 'migration/LegacyUnassignedSnapshot.kt').read_text()
inventory = (base / 'migration/RuntimeProvenanceInventory.kt').read_text()
body = gate.split('fun accountSyncGateState(context: Context)', 1)[1].split('fun accountSyncGateMessage', 1)[0]
assert 'RuntimeProvenanceInventory.scan(' not in body, 'UI gate must not scan/commit catalog'
assert 'RuntimeProvenanceInventory.cachedState' in body, 'UI must use non-authorizing cached evidence'
assert 'fun verifyDataOperation' in inventory, 'IO boundary must freshly verify evidence'
assert 'root.listFiles()' in inventory and 'childrenPresence' in inventory, 'root existence is not user content'
assert 'TECHNICAL_KEYS' in inventory, 'exact metadata exclusion required'
assert 'invalidateBeforeMutation' in inventory, 'write-side cache invalidation missing'
assert 'synchronized(scanLock)' in inventory and 'synchronized(lock)' in inventory, 'scan may not block UI mutation invalidation'
preferences = (base / 'ContextPreferences.kt').read_text()
assert 'override fun commit(): Boolean = ProfileWorkIdentity.commitWork(owner) { validate() && editor.commit() } == true' in preferences
assert 'ProfileWorkIdentity.commitWork(owner)' in preferences
assert 'check(validate())' in preferences and 'override fun apply()' in preferences
main = (base / 'MainActivity.kt').read_text()
assert 'localData.save(userBundle)' in main and 'eventData.pushHistory(changed)' in main
controller = (base / 'PlaybackController.kt').read_text()
assert 'ContextData(context, transaction.ownerContext).pushHistory(song)' in controller
service = (base / 'PlayerService.kt').read_text()
assert 'listenerOwner.matches(serviceOwner)' in service and 'INPUT_SESSION_GENERATION' in service
for name in ('DownloadScheduler.kt', 'ScriptProbeScheduler.kt'):
    source = (base / name).read_text()
    assert 'INPUT_OWNER' in source and 'INPUT_SESSION_GENERATION' in source and '.decode(' in source, name
remote = (base / 'sync/WebDavSync.kt').read_text()
assert 'profileHistory = { LocalStore.loadHistory(context, owner.id) }' in remote, 'profile sync cannot read legacy history'
assert 'recordSuccessfulPut' in remote and 'result.etag' in remote
binding = (base / 'sync/ProfileRemoteBindings.kt').read_text()
assert 'revision' in binding and 'lastSuccessfulPut' in binding
store = (base / 'LocalStore.kt').read_text()
assert store.index('InactiveImportJournal.prepare') < store.index('InactiveImportJournal.complete')
assert 'rollbackInactiveLocalCopy' in store and 'InactiveCopyRollbackAction(profileId, onDismiss)' in (base / 'UnactivatedProfilePreview.kt').read_text()
sessions = (base / 'migration/ProfileSessionCoordinator.kt').read_text()
# Exclusive rotation replaces the old identity monitor; preserve monotonic publication
# and unlock while rejecting recursive upgrades before any writer wait.
rotation = sessions.split('fun rotateLegacy(): ProfileContext', 1)[1].split('fun <T> commit(', 1)[0]
assert 'private val transactions = java.util.concurrent.locks.ReentrantReadWriteLock()' in sessions
assert '@Volatile private var current' in sessions and '@Volatile private var valid' in sessions
assert 'check(transactions.readHoldCount == 0 && !transactions.isWriteLockedByCurrentThread)' in rotation
assert rotation.index('pendingRotations.incrementAndGet()') < rotation.index('transactions.writeLock()') < rotation.index('barrier.lock()') < rotation.index('current = ProfileContext.legacy(current.generation + 1L)') < rotation.index('barrier.unlock()')
assert 'finally { pendingRotations.decrementAndGet() }' in rotation
commit = sessions.split('fun <T> commit(token: ProfileContext, action: () -> T): T?', 1)[1].split('/** Remote profiles', 1)[0]
assert 'val nested = transactions.readHoldCount > 0' in commit
assert 'if (!nested && hasPendingRotation()) return null' in commit
assert 'if (!permit.tryLock()) return null' in commit and 'permit.lock()' not in commit
assert 'if ((!nested && hasPendingRotation()) || !isCurrent(token)) null else action()' in commit
assert 'finally { permit.unlock() }' in commit
session_test = (root / 'app/src/test/java/xyz/cncnlp/soundwave/migration/ProfileSessionCoordinatorTest.kt').read_text()
assert 'queuedRotationRejectsNewButAllowsNestedCommitInAdmittedAction' in session_test
stats = (base / 'ListenStats.kt').read_text()
stats_write = stats.split('fun updateStats(', 1)[1].split('private val sensitiveCoverQueryKeys', 1)[0]
assert 'editor.commit()' in stats_write and '.apply()' not in stats_write
assert 'catch (_: Exception) {\n            false\n        }' in stats_write
context_data = (base / 'ContextData.kt').read_text()
assert 'return ProfileWorkIdentity.commitWork(owner) { if (id == null) ListenStats.markPlay' in context_data
assert 'return ProfileWorkIdentity.commitWork(owner) { if (id == null) ListenStats.addDuration' in context_data
# r658c D#2: listening stats are written by PlayerService's serial writer (not a cancellable UI coroutine).
service_src = (root / 'app/src/main/java/xyz/cncnlp/soundwave/PlayerService.kt').read_text() if 'root' in dir() else open('app/src/main/java/xyz/cncnlp/soundwave/PlayerService.kt', encoding='utf-8').read()
assert 'listenStatsWriter.execute' in service_src and 'data.addDuration(it.song, it.seconds)' in service_src
# Rejected preference publication must be observable, not a silent Unit return.
apply_body = preferences.split('override fun apply()', 1)[1]
assert 'check(ProfileWorkIdentity.commitWork(owner) {' in apply_body
assert apply_body.index('check(validate())') < apply_body.index('editor.apply()')
assert '} == true)' in apply_body
# This is a wrapper contract only: nested multi-write actions require independent review.
assert 'fun remoteReady(token: ProfileContext): Boolean = false' in sessions, 'activation/remote readiness must remain closed'
assert binding.index('remoteReady(token)') < binding.index('SecretStore.encrypt'), 'opt-in must fail before encryption'
load_binding = binding.split('fun load(context:', 1)[1].split('fun recordSuccessfulPut', 1)[0]
assert load_binding.index('remoteReady(token)') < load_binding.index('prefs(context, owner.id)'), 'credentials must not be read before readiness'
resolver = (base / 'UrlResolver.kt').read_text()
assert 'encodeOwner(owner)}:${owner.generation}' in resolver
assert 'sc.id, sc.enabled, sc.code' in resolver, 'cache key must include script configuration'
assert 'runtime.ownerContext' in resolver and 'requestStillCurrent()' in resolver
copy = (base / 'SelectedDomainCopy.kt').read_text()
assert 'PREPARED:' in copy and 'COMPLETE:' in copy and 'Occupied copy namespace' in copy
assert 'SCROLL("list_scroll_v1",' in copy, 'copy must use the production scroll namespace'
assert 'private const val PREF = "list_scroll_v1"' in (base / 'ListScrollStore.kt').read_text()
assert 'ProfileWorkIdentity.commitWork(owner)' in preferences
assert 'private fun requireOwner()' in preferences, 'retained preference reads must reject stale leases'
assert 'PlaybackStore.load(applicationContext, serviceOwner)' in service
assert 'mediaOwnerCurrent()' in service
print('PASS local-first integrated runtime boundary structural checks (not JVM tests)')
