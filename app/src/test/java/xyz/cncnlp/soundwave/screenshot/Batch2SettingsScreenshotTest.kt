package xyz.cncnlp.soundwave.screenshot

import androidx.compose.material.icons.filled.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.Dispatchers
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.cncnlp.soundwave.*
import xyz.cncnlp.soundwave.ui.common.*
import xyz.cncnlp.soundwave.ui.player.PlayerLyricActionsSheet
import java.io.File

/** Real LibraryPage entry + synthetic local image: no provider/account requests. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34], qualifiers="w412dp-h1100dp-xhdpi")
class Batch2SettingsScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun render(name:String, dark:Boolean=false, content:@Composable ()->Unit) {
        val dir=System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(dir!=null)
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).dispatcher(Dispatchers.Unconfined).crossfade(false).allowHardware(false).build())
        rule.setContent { SoundwaveDesignTheme(isDark=dark) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } } }
        repeat(5) { rule.waitForIdle(); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(100) }
        val view=rule.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir,"$name.png").apply {parentFile.mkdirs()}.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun settings(dark:Boolean) {
        render("settings-components-${if(dark) "dark" else "light"}",dark) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                xyz.cncnlp.soundwave.ui.settings.SettingsGroupLabel("播放设置", "PLAYBACK", eyebrowColor=xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Playback)
                xyz.cncnlp.soundwave.ui.settings.SettingsGroupCard {
                    xyz.cncnlp.soundwave.ui.settings.SettingsDesignRow(mark=xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Playback,onClick={}) {
                        xyz.cncnlp.soundwave.ui.settings.SettingsIconTile(androidx.compose.material.icons.Icons.Default.Settings,xyz.cncnlp.soundwave.ui.settings.SettingsTileTone.Neutral)
                        androidx.compose.material3.Text("播放与歌词")
                    }
                    xyz.cncnlp.soundwave.ui.settings.SettingsRowDivider()
                    xyz.cncnlp.soundwave.ui.settings.SettingsDesignRow(mark=xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Appearance,onClick={}) {
                        androidx.compose.material3.Text("主题与外观")
                    }
                }
            }
        }
        rule.onNodeWithText("播放与歌词").assertIsDisplayed()
    }
    @Test fun light()=settings(false)
    @Test fun dark()=settings(true)
}
