#!/usr/bin/env python3
"""Source contracts for review F1-F7. Not Kotlin execution or behavioral proof."""
from pathlib import Path
r = Path(__file__).resolve().parents[1]
b = r / 'app/src/main/java/xyz/cncnlp/soundwave'
t = r / 'app/src/test/java/xyz/cncnlp/soundwave'
def source(name): return (b / name).read_text()
w = source('migration/ProfileWorkIdentity.kt')
assert 'INPUT_CANCELLATION_EPOCH' in w and 'cancellation_epoch' in w
assert 'epoch == cancellationEpoch(context)' in w
assert 'commitPersistentWork' in w and 'synchronized(durableWorkLock)' in w
assert 'fun persistentWork(): ProfileContext = ProfileContext.legacy(0L)' in w
assert 'token.owner == ProfileOwner.LegacyUnassigned && epoch >= 0L && epoch == cancellationEpoch(context)' in w
assert 'if (generation < 0L || (owner == null && generation != 0L)) return null' in w
assert 'if (owner == null || owner == LEGACY_OWNER) ProfileContext.legacy(generation) else null' in w
for name, cls in [('DownloadScheduler.kt','class DownloadWorker'), ('ScriptProbeScheduler.kt','class ScriptProbeWorker')]:
    s = source(name)
    assert 'persistentWork()' in s and 'INPUT_CANCELLATION_EPOCH' in s
    worker = s.split(cls, 1)[1]
    assert 'isPersistentWork(' in worker and '.runtime()' not in worker
assert 'cancellationEpoch = cancellationEpoch' in source('DownloadScheduler.kt')
assert 'ContextData(ctx, ownerContext, cancellationEpoch).addDownloadDurably' in source('DownloadHelper.kt')
assert 'commitPersistentWork(context, owner, cancellationEpoch)' in source('ContextData.kt')
s = source('migration/ProfileSessionCoordinator.kt')
assert 'fun capture(): ProfileContext = current' in s
assert 'fun isCurrent(token: ProfileContext): Boolean = valid && current.matches(token)' in s
assert 'transactions.readLock()' in s and 'transactions.writeLock()' in s
# N2: preserve stale rejection/unlock, and require non-queuing acquisition plus both pending checks.
commit = s.split('fun <T> commit(token: ProfileContext, action: () -> T): T?', 1)[1].split('/** Remote profiles', 1)[0]
assert 'val nested = transactions.readHoldCount > 0' in commit
assert 'if (!nested && hasPendingRotation()) return null' in commit
assert 'if (!permit.tryLock()) return null' in commit and 'permit.lock()' not in commit
assert 'if ((!nested && hasPendingRotation()) || !isCurrent(token)) null else action()' in commit
assert commit.index('val nested =') < commit.index('transactions.readLock()') < commit.index('permit.tryLock()') < commit.index('!isCurrent(token)') < commit.index('action()')
assert 'queuedRotationRejectsNewButAllowsNestedCommitInAdmittedAction' in (t/'migration/ProfileSessionCoordinatorTest.kt').read_text()
rotation = s.split('fun rotateLegacy(): ProfileContext', 1)[1].split('fun <T> commit(', 1)[0]
assert 'check(transactions.readHoldCount == 0 && !transactions.isWriteLockedByCurrentThread)' in rotation
assert rotation.index('check(transactions.readHoldCount') < rotation.index('requireRotationIo()') < rotation.index('pendingRotations.incrementAndGet()') < rotation.index('barrier.lock()')
assert 'finally { barrier.unlock() }' in rotation and 'finally { pendingRotations.decrementAndGet() }' in rotation
assert 'android.os.Looper.myLooper() != android.os.Looper.getMainLooper()' in s
# N1: inspect exact production injection, not just independent monitor names.
scheduler = source('DownloadScheduler.kt')
fence = source('download/DownloadClearProtocol.kt')
assert 'internal val durableWorkLock = Any()' in w
assert 'private val generationFence = DownloadGenerationFence(ProfileWorkIdentity.durableWorkLock)' in scheduler
assert 'DownloadGenerationFence(private val lock: Any = Any())' in fence
assert 'if (readGeneration() != expectedGeneration) null else action()' in fence
assert fence.index('if (!persistGeneration(nextGeneration))') < fence.index('action(nextGeneration)')
assert 'return withCurrentGeneration(ctx, expectedGeneration, finalizeDownload)' in source('DownloadHelper.kt')
assert 'ProfileWorkIdentity.commitPersistentWork(context, ownerContext, cancellationEpoch) {\n                DownloadScheduler.update(context, item, generation)' in scheduler
# N3: both summary paths and notification share the captured epoch permit.
effects = source('PersistentProbeEffects.kt')
assert 'ProfileWorkIdentity.commitPersistentWork(context, owner, epoch) {' in effects
assert effects.index('commitPersistentWork(context, owner, epoch)') < effects.index('.edit().putString("pending_script_probe_done", text).apply()') < effects.index('afterSummary()')
probe = source('ScriptProbeScheduler.kt').split('class ScriptProbeWorker', 1)[1]
assert probe.count('PersistentProbeEffects.commitSummary(applicationContext, owner, epoch,') == 2
assert 'PersistentProbeEffects.commitSummary(applicationContext, owner, epoch, summary) {\n                if (!isStopped) notifyResult(applicationContext, summary)' in probe
assert 'saveSummary(' not in probe
# No owner-wide revocation/rotation is wired into ordinary production cancellation.
for path in b.rglob('*.kt'):
    text = path.read_text()
    if path.name != 'ProfileWorkIdentity.kt':
        assert 'cancelPersistentWork(' not in text, path
    if path.name not in ('ProfileWorkIdentity.kt', 'ProfileSessionCoordinator.kt'):
        assert 'rotateLegacy(' not in text, path
