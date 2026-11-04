package xyz.cncnlp.soundwave

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import kotlin.math.abs
import xyz.cncnlp.soundwave.ui.notice.AppNotice

/**
 * 悬浮歌词（仅应用在后台/其它界面时显示）：
 * - 文字左对齐（J 波对齐设计稿 .flyric；此前居中）
 * - 拖动改位置；点按循环文字颜色；长按锁定
 * - 锁定后禁止拖动但保留触摸；再次长按可解锁
 * - 应用回到前台立即 removeView，减轻系统「正在其他应用上层显示」提示
 */
object LyricFloat {
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var container: LinearLayout? = null
    /** J 波：锁定态可见角标（设计稿 .flyric 右上珊瑚圆 + 闪电字形），仅锁定时显示。 */
    private var lockBadge: View? = null
    /** r656：单个 Canvas 视图绘制主句 + 下一句（固定尺寸、逐帧插值的卡拉 OK 填充、换句滑动过渡）。 */
    private var lineView: LyricFloatLineView? = null
    private var lastNextStartMs: Long = 0L
    private var lastPlaying: Boolean = true
    private var params: WindowManager.LayoutParams? = null
    /** 锁定态显示窗完全不接触摸；交互窗只覆盖实际歌词框，保证其它区域真穿透。 */
    private var interactionRoot: View? = null
    private var interactionParams: WindowManager.LayoutParams? = null
    private var lastText: String = ""
    private var lastSub: String = ""
    private var lastLine: LyricLine? = null
    private var lastPosMs: Long = 0L
    private var locked: Boolean = false
    private var appCtx: Context? = null
    /** 用户开关（设置/通知「词」打开） */
    private var userEnabled: Boolean = false
    /** 应用是否在前台；前台强制不挂悬浮窗 */
    @Volatile private var appInForeground: Boolean = true
    private val mainHandler = Handler(Looper.getMainLooper())

    val COLOR_PRESETS: IntArray = intArrayOf(
        AColor.rgb(42, 120, 255), // 蓝（默认）
        AColor.WHITE,
        AColor.rgb(255, 224, 102),
        AColor.rgb(120, 220, 255),
        AColor.rgb(255, 140, 180),
        AColor.rgb(140, 255, 170),
        AColor.rgb(255, 170, 100),
        AColor.rgb(200, 170, 255)
    )

    /** 默认悬浮歌词颜色：蓝（用户要求由青色改为蓝色）。 */
    private val DEFAULT_LYRIC_COLOR = AColor.rgb(42, 120, 255)

    data class Style(
        val fontSp: Float = 16f,
        val textAlpha: Float = 0.96f,
        val bgAlpha: Float = 0.35f,
        val widthDp: Int = 0,
        val playedColor: Int = AColor.WHITE,
        val dimColor: Int = AColor.argb(160, 255, 255, 255),
        val maxLines: Int = 1,
        val locked: Boolean = false
    )

    fun loadStyle(ctx: Context): Style {
        val p = xyz.cncnlp.soundwave.ContextPreferences.open(ctx, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val legacy = if (p.contains("lyric_float_alpha") && !p.contains("lyric_float_text_alpha")) {
            p.getFloat("lyric_float_alpha", 0.92f)
        } else null
        val textA = p.getFloat("lyric_float_text_alpha", legacy ?: 1f).coerceIn(0.35f, 1f)
        val bgA = p.getFloat(
            "lyric_float_bg_alpha",
            if (legacy != null) (legacy * 0.4f).coerceIn(0f, 0.85f) else 0f
        ).coerceIn(0f, 0.85f)
        val color = p.getInt("lyric_float_color", DEFAULT_LYRIC_COLOR)
        // 当前/已唱文字使用用户歌词色；下一句与尚未唱到的词固定白色。
        val dimDefault = AColor.WHITE
        return Style(
            fontSp = p.getFloat("lyric_float_sp", 16f).coerceIn(12f, 32f),
            textAlpha = textA,
            bgAlpha = bgA,
            widthDp = p.getInt("lyric_float_width_dp", 0).coerceIn(0, 420),
            playedColor = color,
            dimColor = p.getInt("lyric_float_dim", dimDefault),
            maxLines = p.getInt("lyric_float_lines", 2).coerceIn(1, 2),
            locked = p.getBoolean("lyric_float_locked", false)
        )
    }

    fun saveStyle(ctx: Context, style: Style) {
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).edit()
            .putFloat("lyric_float_sp", style.fontSp)
            .putFloat("lyric_float_text_alpha", style.textAlpha)
            .putFloat("lyric_float_bg_alpha", style.bgAlpha)
            .putFloat("lyric_float_alpha", style.textAlpha)
            .putInt("lyric_float_width_dp", style.widthDp)
            .putInt("lyric_float_color", style.playedColor)
            .putInt("lyric_float_dim", style.dimColor)
            .putInt("lyric_float_lines", style.maxLines)
            .putBoolean("lyric_float_locked", style.locked)
            .apply()
    }

