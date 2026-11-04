package xyz.cncnlp.soundwave.ui.common

import xyz.cncnlp.soundwave.ThemeTokens.SheetFrost as SheetFrostTokens

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.player.CoverToneCache

/**
 * r657：透明磨砂背景（v3 #16 / v4 / 用户最终补充）。
 *
 * 三个独立保存的开关（页面/底栏默认关闭；封面取色默认开启，但页面磨砂关闭时不生效）：
 *  - [pageFrosted]「透明磨砂背景」：页面与默认播放器底层改为 当前封面模糊 + 取色光晕 + 纸色 / 墨色罩；
 *  - [coverTint]「磨砂随封面取色」：磨砂层的光晕取自当前封面（关闭时用默认珊瑚 / 蓝光晕）；磨砂关闭时它不生效（设置里置灰）；
 *  - [dockFrosted]「底栏与迷你条磨砂」：只作用于底栏 / 胶囊组 / 搜索圆钮 / 迷你条，与页面磨砂互不影响。
 *
 * 底栏与迷你条永远不透明到看不清：关闭时为实色表面（浅色纯白 / 深色 App 表面色），
 * 打开时为半透明磨砂（alpha 不低于 [DOCK_FROSTED_MIN_ALPHA]，可随显式页面封面取色），页面开关不改变底栏的实色/磨砂分支。
 */
object FrostedPrefs {
    const val KEY_PAGE = "frosted_page_bg"
    const val KEY_TINT = "frosted_cover_tint"
    const val KEY_DOCK = "frosted_dock"

    var pageFrosted by mutableStateOf(false)
    var themeMode by mutableStateOf("light")
    val pageActive: Boolean get() = pageFrosted && pageFrostAllowed(themeMode)
    var coverTint by mutableStateOf(true)
    var dockFrosted by mutableStateOf(false)
    /** 当前播放封面（由 AppRoot 同步）；磨砂层与取色从这里读。 */
    var cover by mutableStateOf("")
    /** 当前封面的取色（后台 64px 小图聚类，见 CoverToneCache）。 */
    var tone by mutableStateOf<Color?>(null)

    /** 切换封面时先复用新封面的缓存色，否则清空旧色，避免旧色暂留在新封面上。 */
    internal fun selectCoverForTone(nextCover: String) {
        if (cover == nextCover) return
        cover = nextCover
        tone = if (nextCover.isBlank()) null else CoverToneCache.cached(nextCover)?.top
    }

    /** 异步取色结果只在目标封面仍是当前封面时提交。 */
    internal fun applyToneForCover(expectedCover: String, sampledTone: Color?): Boolean {
        if (cover != expectedCover) return false
        tone = sampledTone
        return true
    }

    private var loaded = false

    fun load(prefs: SharedPreferences) {
        if (loaded) return
        loaded = true
        pageFrosted = prefs.getBoolean(KEY_PAGE, false)
        coverTint = prefs.getBoolean(KEY_TINT, true)
        dockFrosted = prefs.getBoolean(KEY_DOCK, false)
    }

    fun setPage(prefs: SharedPreferences, on: Boolean) { pageFrosted = on; prefs.edit().putBoolean(KEY_PAGE, on).apply() }
    fun setTint(prefs: SharedPreferences, on: Boolean) { coverTint = on; prefs.edit().putBoolean(KEY_TINT, on).apply() }
    fun setDock(prefs: SharedPreferences, on: Boolean) { dockFrosted = on; prefs.edit().putBoolean(KEY_DOCK, on).apply() }

    internal fun resetForTest(page: Boolean = false, tint: Boolean = true, dock: Boolean = false) {
        loaded = true; pageFrosted = page; coverTint = tint; dockFrosted = dock; tone = null; cover = ""
    }
}

/** 磨砂底栏 / 迷你条的最低不透明度：保证背后列表永远不会直接透出（回归：v4 胶囊组完全透明）。 */
internal fun pageFrostAllowed(themeMode: String): Boolean = xyz.cncnlp.soundwave.normalizeThemeMode(themeMode) == "light"