store = source('LocalStore.kt')
imp = store.split('internal fun importLocalBackupToUnactivatedProfile',1)[1].split('internal fun importedLocalBackupProfiles',1)[0]
assert imp.index('SelectedDomainCopy.preflight') < imp.index('InactiveImportJournal.prepare') < imp.index('profileWriteStarted = true')
assert 'SelectedDomainCopy.rollback(appContext, profileId, auxiliaryOperation)' in imp
assert 'SelectedDomainCopy.copy(appContext, profileId, auxiliaryDomains, auxiliaryOperation)' in imp
copy = source('SelectedDomainCopy.kt')
assert "raw.split(':').getOrNull(1) != operation" in copy
assert 'PREPARED:$operation:$manifest' in copy and 'COMPLETE:$operation:$manifest' in copy
assert 'day_sec_[0-9]{8}' in copy and 'BASIC_ISO_DATE' in copy
assert 'raw is Long && raw >= 0L' in copy and '36600' in copy
assert 'daily_sec_v1' not in copy
rollback = store.split('internal fun rollbackInactiveLocalCopy',1)[1].split('/** Explicit immutable identity',1)[0]
assert rollback.index('InactiveRollbackJournal.prepare') < rollback.index('registry.edit().clear()')
for phase in ('REGISTRY_REMOVED','AUXILIARY_CLEARED','CORE_CLEARED'): assert phase in rollback
assert 'InactiveRollbackJournal.finish' in rollback and 'receipt.token().matches(token)' in rollback
assert 'InactiveRollbackJournal.contains(context, id)' in source('migration/ProfileCatalog.kt')
assert 'InactiveRollbackJournal.read(context, profileId)?.token()' in source('InactiveCopyRollbackAction.kt')
assert 'PendingInactiveRollbacks()' in source('ExtraPages.kt')
lyric = source('LyricSourceLoader.kt')
key = lyric.split('internal fun persistentLyricKey',1)[1].split('private fun loadNetworkCached',1)[0]
assert 'encodeOwner(owner)' in key and 'owner.generation' not in key
assert 'ProfileOwner.LegacyUnassigned' in lyric and 'CacheManager.loadLyric(context, lyricCacheKey(song, "v2yrc"))' in lyric
assert 'ProfileWorkIdentity.commitWork(owner)' in lyric
codec = (t / 'migration/ProvenanceScannerTest.kt').read_text()
assert 'getInt("version")' in codec and 'assertNotEquals(encoded, it)' in codec
assert 'encoded.replace("\\\"version\\\":1"' not in codec
regressions = (t / 'ReviewMigrationRegressionTest.kt').read_text()
for anchor in ('rollbackResumesAfterEveryDestructivePhase', 'importerNeverAdoptsPreexistingAuxiliaryJournals', 'dailyStatsProjectionPreservesActualProducer', 'lyricDiskKeySurvivesSessionRenewal', 'mismatchedAuxiliaryReceipt'):
    assert anchor in regressions
assert 'blockedDurableWriterDoesNotBlockIdentityReads' in (t/'migration/ReviewOwnershipRegressionTest.kt').read_text()
print('PASS F1-F7 source contracts; authored Kotlin/fake regressions NOT RUN')
