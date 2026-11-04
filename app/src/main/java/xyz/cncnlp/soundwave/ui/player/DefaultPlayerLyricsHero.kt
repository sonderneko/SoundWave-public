package xyz.cncnlp.soundwave.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import xyz.cncnlp.soundwave.immersiveCoverHeroHeightDp

/**
 * Standard immersive lyrics artwork when the lyrics page is rendered on its own (no [DefaultPlayerRoute]).
 * Inside the route the persistent StandardImmersiveCoverLayer draws the one shared cover instead (r672),
 * so the two pages never cross-fade two different crops. Fades to transparent (no opaque seam).
 */
@Composable
internal fun DefaultPlayerLyricsHero(cover: String) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val height = immersiveCoverHeroHeightDp(LocalConfiguration.current.screenWidthDp).dp
    Box(Modifier.fillMaxWidth().height(height).offset(y = -statusTop)
        .playerHeroFadeTo({ Float.MAX_VALUE }, STANDARD_IMMERSIVE_FADE_FRACTION)) {
        DefaultPlayerArtwork(cover, "沉浸歌词封面", Modifier.fillMaxSize(), 0.dp)
    }
}
