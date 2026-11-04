package xyz.cncnlp.soundwave.recommend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.HomePageStateStore
import xyz.cncnlp.soundwave.fetchPlaylistCategoryGroups
import xyz.cncnlp.soundwave.source.MusicSourceRegistry
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * r655 风格推荐分类目录：直接复用各平台真实歌单分类接口（网易歌单分类 catalogue、QQ、酷我、酷狗、咪咕），
 * 与首页歌单广场共用 [HomePageStateStore] 的分类缓存（6 小时 TTL），并在进程内再缓存一份，弹窗秒开。
 */
internal object StyleTagCatalog {
    data class Group(val name: String, val tags: List<String>)

    private val memory = ConcurrentHashMap<String, List<Group>>()
    private val placeholder = setOf("全部", "全部分类", "全部歌单", "热门", "推荐", "精选", "最新", "更多")

    fun supported(platform: String): Boolean = platform.trim().lowercase(Locale.ROOT) in MusicSourceRegistry.platformIds

    /** 只读缓存（内存 → 本地分类缓存），不触网。 */
    fun cached(context: Context, platform: String): List<Group>? {
        val key = platform.trim().lowercase(Locale.ROOT)
        memory[key]?.let { return it }
        val snapshot = HomePageStateStore.loadCategoryGroups(context, key) ?: return null
        val groups = sanitize(snapshot.groups?.groups.orEmpty())
        if (groups.isNotEmpty()) memory[key] = groups
        return groups.takeIf { it.isNotEmpty() }
    }

    /** 缓存新鲜则直接返回，否则请求平台分类接口并写回共享缓存；失败时回落旧缓存。 */
    suspend fun load(context: Context, platform: String, force: Boolean = false): List<Group> = withContext(Dispatchers.IO) {
        val key = platform.trim().lowercase(Locale.ROOT)
        if (!supported(key)) return@withContext emptyList()
        val snapshot = HomePageStateStore.loadCategoryGroups(context, key)
        val fresh = snapshot != null && snapshot.groups?.groups.orEmpty().isNotEmpty() &&
            System.currentTimeMillis() - snapshot.savedAt in 0..HomePageStateStore.categoryGroupCacheTtl(snapshot.groups)
        if (!force && fresh) {
            memory[key]?.let { return@withContext it }
            return@withContext sanitize(snapshot!!.groups?.groups.orEmpty()).also { if (it.isNotEmpty()) memory[key] = it }
        }
        val fetched = recommendationAttempt { fetchPlaylistCategoryGroups(key) }
        if (fetched != null && fetched.groups.isNotEmpty()) {
            HomePageStateStore.saveCategoryGroups(context, key, fetched)
            sanitize(fetched.groups).also { if (it.isNotEmpty()) memory[key] = it }
        } else {
            memory[key] ?: sanitize(snapshot?.groups?.groups.orEmpty())
        }
    }

    /** 去掉「全部 / 热门」等非风格占位项，跨组去重，丢弃空组。 */
    internal fun sanitize(raw: List<Pair<String, List<String>>>): List<Group> {
        val seen = HashSet<String>()
        return raw.mapNotNull { (name, items) ->
            val tags = items.map { it.trim() }.filter { it.isNotEmpty() && it !in placeholder && seen.add(it) }
            val title = name.trim()
            if (title.isEmpty() || tags.isEmpty()) null else Group(title, tags)
        }
    }
}
