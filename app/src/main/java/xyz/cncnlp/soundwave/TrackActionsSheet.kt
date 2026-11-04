package xyz.cncnlp.soundwave

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignQuickTile
import xyz.cncnlp.soundwave.ui.common.DesignQuickTileRow
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.DesignSheetItemHeader
import xyz.cncnlp.soundwave.ui.common.DesignSheetPinnedHeader
import xyz.cncnlp.soundwave.ui.common.DesignSheetRow
import xyz.cncnlp.soundwave.ui.common.FrostDivider
import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.common.localGlassSource
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.rememberLocalGlassState
import xyz.cncnlp.soundwave.ui.common.rippleGlow
import xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss

/*
 * 全 App 唯一的「歌曲操作」抽屉布局——珊瑚眉题 TRACK ACTIONS + 「歌曲操作」。
 *
 * ACCEPT4（用户 2026-11-04 裁定 + 审计 §7 案 A）：高度封顶 0.70 不变；滚动结构改为
 * 「只固定拖柄 + 标题头」——条目头（封面 + 歌名/歌手）、出处区、五瓷砖、动作行整块一区滚动
 * （M 波的固定区/内滚区拆分废止：720dp 机高下动作行曾只露 0–1 行）。内滚 state 仍 hoist 给
 * 面板拖拽关闭连接（nestedScroll 改绑整区）。出处区只保留一行「播放来源」：无封面，点击按
 * 来源身份直达来源详情页（歌单/专辑/榜单）；歌手、专辑移入动作行作普通选项；旧「播放来源」
 * 候选列表整套已删除、无任何回退——来源身份缺失时该行不出（案 i）。
 * 关闭动画（审计 §9 C 类）：瓷砖与动作行由内容侧在动作执行后统一走 J8 退场句柄关闭，
 * 宿主 prop 不再同步清状态（状态清除由退场完成后的 onDismiss 承担）。
 *
 * 详情页 / 今日推荐 / 搜索（经 DesignTrackActionSheetContent 委托）与播放器「⋯」（FullPlayerMenuSheet）共用本组件；
 * 外壳统一为 ui/common DesignSheetDialog（贴屏幕底边、从底部 translateY 滑入，不淡入 / 缩放）。
 */

/** 歌曲操作弹层高度封顶（占视口高比例）；两个宿主共用同一口径。 */
internal const val TRACK_ACTIONS_SHEET_MAX_HEIGHT_FRACTION = 0.70f

/** 本弹层动作行高（仅本弹层收紧，家族 DesignSheetRow 默认 56dp 不动）。 */
internal val TRACK_ACTION_ROW_HEIGHT = 52.dp

/** 顶部五个快捷钮的顺序（无下载能力时第三位改为「加入歌单」；「不喜欢」恒末位）；纯函数便于单测。 */
internal fun trackActionQuickTileKeys(showDownload: Boolean): List<String> =
    listOf("like", "next", if (showDownload) "download" else "add", "similar", "dislike")

/** 「播放来源」行的来源类型（决定无障碍文案；跳转一律走歌单身份直达）。 */
internal enum class TrackSourceKind(val label: String) { PLAYLIST("歌单"), PLAYBACK("播放来源") }

/** 「播放来源」行（纯数据，组装见 [trackPlaybackSourceEntry]）。身份恒非空——无身份时整行不出（案 i）。 */
internal data class TrackSourceEntry(
    val kind: TrackSourceKind,
    val title: String,
    val subtitle: String,
    val playlist: Playlist
)

/** 歌单行副行补充：本地歌单 / 平台名 + 歌曲数（count 优先，未知时以已加载曲目数兜底）。 */
internal fun playlistSourceMeta(playlist: Playlist): String {
    val parts = mutableListOf<String>()
    if (playlist.local) parts += "本地歌单"
    else if (playlist.source.isNotBlank()) parts += Platforms.label(playlist.source)
    val count = if (playlist.count > 0) playlist.count else playlist.songs.size
    if (count > 0) parts += "$count 首"
    return parts.joinToString(" · ")
}