    /** 与 PlayerLyricActionsSheet / MainActivity 相同的系统悬浮窗授权页 Intent（仓内无共享封装，此处按同一写法）。 */
    private fun openOverlayPermissionSettings(app: Context) {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
    fun hasPermission(ctx: Context): Boolean = canDraw(ctx)
    fun isVisible(): Boolean = root != null
    fun isUserEnabled(ctx: Context): Boolean =
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx.applicationContext, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).getBoolean("lyric_float", false)

    fun isLocked(ctx: Context): Boolean =
        xyz.cncnlp.soundwave.ContextPreferences.open(ctx.applicationContext, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).getBoolean("lyric_float_locked", false)

    /** Activity onResume/onPause 调用：前台隐藏窗体，后台再挂上。 */
    fun setAppForeground(ctx: Context, foreground: Boolean) {
        appInForeground = foreground
        val app = ctx.applicationContext
        appCtx = app
        userEnabled = isUserEnabled(app)
        mainHandler.post {
            if (foreground) {
                // 立刻拆窗，系统「上层显示」提示会随窗口移除而消失
                detachWindowOnly()
            } else if (userEnabled && canDraw(app)) {
                ensureWindow(app)
                if (lastText.isNotBlank()) {
                    lineView?.setStatic(lastText, lastSub)
                }
            }
        }
    }

    fun show(ctx: Context, text: String, sub: String = "") {
        val app = ctx.applicationContext
        appCtx = app
        userEnabled = true
        xyz.cncnlp.soundwave.ContextPreferences.open(app, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).edit().putBoolean("lyric_float", true).apply()
        if (!canDraw(app)) {
            AppNotice.attach(app)
            AppNotice.banner("请先开启悬浮窗权限", actionLabel = "去开启", onAction = { openOverlayPermissionSettings(app) }, onClick = { openOverlayPermissionSettings(app) })
            return
        }
        lastText = text.ifBlank { "♪ SoundWave" }
        lastSub = sub
        // 应用内不挂窗
        if (appInForeground || isAppProcessForeground(app)) {
            detachWindowOnly()
            return
        }
        ensureWindow(app)
        applyText(lastText, lastSub)
    }

    fun update(text: String, sub: String = "") {
        val t = text.ifBlank { "♪ SoundWave" }
        lastText = t
        lastSub = sub
        val app = appCtx ?: return
        if (!userEnabled && !isUserEnabled(app)) return
        if (appInForeground || isAppProcessForeground(app)) {
            if (root != null) detachWindowOnly()
            return
        }
        if (root == null) {
            if (canDraw(app)) ensureWindow(app) else return
        }
        applyText(t, sub)
    }

    /** 仅刷新文案，不强制建窗。 */
    fun tick(ctx: Context, text: String, sub: String = "") {
        val app = ctx.applicationContext
        appCtx = app
        userEnabled = isUserEnabled(app)
        if (!userEnabled) {
            if (root != null) hide()
            return
        }
        lastText = text.ifBlank { lastText.ifBlank { "♪ SoundWave" } }
        lastSub = sub
        if (appInForeground || isAppProcessForeground(app)) {
            detachWindowOnly()
            return
        }
        if (!canDraw(app)) return
        ensureWindow(app)
        applyText(lastText, lastSub)
    }

    fun applyLive(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (root == null) return
        applyStyle(loadStyle(app))
    }

