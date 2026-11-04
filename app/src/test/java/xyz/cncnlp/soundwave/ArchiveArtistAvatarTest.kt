package xyz.cncnlp.soundwave

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveArtistAvatarTest {
    @get:Rule val rule = createComposeRule()
    private var loader: ImageLoader? = null
    private val requests = CopyOnWriteArrayList<Any>()

    @After fun shutdown() { loader?.shutdown() }

    private fun imageLoader(gate: CompletableDeferred<Unit>? = null): ImageLoader =
        ImageLoader.Builder(RuntimeEnvironment.getApplication())
            .crossfade(false)
            .components {
                add(Interceptor { chain ->
                    requests.add(chain.request.data)
                    gate?.await()
                    if (chain.request.data.toString().contains("broken")) {
                        ErrorResult(null, chain.request, IllegalStateException("image unavailable"))
                    } else {
                        SuccessResult(ColorDrawable(Color.GREEN), chain.request, DataSource.NETWORK)
                    }
                })
            }.build().also { loader = it }

    private fun assertDefault() {
        rule.onNodeWithContentDescription("林 默认歌手头像", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("林", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun missingAvatarUsesGlyphWithoutRequestOrInitial() {
        val imageLoader = imageLoader()
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ArchiveArtistAvatar("林", "  ", Modifier.size(74.dp), imageLoader)
            }
        }
        assertDefault()
        assertEquals(0, requests.size)
    }

    @Test fun loadingUsesGlyphThenVerifiedUrlDisplaysRealImage() {
        val gate = CompletableDeferred<Unit>()
        val imageLoader = imageLoader(gate)
        val url = "https://example.com/verified-artist.jpg"
        rule.setContent {
            SoundwaveDesignTheme(isDark = false) {
                ArchiveArtistAvatar("林", url, Modifier.size(74.dp), imageLoader)
            }
        }
        assertDefault()
        rule.runOnIdle { gate.complete(Unit) }
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("林 歌手头像"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("林 歌手头像", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("林 默认歌手头像", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("林", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf(url), requests.toList())
    }

    @Test fun imageFailureUsesGlyphInDarkThemeAndNewUrlCanRecover() {
        val imageLoader = imageLoader()
        val cover = mutableStateOf("https://example.com/broken.jpg")
        rule.setContent {
            SoundwaveDesignTheme(isDark = true) {
                ArchiveArtistAvatar("林", cover.value, Modifier.size(56.dp), imageLoader)
            }
        }
        rule.waitUntil(5_000) { requests.isNotEmpty() }
        rule.waitForIdle()
        assertDefault()
        rule.runOnIdle { cover.value = "https://example.com/verified-artist.jpg" }
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("林 歌手头像"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("林 默认歌手头像", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { cover.value = "" }
        assertDefault()
        assertEquals(listOf("https://example.com/broken.jpg", "https://example.com/verified-artist.jpg"), requests.toList())
    }
}
