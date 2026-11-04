package xyz.cncnlp.soundwave.ui.notice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全 App 统一提示出口（Toast 分流落地，见 SoundWave-Toast 设计说明）：
 * quiet（不提示，状态本身即反馈，由调用方直接不调本类）/ [toast] 底部胶囊 /
 * [undo] 可撤销胶囊 / [banner] 顶部横幅 / [inbox] 消息中心。
 *
 * 分类（ACCEPT5，用户 2026-11-04 裁定）：一切提示按 [Tier] 三分——
 * [Tier.ALERT] 强提示（报错、需当场处理的问题、可撤销的破坏性操作，时长最长）、
 * [Tier.FEEDBACK] 普通反馈（成功/信息确认，短时自动消失）、
 * [Tier.SILENT] 静默（后台完成型结果，只进消息中心不弹）。归类有系统级默认
 * （见各函数），调用点可传 tier 显式覆盖；时长由 [NoticePolicy] 单点决定，
 * 调用点不许自定。台账：WAVE-ACCEPT5-NOTICE.md。
 *
 * 线程安全：所有函数任意线程可调。复合的读-改-写（去重判定、撤销回调单次触发、
 * inbox 增删）都在同一把锁内完成，状态以 [StateFlow] 原子发布，Host 只订阅渲染。
 *
 * 文案防线（调用方责任）：传入文案必须是「结果 + 下一步」的用户语言，
 * **禁止拼接原始异常**（`e.message`、异常类名、堆栈、内部 ID 等实现细节）。
 * 本类不做语义改写，只按原样呈现 / 持久化；40 字以上的长句请调用方先改短。
 *
 * 未挂载 Host 时不静默丢弃：[toast]/[undo] 在已有 context 时回退系统 [Toast]
 * （撤销动作在回退形态下不可用，调用方不要在未挂载期依赖撤销完成不可逆操作）；
 * [inbox] 照常落盘（无 context 时先驻内存，[attach] 后与磁盘合并）。
 */
object AppNotice {

    enum class Kind { NEUTRAL, SUCCESS, FAILURE }

    enum class Category { PLAYBACK, SCRIPT, SYNC, ACCOUNT, SYSTEM }

    /** 提示强度分类（见类头注释）：ALERT 强提示 / FEEDBACK 普通反馈 / SILENT 静默（仅消息中心）。 */
    enum class Tier { ALERT, FEEDBACK, SILENT }

    /**
     * 时长策略单点：所有胶囊/横幅时长只从这里出，调用点不可覆盖数值本身（只能选类）。
     * 横幅时长按用户裁定（2026-11-04「显示的时间短一点」）自 4s/6s 收紧为 3s/5s。
     * 二轮收紧（用户裁定 2026-11-04 19:50「再缩短一些，但不要影响操作」）：普通反馈档
     * 压幅最大（胶囊 2000→1500、长文案 3000→2200、横幅 2500→2000）；强提醒胶囊
     * 3500→3000（单行可从容读完）；ALERT 横幅 3000 不动（标题+正文两行，已在可读下限）；
     * 带动作的两项不动——本实现显示时长即操作有效窗口（到时自动关闭、动作随之失效），
     * 且 UNDO 4000 是 DownloadPendingDelete 删除提交的时序契约，按裁定「宁可少缩」保原值。
     */
    internal object NoticePolicy {
        const val TOAST_DURATION_MS = 1_500L
        const val TOAST_LONG_DURATION_MS = 2_200L
        const val TOAST_LONG_TEXT_THRESHOLD = 14
        const val ALERT_CAPSULE_DURATION_MS = 3_000L
        const val UNDO_DURATION_MS = 4_000L
        const val BANNER_DURATION_MS = 3_000L
        const val BANNER_ACTION_DURATION_MS = 5_000L
        const val BANNER_FEEDBACK_DURATION_MS = 2_000L

        /** 胶囊时长：带动作（撤销）恒 ALERT 档；FAILURE 报错走 ALERT；普通反馈按文案长短。 */
        fun capsuleDurationMillis(tier: Tier, kind: Kind, hasAction: Boolean, textLength: Int): Long = when {
            hasAction -> UNDO_DURATION_MS
            tier == Tier.ALERT || kind == Kind.FAILURE -> ALERT_CAPSULE_DURATION_MS
            textLength > TOAST_LONG_TEXT_THRESHOLD -> TOAST_LONG_DURATION_MS
            else -> TOAST_DURATION_MS
        }

