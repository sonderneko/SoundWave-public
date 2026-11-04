package xyz.cncnlp.soundwave

import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Public repository metadata only. Never downloads or installs assets. */
internal object GitHubReleases {
    const val PROJECT = "https://github.com/projects258036-svg/soundwave-v2"
    const val RELEASES = "$PROJECT/releases"
    data class Release(val tag: String, val url: String, val notes: String, val prerelease: Boolean)
    @Volatile var checkedRelease: Release? = null
        private set
    internal data class CheckState(val message: String = "", val checking: Boolean = false, val release: Release? = null)
    private val mutableCheckState = MutableStateFlow(CheckState())
    internal val checkState = mutableCheckState.asStateFlow()

    fun parse(raw: String, includePrerelease: Boolean): Release? {
        val rows = JSONArray(raw)
        return (0 until rows.length()).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val tag = o.optString("tag_name"); val url = o.optString("html_url")
            if (o.optBoolean("draft") || (!includePrerelease && o.optBoolean("prerelease")) || !url.startsWith("$RELEASES/tag/") || numbers(tag).isEmpty()) null
            else Release(tag, url, o.optString("body"), o.optBoolean("prerelease"))
        }.maxWithOrNull { a,b -> compare(a.tag,b.tag) }
    }
    private fun numbers(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).mapNotNull { it.value.toIntOrNull() }.toList()
    fun compare(a: String, b: String): Int {
        val x=numbers(a); val y=numbers(b)
        for (i in 0 until maxOf(x.size,y.size)) {
            val c=(x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0); if(c!=0) return c
        }
        val ap=a.contains('-'); val bp=b.contains('-')
        return if(ap!=bp) if(ap) -1 else 1 else a.compareTo(b).takeIf { ap } ?: 0
    }
    @Synchronized fun check(local: String): String {
        mutableCheckState.value = CheckState("正在检查 GitHub Releases…", checking = true)
        return try {
            checkResult(local).also { mutableCheckState.value = CheckState(it, release = checkedRelease) }
        } finally {
            // Even an unexpected failure must not leave the notification action disabled.
            if (mutableCheckState.value.checking) mutableCheckState.value = CheckState("检查失败：无法读取 GitHub Releases，当前 $local")
        }
    }

    private fun checkResult(local: String): String {
        checkedRelease=null
        return try {
            val request=okhttp3.Request.Builder().url("https://api.github.com/repos/projects258036-svg/soundwave-v2/releases?per_page=100")
                .header("Accept","application/vnd.github+json").header("User-Agent","SoundWave/$local").build()
            AppHttp.shared.newBuilder().callTimeout(15,TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if(!response.isSuccessful) return "检查失败：GitHub HTTP ${response.code}，当前 $local"
                val latest=parse(response.body?.string().orEmpty(), local.contains('-'))
                checkedRelease=latest
                when {
                    latest==null -> "尚无可用 GitHub 正式发布，当前 $local"
                    compare(latest.tag,local)>0 -> "发现新版本 ${latest.tag} · ${if(latest.prerelease) "预发布" else "正式版"}"
                    else -> "已是最新或本地构建更新 · 当前 $local · GitHub ${latest.tag}"
                }
            }
        } catch (e: Exception) { "检查失败：无法读取 GitHub Releases，当前 $local" }
    }
    fun downloadPage() = checkedRelease?.url ?: RELEASES
}
