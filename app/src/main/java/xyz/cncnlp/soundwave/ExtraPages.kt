package xyz.cncnlp.soundwave
import xyz.cncnlp.soundwave.ui.common.staggerEnter
import xyz.cncnlp.soundwave.ui.common.dockContentBottomPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box

import android.content.Intent
import android.net.Uri
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.ui.settings.WebDavSyncOverlay
import xyz.cncnlp.soundwave.ui.settings.settingsPressFeedback
import xyz.cncnlp.soundwave.ui.settings.SettingsConfirmSheet
import xyz.cncnlp.soundwave.ui.settings.SettingsSheetButton
import xyz.cncnlp.soundwave.ui.settings.SettingsActionButton
import xyz.cncnlp.soundwave.ui.settings.SettingsCard
import xyz.cncnlp.soundwave.ui.settings.SettingsChevron
import xyz.cncnlp.soundwave.ui.settings.SettingsDetailHero
import xyz.cncnlp.soundwave.ui.settings.SettingsDetailRow
import xyz.cncnlp.soundwave.ui.settings.SettingsSwitch
import xyz.cncnlp.soundwave.ui.settings.SettingsEmptyNote
import xyz.cncnlp.soundwave.ui.settings.SettingsHairline
import xyz.cncnlp.soundwave.ui.settings.SettingsPageGlyphs
import xyz.cncnlp.soundwave.ui.settings.SettingsPageSection
import androidx.compose.ui.layout.onSizeChanged
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Primitive-only bridge from AppRoot through the settings route to the public backup page. */
internal data class WebDavAutoSyncUi(
    val active: Boolean = false,
    val enabled: Boolean = false,
    val ready: Boolean = false,
    val uncertain: Boolean = false,
    val canEnable: Boolean = false,
    val onEnabled: (Boolean) -> Unit = {},
    val onManualStarted: () -> Boolean = { true },
    val onManualSucceeded: (String) -> Unit = {},
    val onBindingEdited: () -> Unit = {},
    val withAccountLock: suspend (suspend () -> Unit) -> Unit = { it() }
)

internal val LocalWebDavAutoSyncUi = androidx.compose.runtime.staticCompositionLocalOf { WebDavAutoSyncUi() }

internal data class HistoryPageThemeColors(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val error: Color,
    val onError: Color
)

internal fun historyPageThemeColors(colorScheme: ColorScheme) = HistoryPageThemeColors(
    onSurface = colorScheme.onSurface,
    onSurfaceVariant = colorScheme.onSurfaceVariant,
    primary = colorScheme.primary,
    error = colorScheme.error,
    onError = colorScheme.onError
)

/** ACCEPT5-PERF：ListenStatsPage 的整页统计快照（IO 线程一次采集，组合只读）。
 *  ACCEPT5-STATSMONTH：快照内容改为所选自然月的聚合（见 ListenStats.MonthStats）。 */
private data class ListenStatsPageSnapshot(
    val monthTotalSec: Long,
    val monthTotalPlays: Long,
    val topSongs: List<ListenStats.SongStat>,
    val topArtists: List<ListenStats.ArtistStat>,
    val activeDays: Int,
    val topPlatforms: List<Pair<String, Long>>,
    val monthDailySeconds: List<Long>,
    /** ACCEPT6-PODIUMAVATAR：Top3 歌手的本地 singerId 证据（键 = ArtistAvatarRepository.key）。 */
    val avatarEvidence: Map<String, Map<String, Set<String>>> = emptyMap()
)

