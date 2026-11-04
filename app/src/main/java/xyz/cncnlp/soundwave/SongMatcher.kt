package xyz.cncnlp.soundwave

/**
 * 跨平台候选歌曲的严格身份匹配器。
 *
 * 取链速度不能替代歌曲身份匹配：标题、歌手、版本标记和时长必须先通过，
 * 再把得分交给 Resolver 选择。返回 null 表示候选不能用于自动播放。
 */
object SongMatcher {
    private val versionPattern = Regex(
        "live|现场|remix|mix|dj|伴奏|instrumental|cover|翻唱|demo|片段|试听|karaoke|纯音乐",
        RegexOption.IGNORE_CASE
    )

    /**
     * 跨平台自动换源只允许歌曲身份的规范化精确匹配：不能用“标题前缀”或“歌手包含”猜歌。
     * 括号中的版本词另外通过 versionTokens 核验；feat/多歌手以集合而非字符串顺序比较。
     */
    fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\(.*?\\)|（.*?）|\\[.*?\\]|【.*?】"), " ")
        .replace(Regex("[\\s\\-_/·.]+"), "")
        .trim()

    /**
     * 与下方 singers() 同一分隔符集的切分（feat/ft. 视为分隔符），供导航层复用，不另写第二套口径。
     * 唯一差异：feat/ft. 只在词边界处认——singers() 是双边对称比较（两边同等处理即可），
     * 本函数的产物是单边搜索关键词，词内误切（如「Daft Punk」的 ft）会直接搜空。
     */
    internal fun splitSingers(value: String): List<String> = value
        .replace(Regex("\\b(?:feat(?:uring)?|ft)\\b\\.?", RegexOption.IGNORE_CASE), "/")
        .split(Regex("[、,，/&;；|＋+]"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    /**
     * 主歌手的「可检索名」：切分取第一位（feat./ft. 之后的合作者不算主歌手），
     * 再剥离尾部括号别名（「周杰伦 (Jay Chou)」「A（B）」）。括号只剥尾部成对一层，
     * 名字本体含括号的极少数情况由 normalize 等值比较兜底。「查看歌手」按名搜索专用。
     */
    fun primarySingerName(value: String): String {
        val first = splitSingers(value).firstOrNull().orEmpty()
        val stripped = first
            .replace(Regex("\\s*[\\(（][^\\)）]*[\\)）]\\s*$"), "")
            .trim()
        return stripped.ifBlank { first }
    }

    private fun singers(value: String): Set<String> = value.lowercase()
        .replace(Regex("(?:feat(?:uring)?|ft)\\.?", RegexOption.IGNORE_CASE), "/")
        .split(Regex("[、,，/&;；|＋+]"))
        .map(::normalize)
        .filter { it.isNotBlank() }
        .toSet()

    fun versionTokens(value: String): Set<String> = versionPattern
        .findAll(value)
        .map { it.value.lowercase() }
        .toSet()

    /**
     * 返回候选被自动跨源拒绝的具体门禁，null 表示全部通过。
     * 原因使用脱敏短码（title/artist/version/duration），供 Resolver 写入诊断；
     * score() 与之共用同一判定，保证“可解释”和“可播放”永不脱节。
     */
    fun rejectReason(source: Song, candidate: Song): String? {
        val wantedTitle = normalize(source.name)
        val candidateTitle = normalize(candidate.name)
        if (wantedTitle.isBlank() || candidateTitle.isBlank() || wantedTitle != candidateTitle) return "title"

        val wantedSingers = singers(source.singer)
        val candidateSingers = singers(candidate.singer)
        // 原曲给出了歌手，就必须两边都完整一致；缺失歌手不能成为自动跨源候选。
        if (wantedSingers.isEmpty() || candidateSingers.isEmpty() || wantedSingers != candidateSingers) return "artist"

        val wantedVersion = versionTokens(source.name + " " + source.album)
        val candidateVersion = versionTokens(candidate.name + " " + candidate.album)
        if (wantedVersion != candidateVersion) return "version"

        if (source.duration <= 0L || candidate.duration <= 0L) return "duration_missing"
        if (kotlin.math.abs(source.duration - candidate.duration) > 7_000L) return "duration"

        return null
    }

    /** null=严格拒绝；仅精确标题 + 精确歌手集合 + 相同版本 + 合理时长的候选可自动播放。 */
    fun score(source: Song, candidate: Song): Int? {
        if (rejectReason(source, candidate) != null) return null

        // 排序仅在所有身份门禁都通过后，偏好具备时长佐证的候选，不用 URL 返回速度决定歌曲身份。
        return if (source.duration > 0L && candidate.duration > 0L) 100 else 90
    }

    fun rank(source: Song, candidates: List<Song>, limit: Int = 2): List<Song> = candidates
        .mapNotNull { candidate -> score(source, candidate)?.let { it to candidate } }
        .sortedByDescending { it.first }
        .take(limit.coerceAtLeast(0))
        .map { it.second }
}
