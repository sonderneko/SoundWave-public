package xyz.cncnlp.soundwave

import xyz.cncnlp.soundwave.ui.common.PressMotion
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.common.rippleGlow
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import xyz.cncnlp.soundwave.ui.common.headerControlShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ===== 头像 UI（自 MainActivity 迁入；AvatarCropDialog private→internal） =====
// 账号系统去除后：本文件只剩头像徽章与裁剪弹层；登录弹层已随云账号整套删除。
// UserAvatarBadge 的 loggedIn 入参语义现为「本机资料存在」（恒 true），仅决定状态点与默认字形。
@Composable
fun DefaultWaveAvatar(modifier: Modifier = Modifier, loggedIn: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(primary.copy(alpha = if (loggedIn) 1f else 0.85f), secondary.copy(alpha = 0.9f)))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            val w = size.width
            val h = size.height
            val path = Path()
            val mid = h * 0.55f
            path.moveTo(0f, mid)
            path.cubicTo(w * 0.18f, mid - h * 0.35f, w * 0.32f, mid + h * 0.35f, w * 0.5f, mid)
            path.cubicTo(w * 0.68f, mid - h * 0.3f, w * 0.82f, mid + h * 0.28f, w, mid)
            drawPath(path, color = Color.White.copy(alpha = 0.95f), style = Stroke(width = 3.2f, cap = StrokeCap.Round))
            val path2 = Path()
            val mid2 = h * 0.72f
            path2.moveTo(0f, mid2)
            path2.cubicTo(w * 0.25f, mid2 - h * 0.18f, w * 0.55f, mid2 + h * 0.16f, w, mid2)
            drawPath(path2, color = Color.White.copy(alpha = 0.55f), style = Stroke(width = 2.2f, cap = StrokeCap.Round))
            // 中心音符点
            drawCircle(Color.White, radius = 2.6f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.28f))
        }
    }
}

/** 设计稿未登录态头像文字（`renderAccountSheet` / 退出登录时 `accountAvatar.textContent='—'`）。 */
internal const val DESIGN_LOGGED_OUT_AVATAR_GLYPH = "—"

/** 用户名首字符（字母转大写）；空用户名回退到未登录字形。 */
internal fun designAvatarInitial(name: String): String =
    name.trim().firstOrNull()?.let { if (it.isLetter()) it.uppercaseChar().toString() else it.toString() }
        ?: DESIGN_LOGGED_OUT_AVATAR_GLYPH

/**
 * 设计稿默认头像 1:1（`.profile{width:42px;height:42px;border-radius:50%;font-weight:800}` +
 * v2 覆盖 `html body .profile{background:var(--ink);color:var(--paper);box-shadow:none}`）：
 * 墨色实心圆、纸色 Manrope ExtraBold 字母，字号沿用 body 16px 与 42px 圆的比例，无投影无描边。
 */