        /** 横幅时长：FEEDBACK 档最短；ALERT 带动作给足处理时间、无动作 3s。 */
        fun bannerDurationMillis(tier: Tier, hasAction: Boolean): Long = when {
            tier == Tier.FEEDBACK -> BANNER_FEEDBACK_DURATION_MS
            hasAction -> BANNER_ACTION_DURATION_MS
            else -> BANNER_DURATION_MS
        }
    }

    /** 消息中心一条消息。列表按 [timeMillis] 倒序（最新在前）。 */
    data class InboxMessage(
        val id: Long,
        val category: Category,
        val title: String,
        val body: String?,
        val timeMillis: Long,
        val read: Boolean,
    )

    /** 底部胶囊当前状态（队列长度 1：新消息替换旧消息）。[id] 递增，Host 按 id 计时。 */
    internal data class CapsuleState(
        val id: Long,
        val message: String,
        val kind: Kind,
        val tier: Tier,
        val durationMillis: Long,
        val actionLabel: String?,
        val onAction: (() -> Unit)?,
    )

    /**
     * 顶部横幅当前状态。[id] 递增，Host 按 id 计时；上滑/右滑关闭走 [dismissBanner]。
     * [onClick] 为整条点击行为（显式优先）；[fromInbox] 的横幅在未给 onClick 时，
     * 点击走 [bannerViewHandler]（宿主注册，通常为打开通知中心）。
     */
    internal data class BannerState(
        val id: Long,
        val title: String,
        val body: String?,
        val actionLabel: String?,
        val onAction: (() -> Unit)?,
        val onClick: (() -> Unit)?,
        val fromInbox: Boolean,
        val tier: Tier,
        val durationMillis: Long,
    )

    private const val PREFS_NAME = "app_notice_inbox"
    private const val KEY_ITEMS = "app_notice_inbox"

    private const val DEDUP_WINDOW_MS = 1_500L
    private const val INBOX_MAX_ITEMS = 60
    private const val INBOX_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    /** 脚本检测报告类卡片的保留期（验收二轮 E3 用户裁定）：30 分钟，完整报告在音源脚本页。 */
    private const val SCRIPT_NOTICE_RETENTION_MS = 30L * 60L * 1_000L

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    /** AppNoticeHost 是否在树上；由 Host 的 DisposableEffect 维护。 */
    @Volatile
    private var hostMounted: Boolean = false

    internal fun setHostMounted(mounted: Boolean) {
        hostMounted = mounted
    }

    private var nextId = 0L

    private val _capsuleState = MutableStateFlow<CapsuleState?>(null)
    internal val capsuleState: StateFlow<CapsuleState?> = _capsuleState.asStateFlow()

    private val _bannerState = MutableStateFlow<BannerState?>(null)
    internal val bannerState: StateFlow<BannerState?> = _bannerState.asStateFlow()

    private val _inboxItems = MutableStateFlow<List<InboxMessage>>(emptyList())
    val inboxItems: StateFlow<List<InboxMessage>> = _inboxItems.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var inboxLoaded = false

    // toast 去重：仅记录最近一次实际展示的 toast 文案与时刻（单调时钟）。
    private var lastToastMessage: String? = null
    private var lastToastAtMs = 0L

    /**
     * 保存 applicationContext，供 inbox 持久化与无 Host 时回退系统 Toast。
     * Host 挂载时自动调用；也可由 App 启动处提前调用。重复调用安全（磁盘只合并加载一次）。
     */
    fun attach(context: Context) {
        val app = context.applicationContext ?: context
        synchronized(lock) {
            appContext = app
            loadInboxFromDiskLocked()
        }
    }

    /**
     * 横幅整条点击的默认落点（宿主在组合根注册，如打开通知中心）：仅对 [inbox] 升级出的
     * 横幅（fromInbox）且未给显式 onClick 时生效。未注册时点击无动作。
     */
    @Volatile
    var bannerViewHandler: (() -> Unit)? = null