/**
 * 「播放来源」行组装（纯函数，ACCEPT4 用户裁定）：出处只一行，标题 = 来源名，副行 = 「播放来源 · <meta>」。
 * - 播放器场景（传了 [playbackLabel] 或 [playbackPlaylist]）：身份 = [playbackPlaylist]；标题优先用 label。
 * - 其余场景：身份 = [originPlaylist]（容器歌单）；菜单开在来源页之外时由宿主传入。
 * - 身份缺失（只有名字、没有可直达的 Playlist）→ 返回 null，整行不出（案 i，不回退任何候选列表）。
 */
internal fun trackPlaybackSourceEntry(
    originPlaylist: Playlist?,
    playbackLabel: String = "",
    playbackPlaylist: Playlist? = null
): TrackSourceEntry? {
    val playerScene = playbackLabel.isNotBlank() || playbackPlaylist != null
    if (playerScene) {
        val identity = playbackPlaylist ?: return null
        return TrackSourceEntry(
            kind = TrackSourceKind.PLAYBACK,
            title = playbackLabel.ifBlank { identity.name },
            subtitle = listOf("播放来源", playlistSourceMeta(identity)).filter { it.isNotBlank() }.joinToString(" · "),
            playlist = identity
        )
    }
    val origin = originPlaylist ?: return null
    return TrackSourceEntry(
        kind = TrackSourceKind.PLAYLIST,
        title = origin.name,
        subtitle = listOf("播放来源", playlistSourceMeta(origin)).filter { it.isNotBlank() }.joinToString(" · "),
        playlist = origin
    )
}

/**
 * 出处区：眉题行（「出处」+ 珊瑚 DmMono SOURCE）+ 唯一一行「播放来源」。
 * ACCEPT5（用户 2026-11-04 字号裁定）：层级拉开——「出处」16sp 须大于下方来源名（15sp），
 * SOURCE 降为 9.5sp（与 LoadingPill 眉题同值）；此处局部取值，不动 ThemeTokens.Sheet 眉题
 * token（该 token 同时供弹层大标题 TRACK ACTIONS 等眉题，不顺手波及）。
 */
@Composable
private fun TrackSourceSection(entry: TrackSourceEntry, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("出处", color = colors.onSurface, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Text(
            "SOURCE", color = SoundwaveDesignPalette.Accent, fontSize = 9.5.sp, lineHeight = 12.sp,
            fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.18.em, maxLines = 1
        )
    }
    DesignSheetSourceRow(entry, onClick)
}

