package xyz.cncnlp.soundwave

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.cncnlp.soundwave.source.MusicSourceRegistry

/** 网易云官方榜紧凑卡：主封面 + 右侧两张半透明叠封面 + 真实前三首。 */
@Composable
internal fun OfficialRankCard(board: RankBoard, onOpen: (RankBoard) -> Unit) {
    var songs by remember(board.id, board.source) { mutableStateOf(board.songs.take(3)) }
    var loading by remember(board.id, board.source) { mutableStateOf(board.songs.size < 3) }
    LaunchedEffect(board.id, board.source) {
        // 服务端榜单带前三首；不足 3 首（或只有元数据）时补拉详情，保证折叠效果图有真实前三首。
        if (board.songs.size < 3 && board.id.isNotBlank()) {
            val fetched = withContext(Dispatchers.IO) {
                if (board.source in MusicSourceRegistry.platformIds) {
                    MusicSourceRegistry.platformSource(board.source).rank(board.id)
                } else {
                    ApiClient.rankDetail(board.id, board.source)
                }
            }
            songs = fetched.take(3)
            loading = false
        }
    }
    val title = MaterialTheme.colorScheme.onBackground
    val muted = title.copy(alpha = .52f)
    // 主封面只取榜一曲自己的封面或官方榜单封面；不能把其它歌曲的封面挪来冒充榜一曲。
    // 侧叠层分别使用榜二、榜三的真实封面，缺图时才沿用已知真实主封面。
    val rawCovers = rankCardCoverPool(songs, board.source, board.name)
    val cover0 = rawCovers.getOrNull(0).orEmpty().ifEmpty { board.cover }
    val cover1 = rawCovers.getOrNull(1).orEmpty().ifEmpty { cover0 }
    val cover2 = rawCovers.getOrNull(2).orEmpty().ifEmpty { cover1 }
    // 与首页预下载缓存接线：已有磁盘封面先读 File，弱网/离线不重复请求远端。
    val coverContext = LocalContext.current
    val cover0Model = remember(cover0) { CacheManager.coverModel(coverContext, cover0) ?: cover0 }
    val cover1Model = remember(cover1) { CacheManager.coverModel(coverContext, cover1) ?: cover1 }
    val cover2Model = remember(cover2) { CacheManager.coverModel(coverContext, cover2) ?: cover2 }
    // 仅展示平台实际提供的更新时间；未知时不填充更新周期或实时状态。
    val frequency = board.officialUpdateLabel()
    // 只有首曲封面和榜单官方封面都缺失时才用默认封面；不挪用别曲封面。
    val useDefaultCover = cover0.isBlank()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { onOpen(board.copy(songs = songs.toMutableList())) }
    ) {
        Row(Modifier.fillMaxWidth().height(135.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 折叠封面以轻透叠片呈现，不绘制卡片投影。
            val foldSize = 108.dp
            val foldR = RoundedCornerShape(foldSize * 0.13f)
            Box(Modifier.size(foldSize)) {
                if (!useDefaultCover) {
                    AsyncImage(cover2Model, null, Modifier.align(Alignment.CenterEnd).offset(x = 14.dp, y = 12.dp).size(foldSize * 0.66f).clip(foldR), contentScale = ContentScale.Crop, alpha = 0.22f)
                    AsyncImage(cover1Model, null, Modifier.align(Alignment.CenterEnd).offset(x = 7.dp, y = 5.dp).size(foldSize * 0.80f).clip(foldR), contentScale = ContentScale.Crop, alpha = 0.45f)
                }
                Box(Modifier.align(Alignment.CenterStart).size(foldSize).clip(foldR).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))) {
                    // ACCEPT5 收编：默认封面常驻垫底——主封面加载/失败时不再只剩 primary 10% 淡底 + 播放三角。
                    DefaultLandscapeCover(Modifier.fillMaxSize())
                    if (!useDefaultCover) AsyncImage(cover0Model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // 与播放器一致的圆角三角：无圆底，只圆化三角三个拐点。
                        Canvas(Modifier.size(31.dp)) {
                            val p = Path().apply {
                                moveTo(size.width * .31f, size.height * .18f)
                                quadraticBezierTo(size.width * .23f, size.height * .18f, size.width * .23f, size.height * .31f)
                                lineTo(size.width * .23f, size.height * .69f)
                                quadraticBezierTo(size.width * .23f, size.height * .82f, size.width * .31f, size.height * .82f)
                                lineTo(size.width * .72f, size.height * .56f)
                                quadraticBezierTo(size.width * .82f, size.height * .50f, size.width * .72f, size.height * .44f)
                                close()
                            }
                            drawPath(p, Color.White.copy(alpha = .94f))
                            drawPath(p, Color.White.copy(alpha = .94f), style = Stroke(1.1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            // 榜单右侧标题与前三首文字整体向右 4dp，封面堆叠位置保持不变。
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(board.name, color = title.copy(alpha = .9f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(frequency, color = title.copy(alpha = .72f), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                // 前三曲文字向上收拢（行距 3dp）且底部与封面底部对齐
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    songs.forEachIndexed { index, song ->
                        Text("${index + 1}. ${song.name} - ${song.singer}", color = title.copy(alpha = .9f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (songs.isEmpty()) Text(if (loading) "正在获取…" else "暂无可用歌曲", color = muted, fontSize = 11.sp)
                }
            }
        }
    }
}

/** 榜单推荐用的主页同规格折叠卡：固定 108dp 局部坐标，避免背景两层被横卡宽度拉偏。 */
@Composable
internal fun RankFoldedTile(board: RankBoard, onOpen: () -> Unit) {
    val context = LocalContext.current
    val cover = board.previewCover.ifBlank { board.cover }
    val useDefault = cover.isBlank()
    val model = remember(cover) { CacheManager.coverModel(context, cover) ?: cover }
    val size = 108.dp
    val radius = RoundedCornerShape(size * 0.13f)
    val title = MaterialTheme.colorScheme.onBackground
    Column(Modifier.width(112.dp).clickable(onClick = onOpen)) {
        Box(Modifier.size(size)) {
            if (!useDefault) {
                AsyncImage(model, null, Modifier.align(Alignment.CenterEnd).offset(x = 14.dp, y = 12.dp).size(size * .66f).clip(radius), contentScale = ContentScale.Crop, alpha = .22f)
                AsyncImage(model, null, Modifier.align(Alignment.CenterEnd).offset(x = 7.dp, y = 5.dp).size(size * .80f).clip(radius), contentScale = ContentScale.Crop, alpha = .45f)
            }
            Box(Modifier.align(Alignment.CenterStart).size(size).clip(radius).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f))) {
                // ACCEPT5 收编：默认封面常驻垫底（同 OfficialRankCard 主封面）。
                DefaultLandscapeCover(Modifier.fillMaxSize())
                if (!useDefault) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Text(board.name, color = title.copy(alpha = .92f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp).height(28.dp))
    }
}