    /**
     * 词级悬浮歌词：与沉浸歌词一致，有词轴时逐词推进着色；
     * 无词轴回退整句。调用方（MainActivity 歌词循环）传入解析后的 LyricLine 与播放位置。
     */
    fun tickLine(ctx: Context, line: LyricLine?, sub: String = "", posMs: Long = 0L, nextStartMs: Long = 0L, playing: Boolean = true) {
        val app = ctx.applicationContext
        appCtx = app
        userEnabled = isUserEnabled(app)
        if (!userEnabled) {
            if (root != null) hide()
            return
        }
        lastText = line?.text.orEmpty().ifBlank { lastText.ifBlank { "♪ SoundWave" } }
        lastSub = sub
        lastLine = line
        lastPosMs = posMs
        lastNextStartMs = nextStartMs
        lastPlaying = playing
        if (appInForeground || isAppProcessForeground(app)) {
            detachWindowOnly()
            return
        }
        if (!canDraw(app)) return
        ensureWindow(app)
        applyLineText(line, sub, posMs)
    }

    fun hide() {
        userEnabled = false
        appCtx?.let { app ->
            ContextPreferences.open(app, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
                .edit().putBoolean("lyric_float", false).putBoolean("lyric_float_locked", false).apply()
        }
        locked = false
        detachWindowOnly()
        lastText = ""
        lastSub = ""
        // 通知栏动作本身也要立即反映“关闭”，不能等下一次进度 tick 才换图标。
        try { PlayerService.refreshLyricNotif() } catch (_: Exception) {}
    }

    /** 关闭开关但保留 lastText；或仅拆窗。 */
    fun detachWindowOnly() {
        try {
            val w = wm
            interactionRoot?.let { hit -> if (w != null) w.removeView(hit) }
            val r = root
            if (w != null && r != null) w.removeView(r)
        } catch (_: Exception) {
        }
        wm = null
        root = null
        container = null
        lineView = null
        params = null
        interactionRoot = null
        interactionParams = null
    }

    fun isShowing(): Boolean = root != null

    fun setLocked(ctx: Context, value: Boolean, toast: Boolean = true) {
        locked = value
        syncLockBadge()
        val app = ctx.applicationContext
        val style = loadStyle(app).copy(locked = value)
        saveStyle(app, style)
        val p = params
        val w = wm
        val r = root
        if (p != null && w != null && r != null) {
            applyTouchFlags(p)
            try { w.updateViewLayout(r, p) } catch (_: Exception) {}
        }
        syncInteractionWindow(app)
        if (toast) {
            AppNotice.attach(app)
            AppNotice.toast(if (value) "悬浮歌词已锁定" else "悬浮歌词已解锁", AppNotice.Kind.NEUTRAL)
        }
        // 刷新通知栏「词」三态图标
        try { PlayerService.refreshLyricNotif() } catch (_: Exception) {}
    }

    fun cycleColor(ctx: Context) {
        val app = ctx.applicationContext
        val st = loadStyle(app)
        val idx = COLOR_PRESETS.indexOfFirst { it == st.playedColor }.let { if (it < 0) 0 else it }
        val next = COLOR_PRESETS[(idx + 1) % COLOR_PRESETS.size]
        val dim = AColor.WHITE
        saveStyle(app, st.copy(playedColor = next, dimColor = dim))
        applyLive(app)
    }

    /**
     * 通知栏「词」逻辑：关闭→开启且未锁定→锁定→关闭。
     * 长按歌词独立切换锁定，不改变启用状态。
     */
    fun handleNotifWord(ctx: Context, sampleLine: String = "", sampleSub: String = ""): String {
        val app = ctx.applicationContext
        appCtx = app
        val enabled = isUserEnabled(app)
        val lockedNow = isLocked(app)
        return when {
            enabled && lockedNow -> {
                hide()
                "悬浮歌词已关闭"
            }
            enabled && !lockedNow -> {
                setLocked(app, true, toast = false)
                try { PlayerService.refreshLyricNotif() } catch (_: Exception) {}
                "悬浮歌词已锁定"
            }
            else -> {
                if (!canDraw(app)) {
                    "请先开启悬浮窗权限"
                } else {
                    userEnabled = true
                    locked = false
                    xyz.cncnlp.soundwave.ContextPreferences.open(app, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).edit().putBoolean("lyric_float", true).putBoolean("lyric_float_locked", false).apply()
                    lastText = sampleLine.ifBlank { lastText.ifBlank { "♪ SoundWave" } }
                    lastSub = sampleSub
                    if (!appInForeground && !isAppProcessForeground(app)) {
                        ensureWindow(app)
                        applyText(lastText, lastSub)
                    }
                    try { PlayerService.refreshLyricNotif() } catch (_: Exception) {}
                    "悬浮歌词已开（回到桌面后显示）"
                }
            }
        }
    }

    fun toggleEnabled(ctx: Context, enable: Boolean, sampleLine: String = "", sampleSub: String = "") {
        val app = ctx.applicationContext
        appCtx = app
        AppNotice.attach(app)
        xyz.cncnlp.soundwave.ContextPreferences.open(app, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime()).edit().putBoolean("lyric_float", enable).apply()
        userEnabled = enable
        if (enable) {
            if (!canDraw(app)) {
                AppNotice.banner("请先开启悬浮窗权限", actionLabel = "去开启", onAction = { openOverlayPermissionSettings(app) }, onClick = { openOverlayPermissionSettings(app) })
                return
            }
            lastText = sampleLine.ifBlank { lastText.ifBlank { "♪ SoundWave" } }
            lastSub = sampleSub
            if (appInForeground || isAppProcessForeground(app)) {
                detachWindowOnly()
                AppNotice.toast("已开启：切到其它应用后显示", AppNotice.Kind.SUCCESS)
            } else {
                ensureWindow(app)
                applyText(lastText, lastSub)
            }
        } else {
            hide()
        }
    }

    private fun isAppProcessForeground(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val procs = am.runningAppProcesses ?: return appInForeground
            val pkg = ctx.packageName
            procs.any {
                it.processName == pkg &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        } catch (_: Exception) {
            appInForeground
        }
    }

    private fun applyText(text: String, sub: String) {
        lineView?.setStatic(text, sub)
        if (locked) appCtx?.let(::syncInteractionWindow)
    }

    /**
     * r656：卡拉 OK 着色交给 [LyricFloatLineView]：底字白色、已唱部分按词轴（无词轴按整句时长）裁剪成用户歌词色，
     * 位置在两次服务 tick 之间逐帧插值；不再用 Span 给当前词加粗（会改变宽度导致居中文本左右跳）。
     */
    private fun applyLineText(line: LyricLine?, sub: String, posMs: Long) {
        val view = lineView ?: return
        val app = appCtx ?: return
        view.setLine(line, sub, posMs, lastNextStartMs, lastPlaying)
        if (locked) syncInteractionWindow(app)
    }

    private fun applyTouchFlags(p: WindowManager.LayoutParams) {
        // 锁定时显示窗 FLAG_NOT_TOUCHABLE，所有不在独立小交互窗内的点击都由下层 App 接收。
        p.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
    }

    private fun bgColor(style: Style): Int {
        val a = (style.bgAlpha * 255f).toInt().coerceIn(0, 220)
        return AColor.argb(a, 12, 12, 18)
    }

    /**
     * J 波（对齐设计稿 .flyric 容器）：圆角 14dp + 白 18% 内描边（此前为平涂方角无描边）。
     * 底色仍由用户背景透明度决定（[bgColor]），只换容器形态。
     */
    private fun bgDrawable(ctx: Context, style: Style): android.graphics.drawable.GradientDrawable {
        val density = ctx.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(bgColor(style))
            setStroke((1f * density).toInt().coerceAtLeast(1), AColor.argb(46, 255, 255, 255))
        }
    }

