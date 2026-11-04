package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.cncnlp.soundwave.sync.CloudUserMerge
import xyz.cncnlp.soundwave.sync.WebDavHistoryReconciler
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/**
 * 本机资料 / 用户数据状态单点（原云同步控制器，账号系统去除后保留本地职责）。
 *
 * SoundWave 云账号已整套下线：本类不再有任何登录态、凭证与服务器账号请求。
 * 它持有本机用户数据（喜欢/歌单/历史/头像）与本地资料名，并统一「本地保存 +
 * WebDAV 同步能力签发 + WebDAV 合并落盘」逻辑。WebDAV 数据归属键由本地资料名
 * 派生（AccountDataIdentity.key），与旧账号同名时键不变，既有远端负载可续用。
 * scripts 为跨搜索/下载/播放共享状态，通过 getter 注入；history 仍由
 * PlaybackController 通过回调写入。
 */
class CloudSyncController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val getScripts: () -> List<Script>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val profileId: ProfileId? = null,
) {
    private val isProfileBound = profileId != null
    private val legacyData = ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
    // 本地身份代次：改名时递增，使此前签发的 WebDAV 同步能力立即失效（与旧账号切换语义一致）。
    @Volatile private var identityGeneration = 0L
    private val saveMutex = Mutex()
    /** Shared local-data lease; WebDAV callers acquire this before their transaction slot. */
    internal suspend fun <T> withWebDavAccountLock(action: suspend () -> T): T =
        saveMutex.withLock { action() }
    val userBundleState = mutableStateOf(
        if (profileId == null) {
            check(LocalStore.detachInactiveAccountLocalScope(context)) { "账号本机封存未确认" }
            legacyData.load()
        } else LocalStore.load(context, profileId)
    )
    var userBundle: LocalStore.UserBundle
        get() = userBundleState.value
        set(v) { userBundleState.value = v }
    val historyState = mutableStateOf(
        if (profileId == null) legacyData.history() else LocalStore.loadHistory(context, profileId)
    )
    var history: List<Song>
        get() = historyState.value
        set(v) { historyState.value = PlaybackHistoryPolicy.retainRecent(v) }
    // 本机资料头像：启动时从本机头像存储恢复（见 init）；云端恢复路径已随账号删除。
    val avatarPathState = mutableStateOf("")
    var avatarPath: String
        get() = avatarPathState.value
        set(v) {
            val previous = avatarPathState.value
            if (previous != v) {
                AvatarStore.retainOwnerVersion(this, v)
                avatarPathState.value = v
            }
        }
    @Volatile private var avatarMutationGeneration = 0L
    // 资料建立标记：启动时资料名已存在（init）或本地改名完成时置位；裸 user 赋值不算建立。
    // WebDAV 头像能力据此区分「已建立的本机资料」与「仅写了名字的未验证状态」。
    private var establishedProfile: Pair<String, Long>? = null
    // 本机确认过的头像归属名（显式选图或启动恢复时置位）；WebDAV 头像同步据此签发能力。
    private var verifiedAvatarUser: String? = null
    private var localAvatarSyncOwner: Pair<String, Long>? = null
    val avatarNeedSyncState = mutableStateOf(false)
    var avatarNeedSync: Boolean
        get() = avatarNeedSyncState.value
        set(v) { avatarNeedSyncState.value = v }
    internal var onWebDavIdentityInvalidated: () -> Unit = {}

    /** 本地资料名（prefs `user`；升级前为账号名，语义已转为纯本地资料）。 */
    val userState = mutableStateOf(if (isProfileBound) "" else prefs.getString("user", "") ?: "")
    var user: String
        get() = userState.value
        set(v) {
            if (userState.value != v) {
                onWebDavIdentityInvalidated()
                xyz.cncnlp.soundwave.sync.WebDavOperations.identityChange {
                    userState.value = v
                }
            }
        }

    init {
        if (profileId == null && user.isNotBlank()) establishedProfile = user.trim() to identityGeneration
        if (profileId == null) runCatching {
            userBundle = userBundle.copy(todayStylePreferencesJson =
                xyz.cncnlp.soundwave.recommend.StylePreferenceStore.applySynced(context, userBundle.todayStylePreferencesJson))
        }.onFailure { android.util.Log.w("SWCloud", "风格设置恢复未确认；保留原始快照") }
        if (profileId == null) restoreLocalAvatar()
    }

    /** 启动恢复本机头像：仅读本机 AvatarStore 中归属当前资料名的文件，不联网。 */
    private fun restoreLocalAvatar() {
        val owner = user.trim()
        if (owner.isBlank()) return
        val path = AvatarStore.accountSpecificPath(context, owner)
        if (path.isNotBlank()) {
            verifiedAvatarUser = owner
            localAvatarSyncOwner = owner to identityGeneration
            avatarPath = path
        }
    }

    /** 本机显式选图后确认头像归属（原 acceptExplicitAuthenticatedAvatar 的本地版，去掉登录条件）。 */
    fun acceptExplicitLocalAvatar(localUser: String, path: String): Boolean {
        if (profileId != null || localUser.isBlank() || path.isBlank() ||
            !user.equals(localUser) || !AvatarStore.isAccountAvatarPath(context, localUser, path) ||
            AvatarStore.toB64(path).isBlank()
        ) return false
        if (!AvatarStore.clearRetiredUsername(context, localUser)) return false
        avatarMutationGeneration += 1L
        verifiedAvatarUser = localUser.trim()
        localAvatarSyncOwner = localUser.trim() to identityGeneration
        avatarPath = path
        userBundle = userBundle.copy(avatarB64 = AvatarStore.toB64(path))
        return true
    }

    /** 改名时可携带的本机会话已确认头像路径。 */
    fun verifiedAvatarPathForLocalRename(localUser: String): String =
        avatarPath.takeIf { verifiedAvatarUser?.equals(localUser) == true }.orEmpty()

    /**
     * 本地改名收尾：迁移本机作用域（LocalStore 作用域 + 日统计 + 头像目录由调用方先行处理）、
     * 更新资料名与统计归属键、递增身份代次使旧名签发的 WebDAV 能力失效。
     * 全部为本机操作，不联网。任一步失败返回 false 且不改名（调用方保持原名）。
     */
    fun completeSuccessfulLocalRename(oldUser: String, newUser: String, ownedAvatarPath: String): Boolean {
        if (profileId != null || newUser.isBlank() || !user.equals(oldUser)) return false
        if (oldUser.isBlank()) {
            // 首次命名（资料建立）：无旧作用域可迁移，只确立归属与建立标记。
            val established = userBundle.copy(
                dailyListenStatsJson = AccountDataIdentity.ownedStats(userBundle, newUser),
                dailyListenStatsOwnerV2 = AccountDataIdentity.key(newUser), legacyDailyListenStatsJson = null
            )
            if (runCatching { legacyData.saveWithHistory(established, history, newUser) }.isFailure) return false
            userBundle = established
            verifiedAvatarUser = null
            avatarPath = ""
            identityGeneration += 1L
            establishedProfile = newUser.trim() to identityGeneration
            user = newUser.trim()
            return true
        }
        val verifiedNewPath = ownedAvatarPath.takeIf {
            it.isNotBlank() && AvatarStore.accountSpecificPath(context, newUser) == java.io.File(it).absolutePath
        }
        val renamedBundle = userBundle.copy(
            dailyListenStatsJson = AccountDataIdentity.ownedStats(userBundle, oldUser),
            dailyListenStatsOwnerV2 = AccountDataIdentity.key(newUser), legacyDailyListenStatsJson = null
        )
        val scopeMoved = runCatching {
            ListenStats.renameAccountDailyStats(context, oldUser, newUser) &&
                LocalStore.renameAccountLocalScope(context, oldUser, newUser, renamedBundle, history)
        }.getOrDefault(false)
        if (!scopeMoved) return false
        if (runCatching { legacyData.saveWithHistory(renamedBundle, history, newUser) }.isFailure) return false
        userBundle = renamedBundle
        if (verifiedNewPath != null) {
            verifiedAvatarUser = newUser.trim()
            avatarPath = verifiedNewPath
        } else {
            verifiedAvatarUser = null
            avatarPath = ""
        }
        identityGeneration += 1L
        localAvatarSyncOwner = verifiedAvatarUser?.let { it to identityGeneration }
        establishedProfile = newUser.trim() to identityGeneration
        user = newUser.trim()
        return true
    }

    /** 改名/资料切换时使在途的旧身份能力失效（原 invalidatePendingAccountAuth 的本地版）。 */
    fun invalidatePendingLocalIdentityChange() {
        onWebDavIdentityInvalidated()
        xyz.cncnlp.soundwave.sync.WebDavOperations.invalidate()
        identityGeneration += 1L
        avatarMutationGeneration += 1L
    }

    /** 串行化一次完整的本地身份变更（改名落盘期间挡住并发本地保存与 WebDAV 事务）。 */
    suspend fun <T> withLocalIdentityTransition(block: suspend () -> T): T {
        onWebDavIdentityInvalidated()
        val endTransition = xyz.cncnlp.soundwave.sync.WebDavOperations.beginIdentityTransition()
        try {
            return saveMutex.withLock { block() }
        } finally {
            endTransition()
        }
    }

    fun persistBundle(notify: Boolean = false) {
        xyz.cncnlp.soundwave.sync.WebDavSyncState.markLocalChanged(context)
        if (!isProfileBound) {
            userBundle = userBundle.copy(todayStylePreferencesJson =
                xyz.cncnlp.soundwave.recommend.StylePreferenceStore.snapshot(context, userBundle.todayStylePreferencesJson))
            userBundle.enabledScripts.clear()
            userBundle.enabledScripts.addAll(getScripts().filter { it.enabled }.map { it.id })
            userBundle.scriptOrder.clear()
            userBundle.scriptOrder.addAll(getScripts().map { it.id })
        }
        val retainedHistory = PlaybackHistoryPolicy.retainRecent(history)
        history = retainedHistory
        userBundle.history.clear()
        userBundle.history.addAll(retainedHistory)
        val plsFix = userBundle.playlists.toMutableList()
        var changed = false
        for (i in plsFix.indices) {
            val pl = plsFix[i]
            if (pl.cover.isEmpty()) {
                val cv = pl.songs.firstOrNull()?.cover.orEmpty()
                if (cv.isNotEmpty()) { plsFix[i] = pl.copy(cover = cv); changed = true }
            }
        }
        if (changed) userBundle = userBundle.copy(playlists = plsFix)
        userBundle = userBundle.copy(
            playlists = userBundle.playlists.filterNot {
                it.id.startsWith("rank_") || it.id.startsWith("hot_") || it.id.startsWith("reco_")
            }.toMutableList()
        )
        val boundProfileId = profileId
        if (boundProfileId == null) legacyData.save(userBundle)
        else LocalStore.save(context, boundProfileId, userBundle)
        if (boundProfileId == null && user.isNotBlank()) {
            val ownerKey = AccountDataIdentity.key(user)
            if (LocalStore.accountDataOwner(context) == ownerKey) AccountOwnedDataChanges.localChange(ownerKey)
        }
        if (notify) {
            AppNotice.attach(context.applicationContext)
            AppNotice.toast("已保存到本机", AppNotice.Kind.NEUTRAL)
        }
    }

    fun deleteHistoryRecords(songs: List<Song>) {
        xyz.cncnlp.soundwave.sync.WebDavSyncState.markLocalChanged(context)
        if (songs.isEmpty()) return
        val deletedEventKeys = songs.asSequence()
            .filter { it.playedAt > 0L }
            .mapTo(linkedSetOf()) { PlaybackHistoryPolicy.historyEventKey(it) }
        if (deletedEventKeys.isEmpty()) return
        val updated = userBundle.syncSnapshot()
        songs.forEach { updated.deleteHistoryEvent(it) }
        userBundle = updated
        history = history.filterNot { PlaybackHistoryPolicy.historyEventKey(it) in deletedEventKeys }
        persistBundle()
    }

    /** WebDAV 数据同步能力：归属键由本地资料名派生；改名/资料变更后旧能力立即失效。 */
    internal fun prepareWebDavAccountDataSync(): xyz.cncnlp.soundwave.sync.WebDavAccountDataSync? {
        val owner = user
        if (profileId != null || owner.isBlank()) return null
        val key = AccountDataIdentity.key(owner)
        val identity = identityGeneration
        fun current() = user == owner &&
            identityGeneration == identity && LocalStore.accountDataOwner(context) == key
        if (!current()) return null
        return xyz.cncnlp.soundwave.sync.WebDavAccountDataSync(owner, key, ::current)
    }

    /** WebDAV 头像同步能力：只携带本机已确认归属的头像，绝不按名猜测文件。 */
    fun prepareWebDavAvatarSync(): xyz.cncnlp.soundwave.sync.WebDavAvatarSync? {
        val owner = user.trim()
        val identity = identityGeneration
        val mutation = avatarMutationGeneration
        fun isCurrent(): Boolean = profileId == null && owner.isNotBlank() && user == owner &&
            identityGeneration == identity && avatarMutationGeneration == mutation &&
            (localAvatarSyncOwner == (owner to identity) || verifiedAvatarUser == owner ||
                establishedProfile == (owner to identity))
        if (!isCurrent()) return null
        val portableAvatar = if (verifiedAvatarUser == owner &&
            AvatarStore.isAccountAvatarPath(context, owner, avatarPath)) AvatarStore.toB64(avatarPath) else ""
        return xyz.cncnlp.soundwave.sync.WebDavAvatarSync(
            accountKey = xyz.cncnlp.soundwave.sync.WebDavAvatarSync.stableAccountKey(owner),
            avatarB64 = portableAvatar,
            isCurrent = ::isCurrent,
            apply = { portable ->
                // The UI invokes this on the main thread after the data-only local merge.
                // A rename, profile switch or explicit avatar selection invalidates the captured state.
                if (isCurrent() && portable.isNotBlank() && portable != portableAvatar) {
                    val safeImage = xyz.cncnlp.soundwave.sync.WebDavAvatarSync.validatedImageDataUri(portable)
                    val restored = safeImage?.let { AvatarStore.saveAccountAvatarFromB64(context, owner, it) }.orEmpty()
                    if (restored.isNotBlank()) {
                        verifiedAvatarUser = owner
                        avatarPath = restored
                        avatarMutationGeneration += 1L
                        userBundle = userBundle.copy(avatarB64 = AvatarStore.toB64(restored))
                        legacyData.save(userBundle)
                    }
                }
            }
        )
    }

    /**
     * WebDAV 扩展载荷能力（脚本/智能换源/设置/不喜欢）：与账户数据能力同一归属门禁，
     * 本机资料名或代际变化后立即失效。capture/apply 只触碰各自存储，不进 bundle 合并。
     */
    fun prepareWebDavProfileExtrasSync(): xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync? {
        val owner = user
        if (profileId != null || owner.isBlank()) return null
        val key = AccountDataIdentity.key(owner)
        val identity = identityGeneration
        fun current() = user == owner &&
            identityGeneration == identity && LocalStore.accountDataOwner(context) == key
        if (!current()) return null
        return xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync(
            capture = { xyz.cncnlp.soundwave.sync.WebDavProfileExtras.capture(context) },
            apply = { extras -> xyz.cncnlp.soundwave.sync.WebDavProfileExtras.apply(context, extras) },
            isCurrent = ::current
        )
    }

    /** Apply a WebDAV merge to this device only; deliberately does not call persistBundle(). */
    fun applyWebDavBundleLocally(incoming: LocalStore.UserBundle) {
        if (profileId != null || user.isBlank()) return
        val owner = AccountDataIdentity.key(user)
        check(incoming.dailyListenStatsOwnerV2 == owner) { "WebDAV 资料归属不匹配" }
        val currentBundle = userBundle.syncSnapshot().copy(
            songPlayCountCounters = linkedMapOf(),
            dailyListenStatsOwnerV2 = owner,
            dailyListenStatsJson = ListenStats.accountDailyListenStatsForAccount(
                context, user, AccountDataIdentity.ownedStats(userBundle, user)),
            // 本地侧只取当前资料作用域内的风格存储：userBundle 的副本可能在控制器
            // 无身份构造时被游客态（modifiedAt=0 的合成条目）污染，而 merge 规则对
            // 无时间戳条目恒判本地胜，会让游客残值永久压过远端风格设置。
            todayStylePreferencesJson = xyz.cncnlp.soundwave.recommend.StylePreferenceStore.snapshot(
                context)
        )
        val merged = CloudUserMerge.merge(currentBundle, incoming.copy(avatarB64 = "")).copy(
            avatarB64 = currentBundle.avatarB64
        )
        val reconciledHistory = WebDavHistoryReconciler.reconcile(
            independentHistory = legacyData.history(),
            localBundleHistory = currentBundle.history,
            webDavHistory = incoming.history,
            deletedHistoryEventKeys = merged.historyDeletionTombstones.keys
        )
        val localHistory = PlaybackHistoryPolicy.retainRecent(reconciledHistory)
        val mergedWithHistory = merged.copy(history = localHistory.toMutableList())
        check(ListenStats.mergeAccountDailyListenStatsForAccount(context, user, merged.dailyListenStatsJson)) {
            "WebDAV 歌曲统计本机写入未确认"
        }
        mergedWithHistory.todayStylePreferencesJson = xyz.cncnlp.soundwave.recommend.StylePreferenceStore.applySynced(
            context, mergedWithHistory.todayStylePreferencesJson)
        userBundle = mergedWithHistory
        history = localHistory
        legacyData.saveWithHistory(userBundle, history, user)
    }
}