/** 「播放来源」行（ACCEPT4）：54dp；无封面缩略图——名称 + 「播放来源 · …」副行 + 右侧家族式「查看」hint（不加 chevron）。 */
@Composable
internal fun DesignSheetSourceRow(entry: TrackSourceEntry, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        FrostDivider(designLineColor())
        Row(
            Modifier.fillMaxWidth().height(54.dp).pressScale(PressMotion.SCALE_ROW).rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.22f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .semantics { contentDescription = "查看播放来源：${entry.title}"; role = Role.Button }
                .padding(start = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(entry.title, color = colors.onSurface, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.subtitle.isNotBlank()) Text(
                    entry.subtitle, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text("查看", color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope, letterSpacing = 0.04.em, maxLines = 1)
        }
    }
}

@Composable
internal fun ColumnScope.TrackActionsSheetContent(
    song: Song,
    liked: Boolean,
    showDownload: Boolean,
    canDelete: Boolean,
    onSimilar: () -> Unit,
    onLike: () -> Unit,
    onAddNext: () -> Unit,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDislike: () -> Unit,
    onNavigate: () -> Unit = {},
    disliked: Boolean = false,
    /** 非空时「播放取链链路」交给调用方（播放器沿用自身的取链抽屉状态）；为空时在本抽屉内打开播放路径抽屉。 */
    onPlaybackLink: (() -> Unit)? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    /** 调用方专属列表项（播放器：投屏与播放设备、心动模式、睡眠定时、歌词设置、音量条），排在危险区（从歌单删除）之前。 */
    extraRows: @Composable () -> Unit = {},
    /** 出处「播放来源」行身份（容器歌单）；菜单开在该歌单自己的详情页时宿主传 null。播放器场景改走 playback* 参数。 */
    originPlaylist: Playlist? = null,
    /** 播放器场景：PlaySourceTracker.label()（如「今日推荐」/ 歌单名）。 */
    playbackSourceLabel: String = "",
    /** 播放器场景：PlaySourceTracker.originPlaylist()；非空时播放来源行按身份直达详情，为空整行不出（案 i）。 */
    playbackSourcePlaylist: Playlist? = null,
    /** 整区滚动的 ScrollState（宿主须与 DesignSheetDialog 的 contentScrollState 传同一实例，拖拽关闭改绑它）。 */
    listScrollState: ScrollState? = null
) {
    val g = DesignSheetGlyphs
    val ctx = LocalContext.current
    // SW-12-02（G 波）：收藏/不喜欢切换触感。
    val hapticView = androidx.compose.ui.platform.LocalView.current
    // J8 口径：动作立即执行、状态清理由退场完成承担。瓷砖/动作行/跳转行统一在动作后走退场句柄关闭；
    // 不在弹层内时回退宿主 onNavigate。
    val dismiss = rememberDesignSheetDismiss { onNavigate() }
    val scrollState = listScrollState ?: rememberScrollState()
    var showPath by remember { mutableStateOf(false) }
    // ACCEPT6 dialogfrost：标题头改 pinned 覆盖层——静止时全透与表身无缝，内容滚入带下时
    // 标题带呈磨砂（局部模糊源 = 下方整滚区，与表磨砂同源同值）；滚动区保持全高整滚（案 A 不变），
    // 顶部以等高占位（眉题 14 + 间距 8 + 标题 30 = 52dp）让首屏布局与改前逐 dp 一致。
    val headerGlass = rememberLocalGlassState()
    Box(Modifier.weight(1f)) {
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).localGlassSource(headerGlass)) {
        Spacer(Modifier.height(52.dp))
        val durationLabel = if (song.duration > 0) (song.duration / 1000).let { "%d:%02d".format(it / 60, it % 60) } else ""
        val lossless = song.actualQuality.lowercase().let { q -> listOf("flac", "hires", "hi-res", "master", "24bit").any { it in q } }
        DesignSheetItemHeader(
            song.cover, song.name, listOf(song.singer.ifBlank { "未知歌手" }, song.album, durationLabel).filter { it.isNotBlank() }.joinToString(" · "),
            badge = if (lossless) "无损" else "", topPadding = 16.dp, bottomPadding = 12.dp, trailing = headerTrailing
        )
        DesignQuickTileRow(trackActionQuickTileKeys(showDownload).map { key ->
            when (key) {
                "like" -> DesignQuickTile(if (liked) g.HeartFilled else g.Heart, if (liked) "已收藏" else "收藏", if (liked) "取消收藏" else "收藏歌曲", active = liked, onClick = { if (!liked) xyz.cncnlp.soundwave.ui.common.AppHaptics.confirm(hapticView) else xyz.cncnlp.soundwave.ui.common.AppHaptics.tap(hapticView); onLike(); dismiss() })
                "next" -> DesignQuickTile(g.PlayNext, "下一首", "下一首播放", onClick = { onAddNext(); dismiss() })
                "download" -> DesignQuickTile(g.Download, "下载", onClick = { onDownload(); dismiss() })
                "add" -> DesignQuickTile(g.AddToPlaylist, "加入歌单", "添加到歌单", onClick = { onAdd(); dismiss() })
                "similar" -> DesignQuickTile(g.Similar, "相似歌曲", onClick = { onSimilar(); dismiss() })
                // 「不喜欢」第五钮（恒末位）：已不喜欢态文案「已不喜欢」；激活态走 danger 浅底而非珊瑚实心。
                else -> DesignQuickTile(g.Dislike, if (disliked) "已不喜欢" else "不喜欢", if (disliked) "取消不喜欢" else "不喜欢", active = disliked, danger = true, onClick = { xyz.cncnlp.soundwave.ui.common.AppHaptics.toggle(hapticView, !disliked); onDislike(); dismiss() })
            }
        })
        // 出处（ACCEPT5 移位）：五宫格之下、下方信息/导航行组第一块；组装与直达行为不变。
        val sourceEntry = remember(originPlaylist, playbackSourceLabel, playbackSourcePlaylist) {
            trackPlaybackSourceEntry(originPlaylist, playbackSourceLabel, playbackSourcePlaylist)
        }
        if (sourceEntry != null) {
            TrackSourceSection(sourceEntry) { dismiss(); TrackNavigation.openPlaylist(sourceEntry.playlist) }
        }
        // 动作行：添加到歌单（高频）→ 歌手 / 专辑（ACCEPT4 自出处区移入的普通选项行）→ 分享 → 播放取链链路
        // → 调用方专属行 → 危险区（从歌单删除）垫底。
        if (showDownload) DesignSheetRow(g.AddToPlaylist, "添加到歌单", "选择歌单", height = TRACK_ACTION_ROW_HEIGHT, onClick = { onAdd(); dismiss() })
        if (TrackNavigation.canOpenArtist(song)) DesignSheetRow(g.Artist, "歌手", TrackNavigation.primaryArtist(song.singer), height = TRACK_ACTION_ROW_HEIGHT, onClick = { dismiss(); TrackNavigation.openArtist(ctx, song) })
        // ACCEPT5（用户 2026-11-04 裁定）：专辑行恒在——有真实专辑才可进；无专辑标「无专辑」且
        // 禁用（点按无反应，不死点击不假跳转）。openAlbum 内部 canOpenAlbum 守卫保留作第二道闸。
        val canOpenAlbum = TrackNavigation.canOpenAlbum(song)
        DesignSheetRow(g.Album, "专辑", if (canOpenAlbum) song.album else "无专辑", enabled = canOpenAlbum, height = TRACK_ACTION_ROW_HEIGHT, onClick = { dismiss(); TrackNavigation.openAlbum(ctx, song) })
        DesignSheetRow(g.Share, "分享", "发送歌曲信息", height = TRACK_ACTION_ROW_HEIGHT, onClick = { dismiss(); TrackNavigation.share(ctx, song) })
        // ACCEPT5（退出动画普查 P2）：播放器场景交给宿主开路径层时同走退场句柄（宿主已不
        // 同步清菜单状态）；独立形态仍在本层叠开路径层、父层不退（语义不变）。
        DesignSheetRow(g.Info, "播放取链链路", "查看播放路径", height = TRACK_ACTION_ROW_HEIGHT, onClick = { if (onPlaybackLink != null) { onPlaybackLink(); dismiss() } else showPath = true })
        extraRows()
        if (canDelete) {
            // ACCEPT6 dialogfrost：危险区上方空带（v8 稿面语言，全站动作表统一；只留白不加色）。
            Spacer(Modifier.height(ThemeTokens.Sheet.DangerZoneTopGap))
            DesignSheetRow(g.Delete, "从歌单删除", "仅移除曲目", danger = true, height = TRACK_ACTION_ROW_HEIGHT, onClick = { onDelete(); dismiss() })
        }
    }
    DesignSheetPinnedHeader(scrollState, headerGlass) {
        DesignSheetHeader("Track actions", "歌曲操作")
    }
    }
    if (showPath) PlaybackPathSheet(song) { showPath = false }
}

/** 独立弹出的「歌曲操作」抽屉（统一 DesignSheetDialog 外壳；ACCEPT4：封顶 0.70 + 整块一区滚动）。 */
@Composable
internal fun TrackActionsSheet(
    song: Song,
    liked: Boolean,
    showDownload: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSimilar: () -> Unit,
    onLike: () -> Unit,
    onAddNext: () -> Unit,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onDislike: () -> Unit,
    /** 出处「播放来源」行身份（容器歌单）；开在该歌单自己的详情页时传 null（默认）。 */
    originPlaylist: Playlist? = null
) {
    val listScrollState = rememberScrollState()
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, maxHeightFraction = TRACK_ACTIONS_SHEET_MAX_HEIGHT_FRACTION, contentScrollState = listScrollState) {
        TrackActionsSheetContent(song, liked, showDownload, canDelete, onSimilar, onLike, onAddNext, onAdd, onDownload, onDelete, onDislike, onNavigate = onDismiss, originPlaylist = originPlaylist, listScrollState = listScrollState)
    }
}
