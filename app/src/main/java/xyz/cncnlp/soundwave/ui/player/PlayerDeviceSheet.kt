package xyz.cncnlp.soundwave.ui.player

import xyz.cncnlp.soundwave.ui.common.DesignChromeGlyphs
import android.media.MediaRouter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Tv
import xyz.cncnlp.soundwave.ui.common.VinylDiscSpinner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.DesignSheetItemHeader
import xyz.cncnlp.soundwave.ui.common.designLineColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.DlnaDevice

internal const val PLAYER_DEVICE_MIN_TOUCH_TARGET_DP = 48

internal enum class PlayerDeviceGlyph {
    LOCAL_PHONE,
    SYSTEM_CONNECTOR,
    BLUETOOTH,
    SPEAKER,
    TELEVISION,
    REMOTE_OUTPUT,
    DLNA
}

internal enum class PlayerDeviceColorRole {
    PRIMARY,
    SECONDARY,
    ON_SURFACE_VARIANT
}

internal data class PlayerDeviceOptionStyle(
    val glyph: PlayerDeviceGlyph,
    val colorRole: PlayerDeviceColorRole
)

internal fun playerLocalDeviceOptionStyle(isCurrent: Boolean) = PlayerDeviceOptionStyle(
    glyph = PlayerDeviceGlyph.LOCAL_PHONE,
    colorRole = if (isCurrent) PlayerDeviceColorRole.PRIMARY else PlayerDeviceColorRole.ON_SURFACE_VARIANT
)

internal fun playerSystemOutputActionStyle() = PlayerDeviceOptionStyle(
    glyph = PlayerDeviceGlyph.SYSTEM_CONNECTOR,
    colorRole = PlayerDeviceColorRole.ON_SURFACE_VARIANT
)

// MediaRouter exposes route type and current selection here, but no per-route connecting/error state.
internal fun playerSystemRouteOptionStyle(deviceType: Int, isCurrent: Boolean) = PlayerDeviceOptionStyle(
    glyph = when (deviceType) {
        MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH -> PlayerDeviceGlyph.BLUETOOTH
        MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> PlayerDeviceGlyph.SPEAKER
        MediaRouter.RouteInfo.DEVICE_TYPE_TV -> PlayerDeviceGlyph.TELEVISION
        else -> PlayerDeviceGlyph.REMOTE_OUTPUT
    },
    colorRole = if (isCurrent) PlayerDeviceColorRole.SECONDARY else PlayerDeviceColorRole.ON_SURFACE_VARIANT
)

internal fun playerDlnaOptionStyle(isActive: Boolean) = PlayerDeviceOptionStyle(
    glyph = PlayerDeviceGlyph.DLNA,
    colorRole = if (isActive) PlayerDeviceColorRole.PRIMARY else PlayerDeviceColorRole.ON_SURFACE_VARIANT
)

// r656：统一设计抽屉的线性图标（与「歌曲操作」抽屉同一套 1.6 线宽图标）。
private fun PlayerDeviceGlyph.imageVector(): ImageVector = when (this) {
    PlayerDeviceGlyph.LOCAL_PHONE -> DesignLineGlyphs.Phone
    PlayerDeviceGlyph.SYSTEM_CONNECTOR -> DesignLineGlyphs.Bluetooth
    PlayerDeviceGlyph.BLUETOOTH -> DesignLineGlyphs.Bluetooth
    PlayerDeviceGlyph.SPEAKER -> DesignLineGlyphs.Volume
    PlayerDeviceGlyph.TELEVISION -> Icons.Outlined.Tv
    PlayerDeviceGlyph.REMOTE_OUTPUT -> DefaultPlayerGlyphs.Device
    PlayerDeviceGlyph.DLNA -> Icons.Outlined.Cast
}