@Composable
internal fun DesignDefaultAvatar(sizeDp: Float, glyph: String, modifier: Modifier = Modifier) {
    val fill = LocalDesignAvatarFill.current
    val ink = fill ?: MaterialTheme.colorScheme.onBackground
    val paper = if (fill != null) { if (fill.luminance() > 0.5f) Color(0xFF11141A) else Color.White } else MaterialTheme.colorScheme.background
    val fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (sizeDp * 16f / 42f).dp.toSp() }
    Box(modifier.size(sizeDp.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
        Text(
            glyph,
            color = paper,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontFamily = xyz.cncnlp.soundwave.ui.common.DesignFonts.Manrope,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

/** 设置账号卡可指定头像底色；文字按亮度反差，null = 默认墨色圆。 */
val LocalDesignAvatarFill = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

/**
 * 当前登录用户名（未登录为空），由 MainActivity 根部提供；页头 / 首页顶栏 / 音乐库页头等未传 initial 的
 * [UserAvatarBadge] 据此在无自定义头像时显示用户名首字母（设计稿 .profile 墨色圆），与设置页账户卡一致。
 */
val LocalAvatarUserName = androidx.compose.runtime.staticCompositionLocalOf { "" }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UserAvatarBadge(
    loggedIn: Boolean,
    avatarPath: String = "",
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    sizeDp: Float = 34f,
    offsetX: androidx.compose.ui.unit.Dp = 0.dp,
    showStatusBar: Boolean = false,
    elevated: Boolean = true,
    initial: String = ""
) {
    androidx.compose.runtime.DisposableEffect(avatarPath) {
        AvatarStore.retainVersion(avatarPath)
        onDispose { AvatarStore.releaseVersion(avatarPath) }
    }
    val avatarInitial = initial.ifBlank { if (loggedIn) LocalAvatarUserName.current else "" }
    val pressHapticView = androidx.compose.ui.platform.LocalView.current
    // 无自定义头像时：未登录一律用设计稿默认头像；已登录且调用方给出用户名首字母时同样用设计稿圆形字母头像。
    val designDefault = avatarPath.isEmpty() && (!loggedIn || avatarInitial.isNotBlank())
    // 头像不再绘制硬边；头像下方状态条与点击区域保持原样。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.offset(x = offsetX)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sizeDp.dp)
                // r674：顶部头像（含设计稿默认头像）统一外侧投影；仅 elevated=false（我的页卡片内头像）不画。
                .then(if (elevated) Modifier.headerControlShadow(CircleShape) else Modifier)
                .clip(CircleShape)
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick?.let { cb -> { xyz.cncnlp.soundwave.ui.common.AppHaptics.longPress(pressHapticView); cb() } })
                    } else Modifier
                )
        ) {
            if (avatarPath.isNotEmpty()) {
                AsyncImage(
                    model = avatarPath,
                    contentDescription = "头像",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else if (designDefault) {
                DesignDefaultAvatar(sizeDp = sizeDp, glyph = if (loggedIn) designAvatarInitial(avatarInitial) else DESIGN_LOGGED_OUT_AVATAR_GLYPH)
            } else {
                DefaultWaveAvatar(Modifier.fillMaxSize(), loggedIn = loggedIn)
            }
        }
        if (showStatusBar) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .width(18.dp)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(if (loggedIn) Color(0xFF34C759.toInt()) else Color.Gray.copy(alpha = 0.45f))
            )
        }
    }
}

@Composable
internal fun AvatarCropDialog(uri: Uri, onCancel: () -> Unit, onConfirm: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    val src = remember(uri) { AvatarStore.decodeUri(ctx, uri) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    var boxPx by remember { mutableIntStateOf(1) }
    // 对话框清扫：AlertDialog 白框改 DesignSheet 输入弹层家族（与改名弹层同规格按钮行）；
    // 裁剪手势、导出位图计算与空图取消语义逐字不变。
    xyz.cncnlp.soundwave.ui.common.DesignSheetDialog(onDismiss = onCancel, scrollContent = true) {
        // 收尾棒 J8 句柄补齐：取消/保存均走关闭句柄退场（保存的落盘与 toast 在调用方即时执行，
        // cropAvatarUri 的清除经句柄走 onCancel；调用方 onConfirm 不再自行清状态，配对在 MainActivity）。
        val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss(onCancel)
        xyz.cncnlp.soundwave.ui.common.DesignSheetHeader("Profile", "编辑头像")
        Spacer(Modifier.height(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("双指缩放，拖动调整", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(Color(0xFF1A1A22.toInt()))
                    .onSizeChanged { boxPx = it.width.coerceAtLeast(1) }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            offX += pan.x
                            offY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (src != null) {
                    Image(
                        bitmap = src.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offX
                            translationY = offY
                        }
                    )
                } else Text("无法读取图片", color = Muted)
            }
        }
        xyz.cncnlp.soundwave.ui.common.SheetActionButtons(
            secondary = "取消", onSecondary = dismiss,
            primary = "保存",
            onPrimary = {
                if (src == null) {
                    dismiss()
                } else {
                    val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(out)
                    val k = 512f / boxPx.coerceAtLeast(1)
                    val m = Matrix()
                    val srcW = src.width.toFloat()
                    val srcH = src.height.toFloat()
                    val fill = maxOf(512f / srcW, 512f / srcH)
                    m.postTranslate(-srcW / 2f, -srcH / 2f)
                    m.postScale(fill * scale, fill * scale)
                    m.postTranslate(256f + offX * k, 256f + offY * k)
                    canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    onConfirm(out)
                    dismiss()
                }
            }
        )
    }
}