const val DOCK_FROSTED_MIN_ALPHA = 0.80f

/**
 * 底栏、胶囊组、搜索圆钮、迷你条的填充色（纯函数，单测覆盖）。
 * 关闭 = 不透明实色 [base]；打开 = 半透明磨砂，可混入封面取色，alpha ≥ [DOCK_FROSTED_MIN_ALPHA]。
 * 本纯函数仅接收已解析的可选 tint；Compose 调用层以 pageFrosted && coverTint 解析 tint，独立 dock 开关仍决定是否磨砂。
 */
fun dockChromeFill(base: Color, dockFrosted: Boolean, tint: Color? = null, dark: Boolean = false): Color {
    val opaque = base.copy(alpha = 1f)
    if (!dockFrosted) return opaque
    val tinted = if (tint != null) lerp(opaque, tint.copy(alpha = 1f), if (dark) 0.14f else 0.08f) else opaque
    return tinted.copy(alpha = if (dark) 0.84f else 0.82f).let { if (it.alpha < DOCK_FROSTED_MIN_ALPHA) it.copy(alpha = DOCK_FROSTED_MIN_ALPHA) else it }
}

/** Compose 侧：按当前开关解析底栏 / 迷你条填充色。 */
@Composable
fun dockChromeFill(base: Color, dark: Boolean): Color =
    dockChromeFill(base, FrostedPrefs.dockFrosted, dockCoverTint(FrostedPrefs.pageFrosted, FrostedPrefs.coverTint, FrostedPrefs.tone), dark)

/** A disabled page-tint control cannot silently colour an independently enabled dock. */
internal fun dockCoverTint(pageFrosted: Boolean, coverTint: Boolean, tone: Color?): Color? =
    tone.takeIf { pageFrosted && coverTint }

/** 磨砂背景下吸顶条 / 浮层用的半透明页面色（关闭时原样返回）。 */
@Composable
fun frostedPageTint(background: Color): Color =
    if (FrostedPrefs.pageActive) background.copy(alpha = 0.86f) else background

/** 后台为当前封面取色（只取一次，缓存在 CoverToneCache）。 */
@Composable
fun FrostedToneSync() {
    val ctx = LocalContext.current
    val cover = FrostedPrefs.cover
    LaunchedEffect(cover) {
        val sampledTone = if (cover.isBlank()) null else CoverToneCache.tonesFor(ctx, cover)?.top
        FrostedPrefs.applyToneForCover(cover, sampledTone)
    }
}

/**
 * 页面磨砂层：仅在「透明磨砂背景」打开时绘制（不透明：最底是纸色 / 墨色，所以叠放的路由不会互相透出）。
 * Android 12+ 叠一层强模糊封面；更低版本只画取色光晕（不露出清晰放大的封面）。
 */
@Composable
fun FrostedPageBackdrop(modifier: Modifier = Modifier, dark: Boolean, force: Boolean = false) {
    if (!pageFrostAllowed(FrostedPrefs.themeMode) || (!FrostedPrefs.pageActive && !force)) return
    val paper = Color.White
    val tint = FrostedPrefs.coverTint
    val cover = FrostedPrefs.cover
    val tone = FrostedPrefs.tone
    val glowA = if (tint && tone != null) tone else SoundwaveDesignPalette.Accent
    val glowB = if (tint && tone != null) lerp(tone, SoundwaveDesignPalette.Accent, 0.35f) else SoundwaveDesignPalette.Accent
    val ctx = LocalContext.current
    Box(modifier.background(paper)) {
        if (tint && cover.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(
                model = CacheManager.coverModel(ctx, cover) ?: cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f; scaleX = 1.25f; scaleY = 1.25f }.blur(64.dp)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(glowA.copy(alpha = 0.14f), Color.Transparent), center = Offset(0f, 0f), radius = 1400f)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(glowB.copy(alpha = 0.10f), Color.Transparent), center = Offset(Float.POSITIVE_INFINITY, 1600f), radius = 1300f)
            )
        )
        // 纸色 / 墨色半透明罩：保证正文对比度。
        Box(Modifier.fillMaxSize().background(paper.copy(alpha = 0.72f)))
    }
}