    private fun syncLockBadge() {
        lockBadge?.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun widthPx(ctx: Context, style: Style): Int {
        val density = ctx.resources.displayMetrics.density
        val screen = ctx.resources.displayMetrics.widthPixels
        return if (style.widthDp <= 0) {
            // J 波（对齐设计稿 .flyric）：默认宽改为上限 330dp（左锚定），不再屏宽 86% 居中。
            minOf((330 * density).toInt(), screen - (46 * density).toInt()).coerceAtLeast((160 * density).toInt())
        } else {
            (style.widthDp * density).toInt().coerceAtLeast((120 * density).toInt())
        }
    }

    private fun applyStyle(style: Style) {
        locked = style.locked
        val ctx = appCtx
        lineView?.applyStyle(style.fontSp, style.playedColor, style.textAlpha, style.maxLines >= 2)
        if (ctx != null) container?.background = bgDrawable(ctx, style)
        syncLockBadge()
        params?.let { p ->
            applyTouchFlags(p)
            if (ctx != null) {
                // r656：窗口宽度固定为样式宽度，不随每句文本宽度变化（WRAP_CONTENT 会让窗口左右跳）。
                p.width = widthPx(ctx, style)
                // J 波：锚定改左上（设计稿 .flyric left:30/top:120），不再水平居中。
                p.gravity = Gravity.TOP or Gravity.START
            }
        }
        try {
            val w = wm; val r = root; val p = params
            if (w != null && r != null && p != null) w.updateViewLayout(r, p)
        } catch (_: Exception) {}
    }

    /** 锁定时以第二个透明、小尺寸窗口承接长按解锁；显示窗自身完全可穿透。 */
    private fun syncInteractionWindow(ctx: Context) {
        val w = wm ?: return
        val display = root
        val source = params
        if (!locked || display == null || source == null) {
            interactionRoot?.let { try { w.removeView(it) } catch (_: Exception) {} }
            interactionRoot = null
            interactionParams = null
            return
        }
        display.post {
            if (!locked || root !== display) return@post
            interactionRoot?.let { existing ->
                interactionParams?.let { ip ->
                    ip.width = display.width.coerceAtLeast(1)
                    ip.height = display.height.coerceAtLeast(1)
                    ip.gravity = source.gravity; ip.x = source.x; ip.y = source.y
                    try { w.updateViewLayout(existing, ip) } catch (_: Exception) {}
                }
                return@post
            }
            val hit = FrameLayout(ctx).apply {
                setBackgroundColor(AColor.TRANSPARENT)
                setOnTouchListener(LyricFloatUnlockTouch(this) { if (locked) setLocked(ctx, false) })
            }
            val ip = WindowManager.LayoutParams(
                display.width.coerceAtLeast(1), display.height.coerceAtLeast(1),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = source.gravity; x = source.x; y = source.y }
            try {
                w.addView(hit, ip)
                interactionRoot = hit
                interactionParams = ip
            } catch (_: Exception) {}
        }
    }

    private fun ensureWindow(ctx: Context) {
        val style = loadStyle(ctx)
        locked = style.locked
        if (root != null) {
            applyStyle(style)
            syncInteractionWindow(ctx)
            return
        }
        create(ctx, style)
    }

    private fun create(ctx: Context, style: Style) {
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        locked = style.locked
        val density = ctx.resources.displayMetrics.density
        // J 波（设计稿 .flyric 内距 8×14）：横距 16→14dp。
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            background = bgDrawable(ctx, style)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val line = LyricFloatLineView(ctx).apply {
            applyStyle(style.fontSp, style.playedColor, style.textAlpha, style.maxLines >= 2)
            setStatic(lastText.ifBlank { "♪ SoundWave" }, lastSub)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        box.addView(line)
        container = box
        lineView = line

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val prefs = xyz.cncnlp.soundwave.ContextPreferences.open(ctx, "soundwave", 0, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        val screenW = ctx.resources.displayMetrics.widthPixels
        val p = WindowManager.LayoutParams(
            // r656：固定为样式宽度（默认约屏宽 86%），换句 / 唱词时窗口尺寸恒定，不再抖动。
            widthPx(ctx, style),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            0,
            PixelFormat.TRANSLUCENT
        ).apply {
            // J 波（设计稿 .flyric 左锚定 left:30 / top:120）：gravity 改左上，x/y 为左上偏移。
            // 位置键换 _v2：旧键存的是「相对屏幕中心的偏移」，与新锚定语义不兼容，
            // 旧位置一次性作废回到稿默认位，用户拖动后按新键保存。
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("lyric_float_x_v2", (30 * density).toInt())
            y = prefs.getInt("lyric_float_y_v2", (120 * density).toInt())
            applyTouchFlags(this)
        }
        p.title = ""
        // 降低部分 ROM 对上层窗的持续点名提示（仍可能显示，但前台已拆窗）
        try { p.setTitle("") } catch (_: Exception) {}
        params = p

        val frame = FrameLayout(ctx)
        // J 波：frame 顶部/右侧留 6dp 透明带，让锁定角标能「挂」在歌词框右上角外侧（窗口内绘制，
        // 不越窗）。角标仅锁定时可见（syncLockBadge），未锁定时该带为空透明。
        frame.setPadding(0, (6 * density).toInt(), (6 * density).toInt(), 0)
        frame.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val badge = FloatLockBadgeView(ctx)
        badge.layoutParams = FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt(), Gravity.TOP or Gravity.END)
        badge.visibility = if (locked) View.VISIBLE else View.GONE
        frame.addView(badge)
        lockBadge = badge
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longArmed = false
        var didLongLock = false
        // 锁定态：只有落在歌词文字框内才接收触摸；框外点击必须穿透到下层应用。
        var inBox = false
        frame.setOnTouchListener { _, ev ->
            val lp = params ?: return@setOnTouchListener false
            val hitBox = runCatching {
                val r = android.graphics.Rect()
                box.getHitRect(r)
                ev.x >= r.left && ev.x <= r.right && ev.y >= r.top && ev.y <= r.bottom
            }.getOrDefault(false)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inBox = hitBox
                    if (locked && !inBox) return@setOnTouchListener false
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longArmed = true
                    didLongLock = false
                    frame.postDelayed({
                        if (longArmed && !moved) {
                            didLongLock = true
                            // 长按：锁定 ↔ 解锁切换
                            setLocked(ctx, !locked)
                        }
                    }, 480L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (locked && !inBox) return@setOnTouchListener false
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > 12) {
                        moved = true
                        longArmed = false
                    }
                    // 锁定状态禁拖动
                    if (moved && !locked) {
                        // J 波：左锚定下 x 是相对屏幕左缘的偏移（拖拽数学与旧居中锚定同形）。
                        lp.x = startX + dx
                        lp.y = (startY + dy).coerceAtLeast(0)
                        try { w.updateViewLayout(frame, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (locked && !inBox) return@setOnTouchListener false
                    longArmed = false
                    if (moved) {
                        if (!locked) {
                            prefs.edit().putInt("lyric_float_x_v2", lp.x).putInt("lyric_float_y_v2", lp.y).apply()
                        }
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP && !didLongLock && !locked) {
                        // 点按：循环文字颜色（锁定状态不换色）
                        cycleColor(ctx)
                    }
                    true
                }
                else -> false
            }
        }
        root = frame
        try {
            w.addView(frame, p)
            if (locked) frame.post { syncInteractionWindow(ctx) }
        } catch (e: Exception) {
            detachWindowOnly()
            AppNotice.attach(ctx.applicationContext)
            AppNotice.toast("悬浮窗打开失败", AppNotice.Kind.FAILURE)
        }
    }
}

/** J 波：悬浮歌词锁定角标——18dp 珊瑚实心圆 + 白色闪电字形（路径绘制，不依赖字体/emoji 渲染）。 */
private class FloatLockBadgeView(ctx: Context) : View(ctx) {
    private val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = AColor.rgb(255, 92, 69) // SoundwaveDesignPalette.Accent #FF5C45
    }
    private val boltPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = AColor.WHITE
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = minOf(width, height) / 2f
        canvas.drawCircle(width / 2f, height / 2f, r, circlePaint)
        // 闪电字形：24 单位路径（M13 2 L4.5 13.5 H10 L8.5 22 L19 9.5 H13.5 Z），等比缩放入圆内。
        val s = (r * 2f) / 24f
        val path = android.graphics.Path().apply {
            moveTo(13f, 2f); lineTo(4.5f, 13.5f); lineTo(10f, 13.5f); lineTo(8.5f, 22f); lineTo(19f, 9.5f); lineTo(13.5f, 9.5f); close()
        }
        val m = android.graphics.Matrix().apply { setScale(s, s) }
        path.transform(m)
        canvas.drawPath(path, boltPaint)
    }
}
