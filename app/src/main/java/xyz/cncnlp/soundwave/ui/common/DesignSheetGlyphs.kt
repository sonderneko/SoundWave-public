package xyz.cncnlp.soundwave.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 1.0.655 统一抽屉图标集（全新绘制）：24×24 网格、2dp 视觉边距、描边 1.8、圆端点 / 圆角连接、无填充
 * （仅 [HeartFilled] 为实心）。所有底部抽屉 / 操作弹窗（歌曲、歌单、专辑、榜单、今日推荐、音乐库、播放器）共用，
 * 保证左侧图标风格一致、互不重复。单色：Icon(tint = …) 着色。
 */
object DesignSheetGlyphs {
    private fun glyph(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    /** 相似歌曲：主星 + 小星（发现相近风格）。 */
    val Similar: ImageVector by lazy { glyph("sws_similar", "M10 4.5 11.6 9 16 10.5 11.6 12 10 16.5 8.4 12 4 10.5 8.4 9z", "M17.5 14.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z") }
    /** 收藏（空心心形）。 */
    val Heart: ImageVector by lazy { glyph("sws_heart", "M12 19.6 4.9 12.8A4.4 4.4 0 0 1 11.1 6.6L12 7.5l.9-.9a4.4 4.4 0 0 1 6.2 6.2z") }
    /** 已收藏（实心心形）。 */
    val HeartFilled: ImageVector by lazy { glyph("sws_heart_filled", "M12 19.6 4.9 12.8A4.4 4.4 0 0 1 11.1 6.6L12 7.5l.9-.9a4.4 4.4 0 0 1 6.2 6.2z", filled = true) }
    /** 下一首播放：三行列表 + 右下播放角标。 */
    val PlayNext: ImageVector by lazy { glyph("sws_play_next", "M4 6.5h11M4 11.5h11M4 16.5h6", "M15 14.2v5.6l4.6-2.8z") }
    /** 添加到歌单：三行列表 + 右下加号。 */
    val AddToPlaylist: ImageVector by lazy { glyph("sws_add_to_playlist", "M4 6.5h12M4 11.5h12M4 16.5h6", "M17.5 14v6M14.5 17h6") }
    /** 整张加入队列：叠放的两张卡片 + 箭头。 */
    val QueueAll: ImageVector by lazy { glyph("sws_queue_all", "M7 4.5h10M5 8h14a1.5 1.5 0 0 1 1.5 1.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5v-8A1.5 1.5 0 0 1 5 8z", "M9 13.5h6M12.5 11l2.5 2.5-2.5 2.5") }
    /** 下载：托盘 + 下箭头。 */
    val Download: ImageVector by lazy { glyph("sws_download", "M12 4v10.5M7.8 10.6 12 14.8l4.2-4.2", "M4.5 15.5v2.5A1.5 1.5 0 0 0 6 19.5h12a1.5 1.5 0 0 0 1.5-1.5v-2.5") }
    /** 从歌单删除：垃圾桶。 */
    val Delete: ImageVector by lazy { glyph("sws_delete", "M4.5 7h15M9.5 7V5.2a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1V7", "M6.5 7l.8 11.6a1.5 1.5 0 0 0 1.5 1.4h6.4a1.5 1.5 0 0 0 1.5-1.4L17.5 7", "M10.2 11v5M13.8 11v5") }
    /** 不喜欢：圆形禁止符。 */
    val Dislike: ImageVector by lazy { glyph("sws_dislike", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 1 0 0-17z", "M6 6l12 12") }
    /** 播放全部：圆环 + 播放三角。 */
    val PlayAll: ImageVector by lazy { glyph("sws_play_all", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 1 0 0-17z", "M10.2 8.8v6.4l5-3.2z") }
    /** 保存为歌单：书签 + 加号。 */
    val SaveAsPlaylist: ImageVector by lazy { glyph("sws_save_playlist", "M6.5 4.5h11a1 1 0 0 1 1 1v14l-6.5-4-6.5 4v-14a1 1 0 0 1 1-1z", "M12 8v5M9.5 10.5h5") }
    /** 搜索本页曲目：放大镜 + 内部横线。 */
    val SearchInPage: ImageVector by lazy { glyph("sws_search_in_page", "M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 1 0 0-13z", "M15.3 15.3 20 20", "M8 10.5h5") }
    /** 查看专辑：唱片。 */
    val Album: ImageVector by lazy { glyph("sws_album", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 1 0 0-17z", "M12 10a2 2 0 1 0 0 4 2 2 0 1 0 0-4z") }
    /** 查看歌手：人像。 */
    val Artist: ImageVector by lazy { glyph("sws_artist", "M12 4.5a3.6 3.6 0 1 0 0 7.2 3.6 3.6 0 1 0 0-7.2z", "M5 19.5a7 7 0 0 1 14 0") }
    /** 分享：上箭头出框。 */
    val Share: ImageVector by lazy { glyph("sws_share", "M12 3.8v11M8 7.8l4-4 4 4", "M7 11H6a1.5 1.5 0 0 0-1.5 1.5v6A1.5 1.5 0 0 0 6 20h12a1.5 1.5 0 0 0 1.5-1.5v-6A1.5 1.5 0 0 0 18 11h-1") }
    /** 重命名：铅笔。 */
    val Rename: ImageVector by lazy { glyph("sws_rename", "M15.5 4.5l4 4L9 19H5v-4z", "M13 7l4 4") }
    /** 更换封面：图片。 */
    val Cover: ImageVector by lazy { glyph("sws_cover", "M5.5 4.5h13a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z", "M4.5 16l4.5-4.5 4 4 2.5-2.5 4 4", "M15 7.6a1.2 1.2 0 1 0 0 2.4 1.2 1.2 0 1 0 0-2.4z") }
    /** 排序。 */
    val Sort: ImageVector by lazy { glyph("sws_sort", "M4.5 7h9M4.5 12h6.5M4.5 17h4", "M17.5 5v14M14.8 16.3l2.7 2.7 2.7-2.7") }
    /** 多选。 */
    val Select: ImageVector by lazy { glyph("sws_select", "M7 4.5h10A2.5 2.5 0 0 1 19.5 7v10a2.5 2.5 0 0 1-2.5 2.5H7A2.5 2.5 0 0 1 4.5 17V7A2.5 2.5 0 0 1 7 4.5z", "m8.6 12.2 2.4 2.4 4.4-4.8") }
    /** 切换平台 / 榜单（⇄ 循环箭头，与音乐库「切换平台」一致）。 */
    val Switch: ImageVector get() = DesignLineGlyphs.Repeat
    /** 移除 / 关闭。 */
    val Remove: ImageVector by lazy { glyph("sws_remove", "M6.5 6.5l11 11M17.5 6.5l-11 11") }
    /** 评论。 */
    val Comment: ImageVector by lazy { glyph("sws_comment", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v8.5a1.5 1.5 0 0 1-1.5 1.5H11l-4.5 3.5v-3.5h-1A1.5 1.5 0 0 1 4 15V6.5A1.5 1.5 0 0 1 5.5 5z", "M8 9.5h8M8 12.5h5") }
    /** 歌曲信息。 */
    val Info: ImageVector by lazy { glyph("sws_info", "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 1 0 0-17z", "M12 11v5.5M12 7.8v.1") }

    /** 统一图标集全部条目（截图 / 守卫用：确认图标互不重复）。 */
    val all: List<ImageVector> get() = listOf(
        Similar, Heart, HeartFilled, PlayNext, AddToPlaylist, QueueAll, Download, Delete, Dislike, PlayAll,
        SaveAsPlaylist, SearchInPage, Album, Artist, Share, Rename, Cover, Sort, Select, Switch, Remove, Comment, Info
    )
}