    /**
     * 底部轻提示：默认归类 FEEDBACK（2s，长文案 3s），FAILURE 报错升 ALERT（3.5s）；
     * [tier] 可显式覆盖。同一文案 1.5s 内不重复展示。
     */
    fun toast(message: String, kind: Kind = Kind.NEUTRAL, tier: Tier? = null) {
        if (message.isBlank()) return
        val resolvedTier = tier ?: if (kind == Kind.FAILURE) Tier.ALERT else Tier.FEEDBACK
        var fallback: Context? = null
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (message == lastToastMessage && now - lastToastAtMs < DEDUP_WINDOW_MS) return
            lastToastMessage = message
            lastToastAtMs = now
            if (hostMounted || appContext == null) {
                _capsuleState.value = CapsuleState(
                    id = ++nextId,
                    message = message,
                    kind = kind,
                    tier = resolvedTier,
                    durationMillis = NoticePolicy.capsuleDurationMillis(
                        resolvedTier, kind, hasAction = false, textLength = message.length,
                    ),
                    actionLabel = null,
                    onAction = null,
                )
            } else {
                fallback = appContext
            }
        }
        // 无 Host 且已有 context：回退系统 Toast，不静默丢弃。
        fallback?.let { showSystemToast(it, message) }
    }

    /** 可撤销胶囊：恒 ALERT 类，右侧「撤销」，4s；回调只触发一次（触发即清状态，重复点击/计时竞态都不会二次执行）。 */
    fun undo(message: String, onUndo: () -> Unit) {
        if (message.isBlank()) return
        var fallback: Context? = null
        synchronized(lock) {
            if (hostMounted || appContext == null) {
                _capsuleState.value = CapsuleState(
                    id = ++nextId,
                    message = message,
                    kind = Kind.NEUTRAL,
                    tier = Tier.ALERT,
                    durationMillis = NoticePolicy.UNDO_DURATION_MS,
                    actionLabel = "撤销",
                    onAction = onUndo,
                )
            } else {
                fallback = appContext
            }
        }
        fallback?.let { showSystemToast(it, message) }
    }

    /**
     * 顶部横幅：默认 ALERT 类（无动作 3s、带按钮 5s），可上滑/右滑关闭、整条可点击查看
     * （[onClick]）；[tier] 传 FEEDBACK 时 2.5s。
     */
    fun banner(
        title: String,
        body: String? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        tier: Tier = Tier.ALERT,
        onClick: (() -> Unit)? = null,
    ) {
        postBanner(title, body, actionLabel, onAction, tier, onClick, fromInbox = false)
    }

    private fun postBanner(
        title: String,
        body: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
        tier: Tier,
        onClick: (() -> Unit)?,
        fromInbox: Boolean,
    ) {
        if (title.isBlank()) return
        synchronized(lock) {
            _bannerState.value = BannerState(
                id = ++nextId,
                title = title,
                body = body,
                actionLabel = actionLabel,
                onAction = onAction,
                onClick = onClick,
                fromInbox = fromInbox,
                tier = tier,
                durationMillis = NoticePolicy.bannerDurationMillis(tier, hasAction = actionLabel != null),
            )
        }
    }

    /**
     * 写入消息中心（持久化，上限 60 条 / 保留 30 天）。本函数即 [Tier.SILENT] 静默类：
     * 后台完成型结果（同步/检测/下载类）默认只落盘不打扰；
     * [alsoBanner] / [alsoToast] 是调用点显式升级（升级出的横幅带 fromInbox 标记，
     * 点击默认跳通知中心，见 [bannerViewHandler]），升级理由须是该结果有即时价值或
     * 属于需处理的问题而非单纯结果回执（归类表见 WAVE-ACCEPT5-NOTICE.md）。
     */
    fun inbox(
        category: Category,
        title: String,
        body: String? = null,
        alsoBanner: Boolean = false,
        alsoToast: Boolean = false,
    ) {
        if (title.isBlank()) return
        synchronized(lock) {
            loadInboxFromDiskLocked()
            val item = InboxMessage(
                id = ++nextId,
                category = category,
                title = title,
                body = body,
                timeMillis = System.currentTimeMillis(),
                read = false,
            )
            val updated = sanitizeInbox(listOf(item) + _inboxItems.value)
            _inboxItems.value = updated
            refreshUnreadLocked()
            persistInboxLocked(updated)
        }
        if (alsoBanner) postBanner(title, body, null, null, Tier.ALERT, null, fromInbox = true)
        if (alsoToast) toast(title)
    }

    /** 全部标为已读（设置页消息列表离开时调用）。 */
    fun markAllRead() {
        synchronized(lock) {
            loadInboxFromDiskLocked()
            if (_inboxItems.value.all { it.read }) return
            val updated = _inboxItems.value.map { if (it.read) it else it.copy(read = true) }
            _inboxItems.value = updated
            refreshUnreadLocked()
            persistInboxLocked(updated)
        }
    }

    /** 清空消息中心，返回被清掉的旧列表快照（供「清空 + 撤销」用 [restoreInbox] 写回）。 */
    fun clearInbox(): List<InboxMessage> {
        return synchronized(lock) {
            loadInboxFromDiskLocked()
            val snapshot = _inboxItems.value
            _inboxItems.value = emptyList()
            refreshUnreadLocked()
            persistInboxLocked(emptyList())
            snapshot
        }
    }

    /** 写回一份列表（如撤销清空）；会按上限/保留期清洗并持久化。 */
    fun restoreInbox(items: List<InboxMessage>) {
        synchronized(lock) {
            val updated = sanitizeInbox(items)
            _inboxItems.value = updated
            refreshUnreadLocked()
            persistInboxLocked(updated)
        }
    }

    /** 关闭当前横幅（Host 上滑手势调用）。 */
    fun dismissBanner() {
        synchronized(lock) { _bannerState.value = null }
    }

    /** 关闭当前底部胶囊（无条件）。 */
    fun dismissCapsule() {
        synchronized(lock) { _capsuleState.value = null }
    }

    /** Host 计时到点关闭：仅当仍是同一条（id 未被新消息替换）时才关。 */
    internal fun dismissBanner(id: Long) {
        synchronized(lock) {
            if (_bannerState.value?.id == id) _bannerState.value = null
        }
    }

    internal fun dismissCapsule(id: Long) {
        synchronized(lock) {
            if (_capsuleState.value?.id == id) _capsuleState.value = null
        }
    }

    /** Host 点击胶囊动作（撤销）：原子地取走回调并清状态，保证只执行一次。 */
    internal fun triggerCapsuleAction(id: Long) {
        val action: (() -> Unit)? = synchronized(lock) {
            val current = _capsuleState.value
            if (current == null || current.id != id) {
                null
            } else {
                _capsuleState.value = null
                current.onAction
            }
        }
        action?.invoke()
    }

    /** Host 判断横幅整条是否可点击查看：有显式 onClick，或来自消息中心且宿主注册了落点。 */
    internal fun bannerHasClick(state: BannerState): Boolean =
        state.onClick != null || (state.fromInbox && bannerViewHandler != null)

    /** Host 点击横幅整条：显式 onClick 优先，否则（消息中心横幅）走宿主注册的查看落点；触发后收起横幅。 */
    internal fun triggerBannerClick(id: Long) {
        val action: (() -> Unit)? = synchronized(lock) {
            val current = _bannerState.value
            if (current == null || current.id != id) {
                null
            } else {
                val resolved = current.onClick ?: if (current.fromInbox) bannerViewHandler else null
                if (resolved == null) {
                    null
                } else {
                    _bannerState.value = null
                    resolved
                }
            }
        }
        action?.invoke()
    }

    /** Host 点击横幅按钮：原子地取走回调并收起横幅，保证只执行一次。 */
    internal fun triggerBannerAction(id: Long) {
        val action: (() -> Unit)? = synchronized(lock) {
            val current = _bannerState.value
            if (current == null || current.id != id) {
                null
            } else {
                _bannerState.value = null
                current.onAction
            }
        }
        action?.invoke()
    }

    // ---------- 内部 ----------

    private fun refreshUnreadLocked() {
        _unreadCount.value = _inboxItems.value.count { !it.read }
    }

    /**
     * 应用更新检查状态不进消息中心（用户裁定）：更新检查只保留在关于页。
     * 历史版本曾把检查结果写成 SYSTEM 卡落盘，这里在清洗时一并移除存量卡；
     * 前缀与 GitHubReleases.check 的状态文案一一对应（崩溃报告等其他 SYSTEM 卡不受影响）。
     */
    private val UPDATE_STATUS_PREFIXES = listOf("发现新版本", "已是最新", "检查失败", "尚无可用 GitHub", "正在检查 GitHub")

    private fun isUpdateStatusCard(item: InboxMessage): Boolean =
        item.category == Category.SYSTEM && UPDATE_STATUS_PREFIXES.any { item.title.startsWith(it) }

    /** 清洗：丢弃 30 天前、剔除更新状态卡、脚本检测卡只留 30 分钟、按时间倒序、上限 60 条、同 id 只留一份。 */
    private fun sanitizeInbox(items: List<InboxMessage>): List<InboxMessage> {
        val now = System.currentTimeMillis()
        val cutoff = now - INBOX_RETENTION_MS
        val scriptCutoff = now - SCRIPT_NOTICE_RETENTION_MS
        return items
            .filter { it.timeMillis >= cutoff }
            .filter { it.category != Category.SCRIPT || it.timeMillis >= scriptCutoff }
            .filterNot { isUpdateStatusCard(it) }
            .distinctBy { it.id }
            .sortedByDescending { it.timeMillis }
            .take(INBOX_MAX_ITEMS)
    }

    /**
     * 主动清洗已落盘的脚本检测卡（打开消息中心时调用）：超过 30 分钟的 SCRIPT 卡
     * 从内存与磁盘一并移除；其余分类不动。清洗规则本体在 [sanitizeInbox]，本函数只负责
     * 对当前列表执行并在有变化时回写。
     */
    fun pruneExpiredScriptNotices() {
        synchronized(lock) {
            loadInboxFromDiskLocked()
            val updated = sanitizeInbox(_inboxItems.value)
            if (updated != _inboxItems.value) {
                _inboxItems.value = updated
                refreshUnreadLocked()
                persistInboxLocked(updated)
            }
        }
    }

    /** 磁盘 → 内存合并加载（仅首次且已有 context 时执行）：attach 前写入的内存条目不丢。 */
    private fun loadInboxFromDiskLocked() {
        if (inboxLoaded) return
        val ctx = appContext ?: return
        inboxLoaded = true
        val disk = readInboxFromPrefs(ctx)
        if (disk.isEmpty() && _inboxItems.value.isEmpty()) return
        val merged = sanitizeInbox(disk + _inboxItems.value)
        _inboxItems.value = merged
        refreshUnreadLocked()
        if (merged != disk) persistInboxLocked(merged)
    }

    private fun readInboxFromPrefs(ctx: Context): List<InboxMessage> {
        return try {
            val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, null)
                ?: return emptyList()
            val array = JSONArray(raw)
            val out = ArrayList<InboxMessage>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val category = try {
                    Category.valueOf(obj.optString("category", Category.SYSTEM.name))
                } catch (_: Exception) {
                    Category.SYSTEM
                }
                out += InboxMessage(
                    id = obj.optLong("id", 0L),
                    category = category,
                    title = obj.optString("title", ""),
                    body = if (obj.has("body") && !obj.isNull("body")) {
                        obj.optString("body", "")
                    } else {
                        null
                    },
                    timeMillis = obj.optLong("timeMillis", 0L),
                    read = obj.optBoolean("read", false),
                )
            }
            sanitizeInbox(out.filter { it.title.isNotBlank() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistInboxLocked(items: List<InboxMessage>) {
        val ctx = appContext ?: return
        try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("category", item.category.name)
                obj.put("title", item.title)
                if (item.body != null) obj.put("body", item.body)
                obj.put("timeMillis", item.timeMillis)
                obj.put("read", item.read)
                array.put(obj)
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, array.toString())
                .apply()
        } catch (_: Exception) {
            // 提示系统必须自稳：持久化失败不反噬调用方，内存状态仍有效。
        }
    }

    /** 系统 Toast 回退：切主线程展示，时长按胶囊规则近似（>2s 用 LENGTH_LONG）。 */
    private fun showSystemToast(ctx: Context, message: String) {
        val length = if (message.length > NoticePolicy.TOAST_LONG_TEXT_THRESHOLD) {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, message, length).show()
            } catch (_: Exception) {
                // 后台无 Looper 等场景 makeText 可能失败；此时消息已尽力送达，不再上抛。
            }
        }
    }
}
