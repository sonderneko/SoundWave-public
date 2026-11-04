package xyz.cncnlp.soundwave

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader

/** Only verified provider artist artwork is passed in; never substitute initials or album art. */
@Composable
internal fun ArchiveArtistAvatar(
    name: String,
    cover: String,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = LocalContext.current.imageLoader
) {
    if (cover.isBlank()) {
        ArchiveArtistPlaceholder(name, modifier)
    } else {
        // ACCEPT6-STATSMEDIA：与首页 HomeArtistCard 同口径走 CacheManager.coverModel 管线
        // （http→https 归一 + 校验缓存 + 尺寸变体）；此前直喂原始 URL，需要归一的头像
        // （http CDN 等）会被明文策略拦死成占位——同一头像在首页能出、领奖台出不来即此因。
        val context = LocalContext.current
        SubcomposeAsyncImage(
            model = CacheManager.coverModel(context, cover, 192) ?: cover,
            imageLoader = imageLoader,
            contentDescription = "$name 歌手头像",
            modifier = modifier,
            contentScale = ContentScale.Crop
        ) {
            when (painter.state) {
                is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                else -> ArchiveArtistPlaceholder(name, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ArchiveArtistPlaceholder(name: String, modifier: Modifier) {
    // ACCEPT5 收编：与封面占位同一体系——黑胶母题（此前传 Artist glyph 走字形圆盘分支）；父级负责圆形裁切，即圆形黑胶。
    DefaultCoverPlaceholder(
        modifier = modifier,
        contentDescription = "$name 默认歌手头像"
    )
}