@Composable
private fun PlayerDeviceColorRole.resolveColor(): Color = when (this) {
    PlayerDeviceColorRole.PRIMARY -> MaterialTheme.colorScheme.primary
    PlayerDeviceColorRole.SECONDARY -> MaterialTheme.colorScheme.secondary
    PlayerDeviceColorRole.ON_SURFACE_VARIANT -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 分组眉题：DM Mono 大字距灰色英文 + 中文说明，与设计抽屉眉题同族。 */
@Composable
private fun DeviceSectionLabel(eyebrow: String, label: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            eyebrow.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 13.sp,
            fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, letterSpacing = 0.16.em, maxLines = 1
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 选项行内容：左 21dp 线性图标 + 15sp 粗体标题 / 12sp 说明（与 DesignSheetRow 同尺寸），右侧状态。 */
@Composable
private fun RowScope.DeviceRowContent(icon: ImageVector, iconTint: Color, title: String, subtitle: String) {
    Icon(icon, null, tint = iconTint, modifier = Modifier.size(21.dp))
    Spacer(Modifier.width(18.dp))
    Column(Modifier.weight(1f)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeviceDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(xyz.cncnlp.soundwave.ui.common.frostLine(designLineColor())))

@Composable
private fun DeviceCurrentMark(description: String) =
    Icon(DesignLineGlyphs.Check, description, tint = SoundwaveDesignPalette.Accent, modifier = Modifier.size(18.dp))

@Composable
private fun DeviceChevron(description: String) =
    Icon(DesignLineGlyphs.Chevron, description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))

/**
 * Stateless device-picker surface; routing, DLNA effects and visibility stay owned by FullPlayer.
 * r656：改为统一设计抽屉（珊瑚眉题 AUDIO OUTPUT + 24sp「播放设备」→ 当前歌曲条目头 → 1dp 分隔的 56dp 选项行），
 * 与「⋯ 歌曲操作」抽屉同一外壳（DesignSheetDialog / DesignSheetPanel）。
 */
@Composable
internal fun PlayerDeviceSheet(
    outputSnapshot: PlayerSystemOutputSnapshot<MediaRouter.RouteInfo>,
    dlnaSnapshot: PlayerDlnaPanelSnapshot,
    onDismissRequest: () -> Unit,
    onSelectLocalRoute: (MediaRouter.RouteInfo) -> Unit,
    onSelectSystemRoute: (MediaRouter.RouteInfo) -> Unit,
    onOpenSystemOutput: () -> Unit,
    onRefreshDlna: () -> Unit,
    onCastDlna: (DlnaDevice) -> Unit,
    onPauseDlna: () -> Unit,
    onResumeDlna: () -> Unit,
    onStopDlna: () -> Unit,
    songTitle: String = "",
    songCover: String = ""
) {
    DesignSheetDialog(onDismiss = onDismissRequest) {
        PlayerDeviceSheetContent(
            outputSnapshot, dlnaSnapshot, onSelectLocalRoute, onSelectSystemRoute, onOpenSystemOutput,
            onRefreshDlna, onCastDlna, onPauseDlna, onResumeDlna, onStopDlna, songTitle, songCover
        )
    }
}

/** 抽屉内容（可单独渲染供截图测试）。 */
@Composable
internal fun PlayerDeviceSheetContent(
    outputSnapshot: PlayerSystemOutputSnapshot<MediaRouter.RouteInfo>,
    dlnaSnapshot: PlayerDlnaPanelSnapshot,
    onSelectLocalRoute: (MediaRouter.RouteInfo) -> Unit,
    onSelectSystemRoute: (MediaRouter.RouteInfo) -> Unit,
    onOpenSystemOutput: () -> Unit,
    onRefreshDlna: () -> Unit,
    onCastDlna: (DlnaDevice) -> Unit,
    onPauseDlna: () -> Unit,
    onResumeDlna: () -> Unit,
    onStopDlna: () -> Unit,
    songTitle: String = "",
    songCover: String = ""
) {
    val session = dlnaSnapshot.session
    val castRoutes = outputSnapshot.routes.filterNot { it == outputSnapshot.defaultRoute }
    val currentOutput = when {
        session != null -> "正在投送到 ${session.device.name}"
        outputSnapshot.isDefaultRouteSelected() -> "当前从本机播放"
        else -> castRoutes.firstOrNull { outputSnapshot.isSelected(it) }?.name?.toString()?.let { "当前输出：$it" } ?: "当前从系统输出播放"
    }
    DesignSheetHeader("Audio output", "播放设备")
    if (songTitle.isNotBlank()) DesignSheetItemHeader(songCover, songTitle, currentOutput)
    else Spacer(Modifier.height(14.dp))
    // 路由 / DLNA 列表可能很长：内容区最高约屏幕 58%，可滚动。
    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        val localStyle = playerLocalDeviceOptionStyle(outputSnapshot.isDefaultRouteSelected())
        DeviceDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).clickable {
            outputSnapshot.defaultRoute?.let(onSelectLocalRoute)
        }.heightIn(min = 56.dp).padding(start = 4.dp, end = 2.dp).semantics { contentDescription = "本机扬声器"; role = Role.Button }, verticalAlignment = Alignment.CenterVertically) {
            DeviceRowContent(localStyle.glyph.imageVector(), localStyle.colorRole.resolveColor(), "本机扬声器", "手机扬声器 / 已连接的有线耳机")
            if (outputSnapshot.isDefaultRouteSelected()) DeviceCurrentMark("当前输出")
        }
        val systemActionStyle = playerSystemOutputActionStyle()
        DeviceDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).clickable {
            onOpenSystemOutput()
        }.heightIn(min = 56.dp).padding(start = 4.dp, end = 2.dp).semantics { contentDescription = "耳机与外接设备"; role = Role.Button }, verticalAlignment = Alignment.CenterVertically) {
            DeviceRowContent(systemActionStyle.glyph.imageVector(), systemActionStyle.colorRole.resolveColor(), "耳机与外接设备", "蓝牙、AirPlay 与系统输出连接器")
            DeviceChevron("打开系统输出连接器")
        }
        DeviceDivider()
        DeviceSectionLabel("DLNA", if (dlnaSnapshot.isScanning) "局域网实时扫描中" else "局域网媒体渲染器") {
            Text("刷新", color = SoundwaveDesignPalette.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                modifier = Modifier.clickable { onRefreshDlna() }.padding(horizontal = 8.dp).heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).wrapContentHeight(Alignment.CenterVertically))
        }
        if (dlnaSnapshot.devices.isEmpty()) {
            DeviceDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dlnaSnapshot.isScanning) VinylDiscSpinner(19.dp)
                else Icon(Icons.Outlined.Cast, "DLNA 设备", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(18.dp))
                Text(if (dlnaSnapshot.isScanning) "正在扫描局域网 DLNA 设备…" else "未发现 DLNA 设备，点击刷新重试", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontFamily = DesignFonts.Manrope)
            }
        } else {
            dlnaSnapshot.devices.forEach { device ->
                val isActive = session?.device?.location == device.location
                val deviceStyle = playerDlnaOptionStyle(isActive)
                DeviceDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).clickable { onCastDlna(device) }.heightIn(min = 56.dp).padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(deviceStyle.glyph.imageVector(), if (isActive) "当前 DLNA 输出" else "DLNA 媒体渲染器", tint = deviceStyle.colorRole.resolveColor(), modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(device.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (session?.isPlaying == true) "播放中 · " else "已暂停 · ",
                                    color = deviceStyle.colorRole.resolveColor(),
                                    fontSize = 12.sp, fontFamily = DesignFonts.Manrope
                                )
                                Text("断开", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                                    modifier = Modifier.clickable { onStopDlna() }.padding(horizontal = 8.dp).heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).wrapContentHeight(Alignment.CenterVertically))
                            }
                            // 远端进度轮询结果：显示 已播/总长（分:秒）
                            val sPos = session?.positionMs ?: 0L
                            val sDur = session?.durationMs ?: 0L
                            if (sDur > 0L) {
                                Text(
                                    "远端进度 %02d:%02d / %02d:%02d".format(sPos / 60000L, (sPos / 1000L) % 60L, sDur / 60000L, (sDur / 1000L) % 60L),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.DmMono
                                )
                            }
                        } else {
                            Text("DLNA 媒体渲染器", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope)
                        }
                    }
                    if (isActive) {
                        Icon(if (session?.isPlaying == true) DesignChromeGlyphs.PauseBold else DesignChromeGlyphs.PlaySolid, if (session?.isPlaying == true) "暂停 DLNA 播放" else "继续 DLNA 播放", tint = deviceStyle.colorRole.resolveColor(),
                            modifier = Modifier.clickable { if (session?.isPlaying == true) onPauseDlna() else onResumeDlna() }.padding(14.dp).size(20.dp))
                    } else DeviceChevron("打开 DLNA 渲染器")
                }
            }
        }
        if (castRoutes.isNotEmpty()) {
            DeviceDivider()
            DeviceSectionLabel("Media routes", "其他系统媒体路由")
            castRoutes.forEach { route ->
                val routeStyle = playerSystemRouteOptionStyle(route.deviceType, outputSnapshot.isSelected(route))
                DeviceDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = PLAYER_DEVICE_MIN_TOUCH_TARGET_DP.dp).clickable {
                    onSelectSystemRoute(route)
                }.heightIn(min = 56.dp).padding(start = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceRowContent(routeStyle.glyph.imageVector(), routeStyle.colorRole.resolveColor(), route.name.toString(), route.description?.toString().orEmpty().ifBlank { "可连接媒体设备" })
                    if (outputSnapshot.isSelected(route)) DeviceCurrentMark("当前设备") else DeviceChevron("选择该设备")
                }
            }
        }
    }
}