/** AppRoot 一次性接线：读取三个磨砂开关、同步当前封面并后台取色（让 MainActivity 只多一行）。 */
@Composable
fun FrostedAppRootSync(prefs: SharedPreferences, cover: String) {
    FrostedPrefs.load(prefs)
    androidx.compose.runtime.SideEffect {
        FrostedPrefs.themeMode = xyz.cncnlp.soundwave.normalizeThemeMode(prefs.getString("theme_mode", "light") ?: "light")
        FrostedPrefs.selectCoverForTone(cover)
    }
    FrostedToneSync()
}

/* ───────── r657 第二轮：「透明磨砂背景」打开时的统一表面规则 ───────── */

/** 磨砂开时分隔线全部隐藏（列表行之间、设置行、页头分隔线、抽屉行分隔线）；关时照常。 */
fun frostHidesDividers(pageFrosted: Boolean = FrostedPrefs.pageActive): Boolean = pageFrosted

/** Sheet surface: enabled uses the current near-opaque surface token, disabled uses opaque base. */
fun frostedSheetFill(base: Color, pageFrosted: Boolean = FrostedPrefs.pageActive, sdk: Int = Build.VERSION.SDK_INT): Color =
    // r658d（用户 10-20：对话框只有透明度、太透）：仅在系统真的开启跨窗口模糊时才用半透明（78% + 窗口背后 36dp 模糊），
    // 否则（不支持 / 省电模式关闭模糊）近乎不透明 96%，不再让下层文字透出来。
    if (!pageFrosted) base.copy(alpha = 1f)
    // r658h：底部抽屉改为应用内覆盖层（不再是独立窗口，没有 blurBehind），统一 96% 近不透明。
    else base.copy(alpha = SheetFrostTokens.SurfaceAlpha)

/** 系统跨窗口模糊是否可用（API 31+，受开发者选项 / 省电模式影响）；由 [CrossWindowBlur.install] 监听更新。 */
object CrossWindowBlur {
    var enabled by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var installed = false
    fun install(context: android.content.Context) {
        if (installed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        installed = true
        // r658e：整体 try（10-20 真机启动闪退排查，任何异常都不影响启动）；用 Activity 自身的 WindowManager（UI Context）。
        try {
            val wm = context.getSystemService(android.view.WindowManager::class.java) ?: return
            enabled = try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false }
            wm.addCrossWindowBlurEnabledListener(context.mainExecutor) { on -> enabled = on == true }
        } catch (_: Throwable) {}
    }
}

/** Window sheet blur radius in dp (API 31+). Does not control independent dock frost. */
fun frostedSheetBlurDp(pageFrosted: Boolean = FrostedPrefs.pageActive): Int = if (pageFrosted) SheetFrostTokens.WindowBlurDp else SheetFrostTokens.DisabledWindowBlurDp

/**
 * ACCEPT6 dialogfrost：Dialog 窗口回退宿主的 blurBehind 半径定版常值——表的磨砂常开、
 * 与页面磨砂开关解耦，与 haze 路径共用 [SheetFrostTokens.GlassBlurDp]。
 */
fun sheetWindowBlurDp(): Int = SheetFrostTokens.GlassBlurDp

/** 1dp 分隔线：磨砂开时不绘制（保留占位高度，避免布局跳动）。 */
@Composable
fun FrostDivider(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(if (frostHidesDividers()) Color.Transparent else color))
}

/** 分隔线颜色：磨砂开时透明（只改颜色，不改布局）。 */
fun frostLine(color: Color): Color = if (frostHidesDividers()) Color.Transparent else color
