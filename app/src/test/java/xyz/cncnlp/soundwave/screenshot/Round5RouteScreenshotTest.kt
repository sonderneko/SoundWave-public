package xyz.cncnlp.soundwave.screenshot

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
class Round5RouteScreenshotTest {
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
    private fun daily(platform:String, missing:Boolean=false) {
        val dir=System.getenv("SW_SCREENSHOT_DIR"); Assume.assumeTrue(dir!=null)
        val image=File(rule.activity.cacheDir,"round4-art.png")
        val bitmap=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(0xFF558B79.toInt())
        val paint=Paint().apply {color=0xFFF8D596.toInt()}; canvas.drawCircle(730f,280f,240f,paint)
        paint.color=0xFF243B49.toInt(); canvas.drawRect(0f,650f,1080f,1080f,paint)
        image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        val songs=listOf(Song(id="first",name="First track",singer="Test artist",source=platform,cover=""),Song(id="second",name="Second track",singer="Test artist",source=platform,cover=if(missing) "" else image.absolutePath))
        val detail=todayRecommendationDetail(platform,songs)
        Assert.assertTrue(isTodayRecommendationDetail(detail))
        Assert.assertEquals(if(missing) "" else image.absolutePath,detail.cover)
        render("daily-$platform-${if(missing) "missing" else "cover"}") {
            LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=detail,detailSongs=songs,onOpen={},onBack={},onPlay={_,_->},onLogin={},onImport={})
        }
        rule.onNodeWithText("播放全部",useUnmergedTree=true).assertExists()
    }
    @Test fun dailyWy()=daily("wy")
    @Test fun dailyQq()=daily("tx")
    @Test fun dailyKg()=daily("kg")
    @Test fun dailyKw()=daily("kw")
    @Test fun dailyMg()=daily("mg")
    @Test fun dailyWyMissing()=daily("wy",true)
    @Test fun dailyQqMissing()=daily("tx",true)
    @Test fun dailyKgMissing()=daily("kg",true)
    @Test fun dailyKwMissing()=daily("kw",true)
    @Test fun dailyMgMissing()=daily("mg",true)

    private fun immersive(dark:Boolean) {
        val bmp=Bitmap.createBitmap(1080,1080,Bitmap.Config.ARGB_8888)
        val c=Canvas(bmp); c.drawColor(0xFF558B79.toInt()); val p=Paint().apply {color=0xFFF8D596.toInt()}; c.drawCircle(730f,280f,240f,p)
        val file=File(rule.activity.cacheDir,"immersive-route.png"); file.outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        val song=Song(id="test",name="Test track",singer="Test artist",source="wy",cover=file.absolutePath)
        val detail=Playlist(id="fixture-playlist",name="沉浸封面测试歌单",source="wy",cover=file.absolutePath,author="测试作者",count=74)
        render("immersive-route-${if(dark) "dark" else "light"}",dark) {
            LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=detail,detailSongs=listOf(song),onOpen={},onBack={},onPlay={_,_->},onLogin={},onImport={},detailCoverStyle=DetailCoverStyles.IMMERSIVE_HERO)
        }
        rule.onNodeWithText("播放全部",useUnmergedTree=true).assertExists()
        rule.onNodeWithText("测试作者 · 74 首 · 网易云",useUnmergedTree=true).assertExists()
    }
    @Test fun immersiveRouteLight()=immersive(false)
    @Test fun immersiveRouteDark()=immersive(true)
    @Test fun singerMetadataAndAlbumPaging() {
        var requests = 0
        val detail = Playlist(id="singer_wy_fixture",name="歌手姓名",source="wy",remoteId="fixture",count=50)
        val songs=listOf(Song(id="track",name="Track",singer="歌手姓名",source="wy",duration=210000))
        render("singer-route") {
            LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=detail,detailSongs=songs,onOpen={},onBack={},onPlay={_,_->},onLogin={},onImport={},detailCoverStyle=DetailCoverStyles.IMMERSIVE_HERO,
                loadSingerAlbums={ _, _ -> listOf(ArtistAlbum("first","第一张专辑",source="wy")) },
                loadSingerAlbumsPage={ _, _, page -> requests++; if(page==2) listOf(ArtistAlbum("next","后续专辑",source="wy")) else emptyList() })
        }
        rule.onAllNodesWithText("歌手姓名",useUnmergedTree=true).assertCountEquals(2)
        rule.onNodeWithText("50 首 · 3 MIN · 网易云",useUnmergedTree=true).assertExists()
        rule.onNodeWithText("加载更多专辑",useUnmergedTree=true).assertDoesNotExist()
        rule.waitForIdle()
        Assert.assertEquals(0,requests)
    }

    private fun builtInOrUserPlaylist(id:String, dark:Boolean=false, empty:Boolean=false, album:Boolean=false) {
        val builtin = id == "liked" || id == "local"
        val songList = if (empty) emptyList() else (1..3).map { Song(id="track-$it", name="Track $it",singer="Artist",source="wy") }
        val playlist = Playlist(id=id,name=if(id=="liked") "我的喜欢" else "测试歌单",author=if(id=="liked") "我的喜欢" else "测试作者",local=id!="imported-online",source="wy",songs=songList.toMutableList(),detailContentKind=if(album) "album" else "")
        val queues=mutableListOf<List<Song>>()
        render("scope-$id-${if(dark) "dark" else "light"}${if(empty) "-empty" else ""}",dark) {
            LibraryPage(bundle=LocalStore.UserBundle(),history=emptyList(),downloads=emptyList(),loggedIn=false,user="",detail=playlist,detailSongs=songList,onOpen={},onBack={},onPlay={first,queue-> Assert.assertEquals(first,queue.first());queues.add(queue)},onLogin={},onImport={},detailCoverStyle=DetailCoverStyles.IMMERSIVE_HERO)
        }
        if (id=="liked") {
            rule.onNodeWithText("在线歌单 · ${songList.size} 首",useUnmergedTree=true).assertExists()
            rule.onAllNodesWithText("我的喜欢",useUnmergedTree=true).assertCountEquals(2)
        }
        if(builtin) {
            val shuffle=rule.onNodeWithContentDescription("随机播放",useUnmergedTree=true)
            if(empty) shuffle.assertIsNotEnabled() else {
                shuffle.performClick(); rule.waitForIdle(); shuffle.performClick();rule.waitForIdle()
                Assert.assertEquals(2,queues.size)
                queues.forEach { q->Assert.assertEquals(3,q.size);Assert.assertEquals(songList.map { it.id }.toSet(),q.map { it.id }.toSet()) }
            }
        } else rule.onNodeWithContentDescription("随机播放",useUnmergedTree=true).assertDoesNotExist()
        Assert.assertEquals(if(builtin&&!empty) 2 else 0,queues.size)
    }
    @Test fun likedShuffleAndMetadataLight()=builtInOrUserPlaylist("liked")
    @Test fun likedShuffleAndMetadataDark()=builtInOrUserPlaylist("liked",true)
    @Test fun localShuffle()=builtInOrUserPlaylist("local")
    @Test fun localEmptyShuffleDisabled()=builtInOrUserPlaylist("local",empty=true)
    @Test fun importedHasNoNewShuffle()=builtInOrUserPlaylist("imported-online")
    @Test fun createdLocalPlaylistHasNoNewShuffle()=builtInOrUserPlaylist("user-created")
    @Test fun albumHasNoNewShuffle()=builtInOrUserPlaylist("album-fixture",album=true)

    private fun homeCovers(dark:Boolean,browse:Boolean) {
        val file=File(rule.activity.cacheDir,"shadow-cover.png")
        val bmp=Bitmap.createBitmap(180,180,Bitmap.Config.ARGB_8888)
        val c=Canvas(bmp); c.drawColor(0xFF558B79.toInt());c.drawCircle(120f,50f,38f,Paint().apply { color=0xFFF8D596.toInt() })
        file.outputStream().use {bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        val song=Song("fixture","测试歌曲",singer="测试歌手",source="wy",cover=file.absolutePath)
        val card=DiscoverCard("fixture","测试专辑",file.absolutePath,"测试歌手",source="wy",count=10)
        render("home-covers-${if(browse) "browse" else "feed"}-${if(dark) "dark" else "light"}",dark) {
            Column(Modifier.fillMaxSize().padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(!browse) {
                    androidx.compose.material3.Text("甄选歌单",Modifier.padding(horizontal=14.dp))
                    Box(Modifier.padding(horizontal=14.dp,vertical=10.dp)) {
                        HomeCuratedCard("歌单标题","作者",cover={mod->coil.compose.AsyncImage(file.absolutePath,null,mod)})
                    }
                    androidx.compose.material3.Text("热门歌曲",Modifier.padding(horizontal=14.dp))
                    HomeHotSongsPager(listOf(song),"wy","",false,{}, {_,_->})
                    androidx.compose.material3.Text("新歌速递",Modifier.padding(horizontal=14.dp))
                    HomeNewSongRow(song,0,"网易云",false,{})
                    androidx.compose.material3.Text("新碟首发",Modifier.padding(horizontal=14.dp))
                    Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal=14.dp)) { HomeDebutCard(card,0,null,onClick={}) }
                } else {
                    androidx.compose.material3.Text("数字专辑",Modifier.padding(horizontal=14.dp))
                    Box(Modifier.padding(horizontal=14.dp,vertical=12.dp)) { HomeDigitalAlbumCard(card,null,onClick={}) }
                    HomeFeatureCarousel(false,song,file.absolutePath,file.absolutePath,{},{},{},heartAvailable=true)
                    xyz.cncnlp.soundwave.ui.home.ChartHeroCard(RankBoard("排行榜",source="wy",cover=file.absolutePath),"网易云",onSwitch={})
                    xyz.cncnlp.soundwave.ui.home.PlaylistPlazaHero(listOf(Playlist("fixture","歌单广场",cover=file.absolutePath,author="测试作者")),"推荐",{})
                }
            }
        }
    }
    @Test fun homeFeedCoverShadowsLight()=homeCovers(false,false)
    @Test fun homeFeedCoverShadowsDark()=homeCovers(true,false)
    @Test fun homeBrowseCoverShadowsLight()=homeCovers(false,true)
    @Test fun homeBrowseCoverShadowsDark()=homeCovers(true,true)
    private fun lyrics(dark:Boolean, selected:Boolean=false) {
        render("lyric-actions-${if(dark) "dark" else "light"}${if(selected) "-selected" else ""}",dark) {
            ProvideAppOverlayHost {
                Box(Modifier.fillMaxSize()) {
                    PlayerLyricActionsSheet(selectedLine=if(selected) LyricLine(83000,"在旋律里，遇见每一个自己", "Find yourself in the melody", "zai xuan lv li") else null,showTranslation=selected,showRomanization=selected,hasLyricCorrection=selected,onDismiss={},onCorrection={},onToggleTranslation={},onToggleRomanization={},lyricFontSp=10,lyricGapDp=9,lyricBlurDp=2,lyricOffset=0,onLyricFont={},onLyricGap={},onLyricBlur={},onLyricOffset={},onToggleFloating={})
                    AppOverlayHostLayer()
                }
            }
        }
        rule.onNodeWithText("10sp · 10–32",useUnmergedTree=true).assertExists()
        rule.onNodeWithText("距离模糊").assertDoesNotExist()
        rule.onNodeWithText("翻译").assertExists()
        rule.onNodeWithText("罗马音").assertExists()
        rule.onNodeWithText("歌词纠错").assertExists()
        rule.onNodeWithText("悬浮歌词").assertExists()
    }
    @Test fun lyricActionsLight()=lyrics(false)
    @Test fun lyricActionsDark()=lyrics(true)
    @Test fun lyricActionsSelectedLight()=lyrics(false,true)
    @Test fun lyricActionsSelectedDark()=lyrics(true,true)
}