@Composable
fun ListenStatsPage(onOpenHistory: () -> Unit = {}) {
    val ctx = LocalContext.current
    val onBg = MaterialTheme.colorScheme.onBackground
    val primary = MaterialTheme.colorScheme.primary
    val muted = onBg.copy(alpha = 0.55f)
    val card = Color.Transparent
    // ACCEPT5-PERF：全页统计（2× 全量 song_map 解析、周/月汇总等）原先在组合体同步执行，
    // 每次重组都冻结页面；现整页只采集一次 IO 快照，结构先行、数值异步填入（进页快照口径）。
    // ACCEPT5-STATSMONTH：月份翻页（用户定版）——左右箭头，可达窗口含本月共 6 个月（本月＋前 5 月），
    // 页内状态不落盘；翻月按所选自然月在 IO 快照里重聚合，加载中回空值，绝不串月显示。
    val currentMonth = remember { ListeningCalendar.monthAt(System.currentTimeMillis()) }
    var monthOffset by remember { mutableIntStateOf(0) }
    val selectedMonth = currentMonth.plusMonths(monthOffset.toLong())
    val isCurrentMonth = monthOffset == 0
    val monthAnchorMs = remember(selectedMonth) {
        selectedMonth.atDay(1).atStartOfDay(ListeningCalendar.zoneId).toInstant().toEpochMilli()
    }
    val statsSnapshot by androidx.compose.runtime.produceState<ListenStatsPageSnapshot?>(
        initialValue = null, ctx, selectedMonth
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                ListenStats.init(ctx)
                val data = xyz.cncnlp.soundwave.ContextData(ctx, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
                val ms = data.monthStats(selectedMonth, System.currentTimeMillis())
                // ACCEPT6-STATSMEDIA：统计封面只来自记录当时 Song 自带的 cover，历次播放都没带封面
                // 的条目会永久空封面；快照组装时（仍在本 IO 块内）按稳定键从本地库回填一次：
                // 历史 → 下载 → 收藏 → 歌单歌曲。仅展示层补图，不写回统计存储、不改口径。
                // ACCEPT6-PODIUMAVATAR：同一候选池顺带为 Top3 歌手提取 singerId 证据（统计只有
                // 名字，同名歌手消歧靠用户自己曲库里的 singerId）；故组装条件扩为「有空封面
                // 歌曲或有 Top3 歌手」，仍只在本 IO 块内一次性完成。
                var avatarEvidence: Map<String, Map<String, Set<String>>> = emptyMap()
                val topSongs = if (ms.topSongs.any { it.cover.isBlank() } || ms.topArtists.isNotEmpty()) {
                    runCatching {
                        val bundle = data.load()
                        val candidates = data.history() +
                            data.downloads().map { it.song } +
                            bundle.favs +
                            bundle.playlists.flatMap { it.songs }
                        avatarEvidence = ms.topArtists.take(3).associate { artist ->
                            ArtistAvatarRepository.key(artist.source, artist.singer) to
                                ArtistAvatarRepository.evidenceIdsBySource(candidates, artist.singer)
                        }
                        StatsMediaEnrich.backfillSongCovers(ms.topSongs, candidates)
                    }.getOrDefault(ms.topSongs)
                } else ms.topSongs
                ListenStatsPageSnapshot(
                    monthTotalSec = ms.totalSec,
                    monthTotalPlays = ms.totalPlays,
                    topSongs = topSongs,
                    topArtists = ms.topArtists,
                    activeDays = ms.activeDays,
                    topPlatforms = ms.topPlatforms,
                    monthDailySeconds = ms.dailySeconds,
                    avatarEvidence = avatarEvidence
                )
            }.getOrNull()
        }
    }
    val monthTotalSec = statsSnapshot?.monthTotalSec ?: 0L
    val monthTotalPlays = statsSnapshot?.monthTotalPlays ?: 0L
    val songs = statsSnapshot?.topSongs.orEmpty()
    val artists = statsSnapshot?.topArtists.orEmpty()
    val artistAvatars = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    androidx.compose.runtime.LaunchedEffect(artists.take(3).map { ArtistAvatarRepository.key(it.source, it.singer) }) {
        // UI and ranking render immediately; no network or bitmap decoding in composition.
        // ACCEPT6-STATSMEDIA：先查首页「热门歌手」管线的进程缓存（fetchHotArtists +
        // HomeSectionMemory，同源同形状，零新增网络），同名命中即用；未命中再走仓库 typed 搜索。
        // ACCEPT6-PODIUMAVATAR：主导平台再未命中时，按用户跨源平台顺序（SmartSourceStore
        // 既有口径）逐台按名按需解析 + 快照里的 singerId 证据消歧（见仓库 loadOnDemand）。
        // 统计只有「名字 + 主导平台」，主导平台落在歌手实体薄弱的平台时旧链直接终局占位；
        // 同名多条目（如 wy「昼夜」真身与同名小号并存）也会被 match 的恰一门判死。
        val evidenceByArtist = statsSnapshot?.avatarEvidence.orEmpty()
        kotlinx.coroutines.coroutineScope {
            artists.take(3).forEach { artist ->
                launch {
                    val key = ArtistAvatarRepository.key(artist.source, artist.singer)
                    val chartAvatar = StatsMediaEnrich.chartAvatarFor(
                        xyz.cncnlp.soundwave.ui.home.HomeSectionMemory.hotArtists(artist.source), artist.singer
                    )
                    artistAvatars[key] = chartAvatar.ifEmpty {
                        ArtistAvatarRepository.load(ctx, artist.source, artist.singer)?.url.orEmpty()
                    }.ifEmpty {
                        val order = runCatching { SmartSourceStore.enabledPlatformsInOrder() }
                            .getOrDefault(SmartSourceStore.defaultPlatformOrder())
                        ArtistAvatarRepository.loadOnDemand(
                            ctx, artist.source, artist.singer,
                            evidence = evidenceByArtist[key].orEmpty(),
                            platformOrder = order
                        )?.url.orEmpty()
                    }
                }
            }
        }
    }
    val activeDays = statsSnapshot?.activeDays ?: 0
    val platforms = statsSnapshot?.topPlatforms.orEmpty()
    val month = selectedMonth
    val monthEmpty = statsSnapshot != null && monthTotalSec <= 0L && monthTotalPlays <= 0L
    val emptyNote = if (!isCurrentMonth && monthEmpty) "该月暂无听歌记录" else "暂无数据，听几首就会出现"
    val monthDailySeconds = statsSnapshot?.monthDailySeconds.orEmpty()
    val monthPeakSeconds = monthDailySeconds.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val heatmapCells: List<Pair<Int, Long>?> = buildList {
        repeat(ListeningCalendar.monthStartOffset(monthAnchorMs)) { add(null) }
        monthDailySeconds.forEachIndexed { index, seconds -> add((index + 1) to seconds) }
        while (size % 7 != 0) add(null)
    }
    // r655：听歌档案按设计稿 #archive 重做：完整历史入口卡 → 自然月热力图卡 → 最常听歌手 TOP 3 领奖台（名次圆点带光晕）→ 最常听歌曲。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = SoundwaveDesignPalette.Accent
    val line = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    val surface = MaterialTheme.colorScheme.surface
    val sub = if (dark) Color(0xFFA6ADA7) else Color(0xFF5F645F)
    val cardShape = RoundedCornerShape(22.dp)
    val cardMod = Modifier.fillMaxWidth().clip(cardShape).background(surface).border(1.dp, line, cardShape)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(top = 10.dp, bottom = dockContentBottomPadding()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(
                // r657（v3 #13）：深色珊瑚 / 蓝光晕渐变 + 玻璃高光 + 白色图标。
                Modifier.fillMaxWidth().clip(cardShape)
                    .then(xyz.cncnlp.soundwave.ui.common.glowCardBackground())
                    .border(1.dp, Color.White.copy(alpha = 0.12f), cardShape)
                    .clickable { onOpenHistory() }
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.History, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("查看完整播放历史", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("按日期和时间沿竖向时间轴查看每一次播放", color = Color.White.copy(alpha = 0.66f), fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Column(cardMod.padding(16.dp)) {
                Text("听歌统计热力图", color = onBg, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text("按自然月显示 · 颜色越深，收听时间越长", color = sub, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                val monthNames = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatsMonthArrowButton(next = false, enabled = monthOffset > -5) { monthOffset -= 1 }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(monthNames[month.monthValue - 1], color = onBg, fontSize = 26.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                        Text("${month.year} 年 ${month.monthValue} 月 · ${month.lengthOfMonth()} 天 · 累计 ${ListenStats.fmtDuration(monthTotalSec)}", color = sub, fontSize = 8.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, modifier = Modifier.padding(top = 2.dp))
                    }
                    StatsMonthArrowButton(next = true, enabled = monthOffset < 0) { monthOffset += 1 }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                        Box(Modifier.weight(1f).padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                            Text(weekday, color = sub, fontSize = 8.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono)
                        }
                    }
                }
                val empty = if (dark) Color.White.copy(alpha = 0.06f) else Color(0xFFECEDE8)
                val low = if (dark) accent.copy(alpha = 0.32f) else Color(0xFFFDD5CD)
                val high = Color(0xFFF98B78)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    heatmapCells.chunked(7).forEach { week ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            week.forEach { cell ->
                                if (cell == null) {
                                    Spacer(Modifier.weight(1f).aspectRatio(1f))
                                } else {
                                    val (day, seconds) = cell
                                    val ratio = (seconds.toFloat() / monthPeakSeconds.toFloat()).coerceIn(0f, 1f)
                                    val bg = when { seconds <= 0L -> empty; ratio < 0.5f -> low; else -> high }
                                    val cellShape = RoundedCornerShape(7.dp)
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f).clip(cellShape).background(bg)
                                            .border(1.dp, if (seconds <= 0L) line else bg.copy(alpha = 0.9f), cellShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("$day", color = if (bg == high) Color.White else onBg.copy(alpha = 0.78f), fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("较少", color = sub, fontSize = 8.sp)
                    listOf(empty, low, high).forEach { c -> Box(Modifier.padding(start = 5.dp).size(11.dp).clip(RoundedCornerShape(3.dp)).background(c)) }
                    Text("较多", color = sub, fontSize = 8.sp, modifier = Modifier.padding(start = 5.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (isCurrentMonth) "本月活跃 $activeDays 天" else "当月活跃 $activeDays 天", color = sub, fontSize = 8.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono)
                }
            }
        }
        item {
            Column(cardMod.padding(16.dp)) {
                Text("最常听歌手 TOP 3", color = onBg, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text("当月播放时长 · ${month.year} 年 ${month.monthValue} 月 · 累计 ${ListenStats.fmtDuration(monthTotalSec)} · 播放 ${monthTotalPlays} 次", color = sub, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                if (artists.isEmpty()) Text(emptyNote, color = sub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 22.dp))
                else {
                    val podium = listOf(1, 0, 2).filter { it < artists.size }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        podium.forEach { idx ->
                            val a = artists[idx]
                            val cover = artistAvatars[ArtistAvatarRepository.key(a.source, a.singer)].orEmpty()
                            ArchivePodiumColumn(rank = idx + 1, name = a.singer.ifEmpty { "未知歌手" }, hours = "${a.cnt} 次 · ${ListenStats.fmtDuration(a.sec)}", cover = cover, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            Column(cardMod.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text("最常听歌曲", color = onBg, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text(if (platforms.isNotEmpty()) "当月播放次数 · 常听平台 " + platforms.joinToString(" · ") { (code, _) -> platformName(code) } else "当月播放次数", color = sub, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (songs.isEmpty()) Text(emptyNote, color = sub, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp))
                songs.forEachIndexed { idx, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${idx + 1}", color = onBg, fontSize = 20.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是「♪」文本、加载时只剩 accent 8% 底）。
                            // ACCEPT6-STATSMEDIA：与其他表面同口径走 CacheManager.coverModel 管线
                            // （http→https 归一 + 校验缓存 + 尺寸变体）；此前直喂原始 URL，
                            // 需要归一的封面（http CDN 等）会被明文策略拦死成占位。
                            DefaultCoverPlaceholder(Modifier.fillMaxSize())
                            if (s.cover.isNotEmpty()) coil.compose.AsyncImage(CacheManager.coverModel(ctx, s.cover, 144) ?: s.cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name.ifEmpty { "未知歌曲" }, color = onBg, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.singer.ifEmpty { "未知歌手" }, color = sub, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                        Text("${s.cnt} 次", color = sub, fontSize = 8.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono)
                    }
                    if (idx < songs.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(line)))
                }
            }
        }
    }
}

/** ACCEPT5-STATSMONTH：听歌统计月份翻页箭头（36dp 圆形描边按钮，禁用时置灰且不可点）。 */
@Composable
private fun StatsMonthArrowButton(next: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val onBg = MaterialTheme.colorScheme.onBackground
    val line = if (dark) SoundwaveDesignPalette.NightLine else SoundwaveDesignPalette.Line
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, line, CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (next) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = if (next) "下一月" else "上一月",
            tint = onBg,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 领奖台单列：圆角顶块（第一名青柠淡底更高）+ 右上描边大号名次 + 头像 + 名次圆点（带同色光晕）+ 歌手名/时长。 */
@Composable
private fun ArchivePodiumColumn(rank: Int, name: String, hours: String, cover: String, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val onBg = MaterialTheme.colorScheme.onBackground
    val sub = if (dark) Color(0xFFA6ADA7) else Color(0xFF5F645F)
    val badge = when (rank) { 1 -> SoundwaveDesignPalette.Accent; 2 -> Color(0xFF97907F); else -> Color(0xFFA8643A) }
    val top = if (rank == 1) Color(0xFFCCF35B).copy(alpha = if (dark) 0.16f else 0.22f) else onBg.copy(alpha = 0.05f)
    val blockH = when (rank) { 1 -> 196.dp; 2 -> 150.dp; else -> 138.dp }
    val avatar = if (rank == 1) 74.dp else 56.dp
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
    Box(modifier.height(blockH).clip(shape).background(Brush.verticalGradient(listOf(top, Color.Transparent)))) {
        Text("$rank", color = onBg.copy(alpha = 0.10f), fontSize = 40.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp))
        Column(Modifier.fillMaxWidth().padding(top = if (rank == 1) 18.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.padding(bottom = 11.dp).size(avatar).clip(CircleShape)
                        .border(if (rank == 1) 3.dp else 2.dp, if (rank == 1) Color(0xFFCCF35B) else Color.White, CircleShape)
                        .background(onBg.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    ArchiveArtistAvatar(name, cover, Modifier.fillMaxSize().clip(CircleShape))
                }
                // r655：名次圆点加同色光晕（径向柔光 + 彩色投影）。
                Box(
                    Modifier.size(24.dp)
                        .drawBehind {
                            drawCircle(
                                Brush.radialGradient(listOf(badge.copy(alpha = 0.55f), badge.copy(alpha = 0.18f), Color.Transparent), center = center, radius = size.minDimension * 1.25f),
                                radius = size.minDimension * 1.25f, center = center
                            )
                        }
                        .shadow(10.dp, CircleShape, ambientColor = badge, spotColor = badge)
                        .clip(CircleShape).background(badge).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$rank", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Text(name, color = onBg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
            Text(hours, color = sub, fontSize = 8.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

private data class PendingBackupImport(
    val root: JSONObject,
    val preview: BackupStore.Preview
)

@Composable
fun BackupRestorePage(
    onPrepareWebDavAvatarSync: () -> xyz.cncnlp.soundwave.sync.WebDavAvatarSync? = { null },
    onPrepareWebDavProfileExtrasSync: () -> xyz.cncnlp.soundwave.sync.WebDavProfileExtrasSync? = { null },
    onPrepareWebDavAccountDataSync: () -> xyz.cncnlp.soundwave.sync.WebDavAccountDataSync? = { null },
    onImportWebDavProfileName: suspend (String) -> Boolean = { false },
    webDavAutoEnabled: Boolean = false,
    webDavAutoReady: Boolean = false,
    webDavAutoUncertain: Boolean = false,
    onWebDavAutoEnabled: (Boolean) -> Unit = {},
    onWebDavManualStarted: () -> Boolean = { true },
    onWebDavManualSucceeded: (String) -> Unit = {},
    onWebDavBindingEdited: () -> Unit = {},
    withWebDavAccountLock: suspend (suspend () -> Unit) -> Unit = { it() },
    webDavBundle: LocalStore.UserBundle,
    onWebDavBundleApplied: (LocalStore.UserBundle) -> Unit,
    onRestored: (LocalStore.UserBundle) -> Unit = {}
) {
    val webDavUi = LocalWebDavAutoSyncUi.current
    val autoEnabled = if (webDavUi.active) webDavUi.enabled else webDavAutoEnabled
    val autoReady = if (webDavUi.active) webDavUi.ready else webDavAutoReady
    val autoUncertain = if (webDavUi.active) webDavUi.uncertain else webDavAutoUncertain
    val autoCanEnable = if (webDavUi.active) webDavUi.canEnable else autoReady && !autoUncertain
    val setAutoEnabled = if (webDavUi.active) webDavUi.onEnabled else onWebDavAutoEnabled
    val manualStarted = if (webDavUi.active) webDavUi.onManualStarted else onWebDavManualStarted
    val manualSucceeded = if (webDavUi.active) webDavUi.onManualSucceeded else onWebDavManualSucceeded
    val bindingEdited = if (webDavUi.active) webDavUi.onBindingEdited else onWebDavBindingEdited
    val accountLock = if (webDavUi.active) webDavUi.withAccountLock else withWebDavAccountLock
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    var showWebDavSheet by remember { mutableStateOf(false) }
    var pendingBackupImport by remember { mutableStateOf<PendingBackupImport?>(null) }
    var recoveryStatus by remember { mutableStateOf(BackupRestoreRecovery.Status(false, "")) }
    var recoveryReview by remember { mutableStateOf<BackupRestoreRecovery.Review?>(null) }
    var recoveryBusy by remember { mutableStateOf(false) }

    var localBackupProfiles by remember { mutableStateOf<Map<ProfileId, LocalStore.LocalProfileBackupImportMetadata>>(emptyMap()) }
    var unactivatedProfilePreviewId by remember { mutableStateOf<ProfileId?>(null) }
    LaunchedEffect(ctx) {
        recoveryStatus = withContext(Dispatchers.IO) { BackupRestoreRecovery.status(ctx) }
        localBackupProfiles = withContext(Dispatchers.IO) { LocalStore.importedLocalBackupProfiles(ctx) }
    }
    // r657：选择器与外部 ACTION_VIEW 打开的备份 JSON 共用同一预览路径（只预览，确认后才恢复）。
    val previewBackup: (Uri?) -> Unit = previewBackup@{ uri ->
        if (uri == null) return@previewBackup
        scope.launch {
            try {
                val pending = withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { BackupInputReader.read(it) }
                        ?: error("无法读取备份文件")
                    val root = JSONObject(text)
                    PendingBackupImport(root, BackupStore.preview(root))
                }
                pendingBackupImport = pending
            } catch (e: Exception) {
                AppNotice.toast("备份预览失败，请重试", AppNotice.Kind.FAILURE)
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), previewBackup)
    val externalBackup by ExternalFileImport.pendingBackup.collectAsState()
    LaunchedEffect(externalBackup) { ExternalFileImport.takePendingBackup()?.let(previewBackup) }
    fun export() {
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val json = BackupStore.export(ctx).toString()
                    val dir = File(ctx.getExternalFilesDir(null), "backup").also { it.mkdirs() }
                    val name = "soundwave-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
                    val f = File(dir, name)
                    f.writeText(json)
                    f
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, "导出备份"))
            } catch (e: Exception) {
                AppNotice.inbox(AppNotice.Category.SYNC, "导出备份失败", alsoToast = true)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
    // r656 item 13：按设计稿 settings-detail 重做——衬线 hero → 本机备份 / WebDAV 同步两组透明行。
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SettingsMetrics.SubPadH.dp), contentPadding = PaddingValues(top = 0.dp, bottom = dockContentBottomPadding())) {
        item {
            SettingsDetailHero("同步与数据", "备份与恢复", "导出或恢复设置、歌单与播放数据；多设备可走自有 WebDAV 同步。下载的音频文件不会被包含。")
        }
        item { SettingsPageSection("本机备份") }
        if (recoveryStatus.pending) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("有未完成的恢复", style = MaterialTheme.typography.titleSmall)
                Text(recoveryStatus.message, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !recoveryBusy, onClick = {
                    scope.launch {
                        try { recoveryReview = withContext(Dispatchers.IO) { BackupRestoreRecovery.review(ctx) } }
                        catch (e: Exception) { AppNotice.toast("恢复记录无法继续；已保留资料", AppNotice.Kind.FAILURE) }
                    }
                }) { Text("检查并继续恢复") }
            }
        }

        item {
            Column(Modifier.fillMaxWidth()) {
                SettingsHairline()
                BackupActionRow(
                    title = "导出备份",
                    subtitle = "生成 JSON 备份文件并通过系统分享保存",
                    icon = SettingsPageGlyphs.Export,
                    iconTint = xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Data,
                    onClick = { export() }
                )
                BackupActionRow(
                    title = "导入备份",
                    subtitle = "先预览备份内容，确认后才替换本机数据",
                    icon = SettingsPageGlyphs.Import,
                    iconTint = xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Data,
                    onClick = { pick.launch("application/json") }
                )
                PendingInactiveRollbacks()
                LocalProfileBackupImportAction { profileId ->
                    unactivatedProfilePreviewId = profileId
                    scope.launch {
                        localBackupProfiles = withContext(Dispatchers.IO) {
                            LocalStore.importedLocalBackupProfiles(ctx)
                        }
                    }
                }
            }
        }
        item { SettingsPageSection("WebDAV 同步") }
        item {
            Column(Modifier.fillMaxWidth()) {
                SettingsHairline()
                BackupActionRow(
                    title = "WebDAV 登录同步",
                    subtitle = "本机资料独立 v2 文件；自动同步需单独开启，仅本次应用会话有效；重启后需重新开启",
                    icon = SettingsPageGlyphs.Server,
                    iconTint = xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Data,
                    onClick = { showWebDavSheet = true }
                )
                SettingsDetailRow(
                    title = "WebDAV 自动同步",
                    subtitle = when {
                        autoUncertain -> "远端结果待核对；请手动检查后再同步。自动同步授权仅本次应用会话有效，重启后需重新开启；仅前台恢复或本机变化触发，无定时后台任务。"
                        !autoReady -> "先完成一次手动登录并同步。自动同步授权仅本次应用会话有效，重启后需重新开启；仅前台恢复或本机变化触发，无定时后台任务。"
                        else -> "自动同步授权仅本次应用会话有效，重启后需重新开启；仅前台恢复或本机变化时触发，没有定时后台同步。"
                    },
                    icon = SettingsPageGlyphs.Server,
                    iconTint = xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Data,
                    enabled = autoEnabled || autoCanEnable,
                    trailing = {
                        SettingsSwitch(
                            checked = autoEnabled,
                            enabled = autoEnabled || autoCanEnable,
                            onClick = { setAutoEnabled(!autoEnabled) }
                        )
                    }
                )
            }
        }
        item {
            SettingsPageSection("本机未启用资料预览")
            Text("只显示由本机备份导入索引明确登记的资料；不会扫描或猜测旧本机资料 ID。", color = muted, fontSize = 11.sp, lineHeight = 16.sp)
            if (localBackupProfiles.isEmpty()) {
                SettingsEmptyNote("暂无可预览的本机未启用资料。导入本机备份后才会出现；不会默认读取旧本机资料。")
            }
        }
        items(localBackupProfiles.keys.toList(), key = { it.value }) { profileId ->
            val imported = localBackupProfiles[profileId] ?: return@items
            SettingsCard(Modifier.padding(top = 10.dp)) {
                Text("来源：SoundWave 本机资料备份 v${imported.version}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("状态：${imported.status}（尚未启用）", color = designAccentTextColor(), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                Text("本机导入时间：${imported.importedAt}", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                SettingsActionButton("预览本机未启用资料", Modifier.padding(top = 12.dp), icon = SettingsPageGlyphs.Eye) { unactivatedProfilePreviewId = profileId }
            }
        }
    }
    unactivatedProfilePreviewId?.let { profileId ->
        UnactivatedProfilePreviewDialog(
            profileId = profileId,
            onDismiss = { unactivatedProfilePreviewId = null }
        )
    }
    recoveryReview?.let { review ->
        SettingsConfirmSheet(
            onDismissRequest = { recoveryReview = null }, eyebrow = "Backup · Recovery",
            title = "再次按原备份恢复？",
            text = { Text("将重新写入：${BackupRestoreRecovery.SCOPES}。这不是撤销操作，可能覆盖中断后新修改；写入前会再次核对身份和资料摘要，变化时拒绝覆盖。原资料副本和输入备份会继续保留，WebDAV 凭据不会恢复或上传。") },
            confirmButton = {
                // 收尾棒 J8 句柄补齐：busy 即时置位、恢复协程即时启动（捕获 review 值，不依赖 recoveryReview 状态），
                // 仅 recoveryReview = null 延迟到退场后经 onDismissRequest 触发。
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { recoveryReview = null }
                SettingsSheetButton(modifier = Modifier.weight(1f), danger = true, enabled = !recoveryBusy, onClick = {
                    recoveryBusy = true
                    scope.launch {
                        try {
                            val bundle = withContext(Dispatchers.IO) { BackupStore.resume(ctx, review) }
                            onRestored(bundle)
                            AppNotice.inbox(AppNotice.Category.SYNC, "本机恢复已完成", "云同步门禁仍保留", alsoToast = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            AppNotice.inbox(AppNotice.Category.SYNC, "恢复未完成，记录与资料保留", alsoToast = true)
                        } finally {
                            recoveryBusy = false
                            recoveryStatus = withContext(NonCancellable + Dispatchers.IO) { BackupRestoreRecovery.status(ctx) }
                        }
                    }
                    dismiss()
                }) { Text("按原备份重新恢复") }
            },
            dismissButton = { val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { recoveryReview = null }; SettingsSheetButton(modifier = Modifier.weight(1f), onClick = dismiss) { Text("暂不恢复") } }
        )
    }
    pendingBackupImport?.let { pending ->
        val preview = pending.preview
        SettingsConfirmSheet(
            onDismissRequest = { pendingBackupImport = null },
            eyebrow = "Backup · Restore",
            title = "确认恢复并替换",
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("备份内容预览")
                    Text("收藏：${preview.favoriteCount} 条")
                    Text("歌单：${preview.playlistCount} 个")
                    Text("播放历史：${preview.historyCount} 条")
                    Text("下载记录：${preview.downloadCount} 条")
                    Text("头像数据：${if (preview.hasAvatar) "有" else "无"}")
                    Text("用户脚本数据：${if (preview.hasScripts) "有" else "无"}")
                    Text("确认后，现有收藏、歌单等用户数据将被备份内容替换；备份中包含的播放历史和下载记录也会替换本地列表。偏好设置和用户脚本配置会按备份内容更新。")
                    Text("备份不包含已下载音频文件；恢复下载记录不会复制音频文件。")
                    Text("仅恢复本机已有脚本的顺序与启用配置，不包含或恢复脚本代码；新设备需另行导入脚本。WebDAV 凭据与诊断上传授权保留本机值。")
                }
            },
            confirmButton = {
                // 收尾棒 J8 句柄补齐：恢复协程即时启动（捕获 pending 值），仅 pendingBackupImport = null
                // 延迟到退场后经 onDismissRequest 触发。
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { pendingBackupImport = null }
                SettingsSheetButton(modifier = Modifier.weight(1f), danger = true, onClick = {
                    scope.launch {
                        try {
                            val bundle = withContext(Dispatchers.IO) { BackupStore.restore(ctx, pending.root) }
                            onRestored(bundle)
                            AppNotice.inbox(AppNotice.Category.SYNC, "已恢复备份", alsoToast = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            AppNotice.inbox(AppNotice.Category.SYNC, "恢复未完成，记录与资料保留", alsoToast = true)
                        } finally {
                            recoveryStatus = withContext(NonCancellable + Dispatchers.IO) { BackupRestoreRecovery.status(ctx) }
                        }
                    }
                    dismiss()
                }) {
                    Text("恢复并替换")
                }
            },
            dismissButton = {
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { pendingBackupImport = null }
                SettingsSheetButton(modifier = Modifier.weight(1f), onClick = dismiss) { Text("取消") }
            }
        )
    }
    if (showWebDavSheet) {
        WebDavSyncOverlay(
            prepareAvatarSync = onPrepareWebDavAvatarSync,
            prepareProfileExtrasSync = onPrepareWebDavProfileExtrasSync,
            prepareAccountDataSync = onPrepareWebDavAccountDataSync,
            onManualStarted = manualStarted,
            onManualSucceeded = manualSucceeded,
            onBindingEdited = bindingEdited,
            withWebDavAccountLock = accountLock,
            localBundle = webDavBundle,
            onBundleApplied = onWebDavBundleApplied,
            onDismiss = { showWebDavSheet = false },
            onImportProfileName = onImportWebDavProfileName,
            autoSyncEnabled = webDavUi.enabled,
            onAutoSyncEnabledChange = webDavUi.onEnabled
        )
    }
    }
}

@Composable
internal fun BackupActionRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    showDivider: Boolean = true,
    enabled: Boolean = true,
    // ACCEPT6 iconcolor（用户 2026-11-04 23:25）：设置深层页行首图标按组统一色；Unspecified = 原墨色回退。
    iconTint: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    // r656：备份页操作行与设置详情行同语言——左侧线性图标、13sp 粗标题、9.5sp 说明、右 chevron、底部 1dp 线；整行可点（≥48dp）。
    val ink = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                // 验收二轮 E19：去掉全仓最后一处 Material ripple，与设置详情行同语言——
                // 通栏按压底色（settingsPressFeedback）+ indication = null。
                .settingsPressFeedback(interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    enabled = enabled,
                    onClick = onClick
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                Icon(icon, contentDescription = null, tint = if (iconTint == Color.Unspecified) ink else iconTint, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 17.sp)
                if (subtitle != null) {
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            SettingsChevron()
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun SwipeToDeleteHistoryRow(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    // r656 item 12：整张歌曲卡左滑删除——不再在右侧露出「删除」文字或图标；
    // 拖动时底层仅以中性底淡淡渐显（J 波：与播放队列统一去掉危险色揭开），
    // 越过阈值（或快速左甩）松手即滑出并删除，否则回弹。
    val themeColors = historyPageThemeColors(MaterialTheme.colorScheme)
    val scope = rememberCoroutineScope()
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var deleting by remember { mutableStateOf(false) }
    val dissolve = xyz.cncnlp.soundwave.ui.common.rememberParticleDissolveState()
    LaunchedEffect(enabled) {
        if (!enabled) offset.snapTo(0f)
    }
    val progress = HistoryTimelinePolicy.swipeProgress(offset.value, widthPx)
    Box(
        modifier.fillMaxWidth().height(70.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .then(xyz.cncnlp.soundwave.ui.common.particleDissolveModifier(dissolve, themeColors.onSurface))
            .clip(RoundedCornerShape(14.dp))
    ) {
        if (offset.value < 0f) {
            Box(Modifier.matchParentSize().background(themeColors.onSurface.copy(alpha = 0.04f + 0.06f * progress)))
        }
        Box(
            Modifier.fillMaxSize()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                // r657：静止时无卡片底（只剩封面 + 文字 + 时间线）；仅左滑时垫页面底色遮住删除渐显。
                .then(if (offset.value != 0f) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
                .draggable(
                    state = rememberDraggableState { delta ->
                        if (!deleting) scope.launch { offset.snapTo(HistoryTimelinePolicy.clampSwipeOffset(offset.value, delta, widthPx)) }
                    },
                    orientation = Orientation.Horizontal,
                    enabled = enabled && !deleting,
                    onDragStopped = { velocity ->
                        if (HistoryTimelinePolicy.shouldDelete(offset.value, widthPx, velocity)) {
                            deleting = true
                            // r657 修复：越过阈值后卡片回位并粒子消散，结束后再删除（原型播放历史左滑删除）。
                            offset.animateTo(0f, tween(durationMillis = 120))
                            dissolve.dissolve { onDelete(); deleting = false }
                        } else {
                            offset.animateTo(0f, tween(durationMillis = 180))
                        }
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun HistoryTimelineRail(time: String, isFirst: Boolean, isLast: Boolean, latest: Boolean = false) {
    // r656 item 12：时间放在竖线左侧（右对齐 DM Mono 粗体），其右为珊瑚细竖线 + 空心珊瑚圆环（白芯）。
    val colors = historyPageThemeColors(MaterialTheme.colorScheme)
    val accent = SoundwaveDesignPalette.Accent
    val core = MaterialTheme.colorScheme.background
    Row(
        Modifier.width(HistoryTimelinePolicy.TIME_RAIL_WIDTH_DP.dp).height(70.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time.ifBlank { "—" },
            color = colors.onSurface,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(HistoryTimelinePolicy.TIME_COLUMN_WIDTH_DP.dp)
        )
        Canvas(Modifier.width(HistoryTimelinePolicy.RAIL_WIDTH_DP.dp).fillMaxHeight()) {
            val x = size.width / 2f
            val centerY = size.height / 2f
            drawLine(
                color = accent.copy(alpha = 0.55f),
                start = Offset(x, if (isFirst) centerY - 26.dp.toPx() else 0f),
                end = Offset(x, if (isLast) centerY + 26.dp.toPx() else size.height),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(color = core, radius = 7.dp.toPx(), center = Offset(x, centerY))
            // r657：原型最新一条（now）节点为实心珊瑚 + 淡珊瑚光晕，其余为空心圆环。
            if (latest) {
                drawCircle(color = accent.copy(alpha = 0.18f), radius = 10.dp.toPx(), center = Offset(x, centerY))
                drawCircle(color = accent, radius = 6.dp.toPx(), center = Offset(x, centerY))
            } else drawCircle(color = accent, radius = 6.dp.toPx(), center = Offset(x, centerY), style = androidx.compose.ui.graphics.drawscope.Stroke(2.6.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayHistoryPage(
    history: List<Song>,
    onPlay: (Song) -> Unit = {},
    onDelete: (Song) -> Unit = {},
    onClear: () -> Unit = {},
    // Kept for source compatibility; history no longer exposes long-press batch deletion.
    onDeleteBatch: (List<Song>, Boolean) -> Unit = { _, _ -> }
) {
    PlaySourceScope("播放历史") // r657 第二轮：播放来源
    val themeColors = historyPageThemeColors(MaterialTheme.colorScheme)
    val onBg = themeColors.onSurface
    val muted = themeColors.onSurfaceVariant.copy(alpha = 0.55f)
    var showClearDlg by remember { mutableStateOf(false) }
    val nowMillis = System.currentTimeMillis()
    fun dayKey(ms: Long): String = ListeningCalendar.historyDayLabel(ms, nowMillis)
    fun clock(ms: Long): String = ListeningCalendar.clock(ms)
    // r657：原型筛选 chip（在线 / 本地）。r657 第二轮起历史条目带 playSource（「来自 X」），旧记录无来源时不显示。
    var historyFilter by remember { mutableStateOf("all") }
    val visibleHistory = remember(history, historyFilter) {
        when (historyFilter) {
            "local" -> history.filter { it.source == "local" }
            "online" -> history.filter { it.source != "local" }
            else -> history
        }
    }
    val grouped = visibleHistory.groupBy { dayKey(it.playedAt) }
    val order = grouped.keys.toList()
    // r657 修复（审计第 20 项）：首屏（前 8 级）错峰入场：说明行 → 日期 → 各条记录；更深的行不做入场。
    val historyStagger = xyz.cncnlp.soundwave.ui.common.rememberStaggerGate()
    val staggerIndex = remember(history) {
        var n = 1; val m = HashMap<String, Int>()
        order.forEach { d -> m["day_$d"] = n++; grouped[d].orEmpty().forEach { m[it.key()] = n++ } }
        m
    }
    fun Modifier.historyStagger(key: String): Modifier = staggerIndex[key]?.takeIf { it <= xyz.cncnlp.soundwave.ui.common.StaggerMotion.MAX_STEPS }
        ?.let { this.then(Modifier.staggerEnter(it, historyStagger)) } ?: this
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = SettingsMetrics.SubPadH.dp), contentPadding = PaddingValues(top = 0.dp, bottom = dockContentBottomPadding()), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Row(Modifier.fillMaxWidth().staggerEnter(0, historyStagger), verticalAlignment = Alignment.CenterVertically) {
                // r655（设计稿 #history）：去掉顶部「播放记录」标题，改为「最近播放」+ 说明，记录整体上移。
                Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
                    Text("最近播放", color = onBg, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 22.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope)
                    Text(
                        "时间戳独立记录，不与听歌统计混排 · 左滑删除",
                        color = themeColors.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (history.isNotEmpty()) {
                    Text("清空", color = themeColors.error, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showClearDlg = true }.padding(8.dp))
                }
            }
        }
        if (history.isNotEmpty()) item(key = "history_week") {
            HistoryWeekCard(history, nowMillis, Modifier.staggerEnter(1, historyStagger)) { visibleHistory.firstOrNull()?.let(onPlay) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "online" to "在线", "local" to "本地").forEach { (key, label) ->
                    val on = historyFilter == key
                    Box(
                        Modifier.height(32.dp).clip(CircleShape)
                            .background(if (on) onBg else Color.Transparent)
                            .border(1.dp, if (on) onBg else themeColors.onSurfaceVariant.copy(alpha = 0.28f), CircleShape)
                            .clickable { historyFilter = key }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = if (on) MaterialTheme.colorScheme.background else onBg, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
        if (history.isEmpty()) item {
            // SW-12-13（G 波）：历史整页为空时改用统一状态块（原为一行裸文本）；区块级内联空态仍保持内联。
            xyz.cncnlp.soundwave.ui.common.StateBlock(
                icon = xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs.History,
                title = "暂无历史",
                subtitle = "播放过的歌曲会按日期与时间排列在这里"
            )
        }
        order.forEach { day ->
            val songsForDay = grouped[day].orEmpty()
            item(key = "day_$day") {
                val stamp = songsForDay.firstOrNull()?.playedAt ?: 0L
                val date = if (stamp > 0L) java.text.SimpleDateFormat("M 月 d 日", java.util.Locale.CHINA).format(java.util.Date(stamp)) else ""
                Text(
                    if (date.isNotEmpty() && day != date) "$day · $date" else day,
                    color = designAccentTextColor(), fontWeight = FontWeight.Medium, fontSize = 9.sp,
                    fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, letterSpacing = 0.08.em,
                    modifier = Modifier.historyStagger("day_$day").padding(start = 2.dp, top = 18.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(songsForDay, key = { _, song -> song.key() }) { rowIndex, s ->
                Row(Modifier.fillMaxWidth().animateItem().historyStagger(s.key()), verticalAlignment = Alignment.CenterVertically) {
                    HistoryTimelineRail(
                        time = clock(s.playedAt),
                        isFirst = rowIndex == 0,
                        isLast = rowIndex == songsForDay.lastIndex,
                        latest = s.key() == order.firstOrNull()?.let { grouped[it]?.firstOrNull()?.key() }
                    )
                    SwipeToDeleteHistoryRow(
                        modifier = Modifier.weight(1f),
                        enabled = true,
                        onDelete = { onDelete(s) }
                    ) {
                        Row(
                            Modifier.fillMaxSize().clickable { onPlay(s) }.padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(themeColors.onSurface.copy(alpha = 0.07f)),
                                contentAlignment = Alignment.Center
                            ) {
                                // ACCEPT5 收编：黑胶母题占位常驻垫底（此前无封面是「♪」文本、加载时只剩 7% 底）。
                                DefaultCoverPlaceholder(Modifier.fillMaxSize())
                                if (s.cover.isNotEmpty()) {
                                    AsyncImage(
                                        model = s.cover,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                                Text(s.name, color = onBg, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(historySubtitle(s), color = themeColors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)) // r657 第二轮：「歌手 · 来自 X」
                            }
                        }
                    }
                }
            }
        }
    }
    if (showClearDlg) {
        SettingsConfirmSheet(
            onDismissRequest = { showClearDlg = false },
            eyebrow = "History · Clear",
            title = "清空播放历史？",
            text = { Text("将清空本机全部 ${history.size} 条独立播放记录；听歌统计、收藏与下载文件不受影响。") },
            dismissButton = { val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearDlg = false }; SettingsSheetButton(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") } },
            confirmButton = { val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showClearDlg = false }; SettingsSheetButton(onClick = { onClear(); dismiss() }, modifier = Modifier.weight(1f), danger = true) { Text("清空") } }
        )
    }
}

/** r657：原型播放历史顶部 THIS WEEK 卡——近 7 天播放条数 + 本周聆听小时 + 珊瑚播放键。 */
@Composable
private fun HistoryWeekCard(history: List<Song>, nowMillis: Long, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    val themeColors = historyPageThemeColors(MaterialTheme.colorScheme)
    val ctx = LocalContext.current
    val accent = SoundwaveDesignPalette.Accent
    val weekCount = remember(history, nowMillis / 60_000L) { history.count { it.playedAt > 0L && nowMillis - it.playedAt < 7L * 24 * 3600 * 1000 } }
    val hours = remember(history) { runCatching { ListenStats.weekSec(ctx) }.getOrDefault(0L).coerceAtLeast(0L) / 3600f }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp).clip(shape)
            .background(themeColors.onSurface.copy(alpha = 0.04f))
            .border(1.dp, themeColors.onSurfaceVariant.copy(alpha = 0.18f), shape)
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("THIS WEEK", color = accent, fontSize = 10.sp, fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.12.em, maxLines = 1)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text("$weekCount", color = themeColors.onSurface, fontSize = 34.sp, lineHeight = 38.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("首", color = themeColors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, end = 16.dp))
                Text(String.format(java.util.Locale.ROOT, "%.1f", hours), color = themeColors.onSurface, fontSize = 34.sp, lineHeight = 38.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("小时", color = themeColors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
        }
        Box(
            Modifier.size(52.dp)
                .shadow(12.dp, CircleShape, ambientColor = accent.copy(alpha = 0.32f), spotColor = accent.copy(alpha = 0.5f))
                .clip(CircleShape).background(accent).clickable(onClick = onPlay)
                .semantics { contentDescription = "播放本周历史" },
            contentAlignment = Alignment.Center
        ) {
            Icon(xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs.PlaySolid, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
