package xyz.cncnlp.soundwave.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.SoundwaveDesignPalette

// 本机资料入口与改名表单（账号系统去除后）：头像与用户名为纯本地资料，不联网、无密码。

/** 本机资料选项图标：24×24 线性单色（stroke 1.8，圆端点），由 Icon(tint) 着色。 */
private fun profileLineGlyph(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Gray),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()

private val ProfileGlyphAvatar: ImageVector by lazy {
    profileLineGlyph("sw_profile_avatar", "M8.0 8.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0", "M4 21v-1a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v1")
}
private val ProfileGlyphRename: ImageVector by lazy {
    profileLineGlyph("sw_profile_rename", "M13 20h8", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z")
}
private val ProfileGlyphWebDav: ImageVector by lazy {
    profileLineGlyph("sw_profile_webdav", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M20.5 3.5v4h-4")
}
private val ProfileGlyphLogout: ImageVector by lazy {
    profileLineGlyph("sw_profile_logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5", "M21 12H9")
}

/** WebDAV 地址的展示主机名（状态文案用）：取 URI host，失败时退化为去协议/路径的原文。 */
internal fun webDavHostOf(url: String): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull()
    if (!host.isNullOrBlank()) return host
    return url.removePrefix("https://").removePrefix("http://").substringBefore('/').ifBlank { url }
}

/**
 * 账号中心底部面板（ACCEPT5 按 v8 稿第 6 节重做 + 14:21/14:23 细化）：DesignSheet 家族外壳——眉题
 * 「SoundWave account」+ 标题「账号中心」，身份行 + 动作组 + 危险组。稿的四格统计条（喜欢/歌单/
 * 本周·小时/本地）按用户明令整条不采用（「不要统计」指的就是本对话框内部）；注销账号不设
 * （账号系统已移除，WebDAV 侧无账号可注销，退出登录即其全部语义）。
 *
 * 身份 = 本机资料（头像/用户名纯本地，不依赖登录）。选项恰好四行：更换头像 / 更改用户名 /
 * WebDAV / 退出登录（危险组仅有绑定时显示）。WebDAV 行**改义保留**（用户细化口径，非删除）：
 * 标题恒「WebDAV」+ 摘要（服务器 · 打码账号），不带任何「登录」语义；行本体 → 编辑登录信息
 * 对话框（预填现值、保存更新凭据）；右侧「同步」独立点击区（仅 Ready 态）→ 立即手动同步一次，
 * 进行中/结果由宿主经无头控制器回喂 [webDavSyncStatus] 状态面呈现。未登录（None）时宿主不打开
 * 本面板——点账号卡直达 WebDAV 登录表单；本面板的 None 分支仅作兜底，行本体同样路由表单。
 *
 * 动作行一律「即时执行 + 退场句柄关闭」（J8 纪律）：宿主回调不得同步清面板状态，清状态只走
 * onDismiss（退场走完才触发）；`acted` 一次性门防退场期重复点按。行内同步进行中面板锁定
 * （dismissEnabled=false 且动作行不应答），与旧同步面板 busy 口径一致。
 */
@Composable
internal fun ProfileSheet(
    userName: String,
    avatarPath: String,
    onDismiss: () -> Unit,
    onChangeAvatar: () -> Unit,
    onRenameUser: () -> Unit,
    onEditWebDavCredentials: () -> Unit = {},
    onSyncWebDav: () -> Unit = {},
    webDavSyncBusy: Boolean = false,
    webDavSyncStatus: String = "",
    webDavSyncStatusIsError: Boolean = false,
    onLogoutWebDav: () -> Unit
) {
    val sheetContext = androidx.compose.ui.platform.LocalContext.current
    val webDavState = remember(sheetContext) {
        runCatching { xyz.cncnlp.soundwave.sync.WebDavCredentialStore.loadState(sheetContext) }
            .getOrDefault(xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.None)
    }
    val lastSyncAt = remember(sheetContext) {
        runCatching { xyz.cncnlp.soundwave.sync.WebDavSyncState.lastSyncAt(sheetContext) }.getOrDefault(0L)
    }
    val connected = webDavState is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.Ready
    val hasBinding = webDavState !is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.None
    val host = when (val st = webDavState) {
        is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.Ready -> webDavHostOf(st.credentials.url)
        is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.PasswordUnavailable -> webDavHostOf(st.url)
        else -> ""
    }
    val statusText = when {
        connected -> buildString {
            append("已连接 · ")
            append(host)
            if (lastSyncAt > 0L) {
                append(" · 上次同步 ")
                append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(lastSyncAt)))
            }
        }
        hasBinding -> "待重新输入密码 · $host"
        else -> "未登录 · 本机资料仅保存在此设备"
    }
    val webDavSummary = when (val st = webDavState) {
        is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.Ready ->
            xyz.cncnlp.soundwave.sync.webDavSavedAccountLabel(st.credentials.url, st.credentials.username)
        is xyz.cncnlp.soundwave.sync.WebDavSavedCredentialState.PasswordUnavailable ->
            xyz.cncnlp.soundwave.sync.webDavSavedAccountLabel(st.url, st.username)
        else -> "未连接"
    }
    DesignSheetDialog(onDismiss = onDismiss, scrollContent = false, dismissEnabled = !webDavSyncBusy) {
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        var acted by remember { mutableStateOf(false) }
        var confirmLogout by remember { mutableStateOf(false) }
        fun act(action: () -> Unit) {
            if (acted || webDavSyncBusy) return
            acted = true
            action()
            dismiss()
        }
        DesignSheetHeader("SoundWave account", "账号中心")
        // 身份行（稿 .v8acc-id）：60dp 双环头像（2dp 珊瑚 55% 环 + 3dp 间隔）+ 衬线名 + 状态行。
        // 稿的右侧胶囊钮已按用户细化口径下沉：同步 → WebDAV 行右区，编辑 → 行本体，登录 → 点卡直达表单。
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val accent = SoundwaveDesignPalette.Accent
            Box(
                Modifier.size(60.dp)
                    .border(2.dp, accent.copy(alpha = 0.55f), CircleShape)
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                xyz.cncnlp.soundwave.UserAvatarBadge(
                    loggedIn = true, avatarPath = avatarPath, sizeDp = 52f, elevated = false,
                    initial = userName
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    userName.ifBlank { "我的音乐" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp, lineHeight = 28.sp,
                    fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.03).em, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(
                                if (connected) Color(0xFF34C759)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        statusText, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DesignSheetRow(ProfileGlyphAvatar, "更换头像", "选择新头像") { act(onChangeAvatar) }
        DesignSheetRow(ProfileGlyphRename, "更改用户名", "修改显示名称") { act(onRenameUser) }
        WebDavAccountRow(
            summary = webDavSummary,
            showSync = connected,
            syncBusy = webDavSyncBusy,
            onBodyClick = { act(onEditWebDavCredentials) },
            onSyncClick = { if (!acted && !webDavSyncBusy) onSyncWebDav() }
        )
        if (webDavSyncStatus.isNotBlank()) {
            // 行内同步状态面：与同步表单/面板同一呈现（现行 status 文案由无头控制器同源回喂）。
            Surface(
                color = designStatusContainerColor(webDavSyncStatusIsError),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(
                    webDavSyncStatus,
                    color = designStatusContentColor(webDavSyncStatusIsError),
                    fontSize = 12.sp, fontFamily = DesignFonts.Manrope,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
        if (hasBinding) {
            // ACCEPT6 dialogfrost：危险区上方空带（全站动作表统一语言，确认态同享）。
            Spacer(Modifier.height(xyz.cncnlp.soundwave.ThemeTokens.Sheet.DangerZoneTopGap))
            if (!confirmLogout) {
                DesignSheetRow(ProfileGlyphLogout, "退出登录", "不会删除数据", danger = true) {
                    if (!acted && !webDavSyncBusy) confirmLogout = true
                }
            } else {
                // 退出登录内联确认（不叠新对话框）：说明后果 + 取消/退出登录按钮行（沿改名弹层 52dp 按钮行规格）。
                Column(Modifier.fillMaxWidth()) {
                    FrostDivider(designLineColor())
                    Text(
                        "将清除本机保存的 WebDAV 登录信息（地址、用户名与密码）；本机与云端数据都不会删除，下次同步时需要重新输入密码。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            Modifier.weight(1f).height(52.dp)
                                .pressScale(PressMotion.SCALE_ROW)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, designLineColor(), RoundedCornerShape(999.dp))
                                .rippleGlow(SoundwaveDesignPalette.Accent.copy(alpha = 0.18f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    role = androidx.compose.ui.semantics.Role.Button
                                ) { confirmLogout = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("取消", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = DesignFonts.Manrope)
                        }
                        Box(
                            Modifier.weight(1.5f).height(52.dp)
                                .pressScale(PressMotion.SCALE_ROW)
                                .clip(RoundedCornerShape(999.dp))
                                .background(xyz.cncnlp.soundwave.SoundwaveDanger)
                                .rippleGlow(Color.White.copy(alpha = 0.35f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    role = androidx.compose.ui.semantics.Role.Button
                                ) { act(onLogoutWebDav) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("退出登录", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, fontFamily = DesignFonts.Manrope)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 账号中心 WebDAV 行（用户 14:21/14:23 细化：选项保留在列、改义不删）：与 [DesignSheetRow] 同语言
 * （发丝线 + 无底图标 + 15sp 粗标题 + 12sp 摘要），但拆两个点击区——行本体进编辑登录信息对话框，
 * 右侧「同步」独立点击区（仅 Ready 态）点按即手动同步一次；同步中音柱 + 「同步中」且不可重复点。
 * 行本体与同步区各有 pressScale；标题与摘要零「登录」字样。
 */
@Composable
private fun WebDavAccountRow(
    summary: String,
    showSync: Boolean,
    syncBusy: Boolean,
    onBodyClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        FrostDivider(designLineColor())
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight()
                    .pressScale(PressMotion.SCALE_ROW)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = onBodyClick
                    )
                    .semantics {
                        contentDescription = "WebDAV"
                        role = androidx.compose.ui.semantics.Role.Button
                    }
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(ProfileGlyphWebDav, null, tint = colors.onSurface, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(18.dp))
                Text(
                    "WebDAV", color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    fontFamily = DesignFonts.Manrope, maxLines = 1
                )
                if (summary.isNotBlank()) Text(
                    summary, color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = DesignFonts.Manrope, letterSpacing = 0.04.em, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp)
                )
            }
            if (showSync) {
                Row(
                    Modifier.fillMaxHeight()
                        .pressScale(PressMotion.SCALE_ROW, enabled = !syncBusy)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            enabled = !syncBusy, onClick = onSyncClick
                        )
                        .semantics {
                            contentDescription = "同步"
                            role = androidx.compose.ui.semantics.Role.Button
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (syncBusy) {
                        EqBars(color = SoundwaveDesignPalette.Accent, height = 11.dp, barWidth = 2.5.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (syncBusy) "同步中" else "同步",
                        color = if (syncBusy) colors.onSurfaceVariant else SoundwaveDesignPalette.Accent,
                        fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 本地改名弹层（DesignSheet 家族：kicker + 标题 + 暖圆角填充输入 + 不等宽按钮行）。
 * 纯本地操作：不要求密码、不联网；校验（3–40 字符/字符集）由调用方执行并回传 status。
 * 按钮行沿用歌单输入弹层规格：高 52dp，取消描边胶囊 weight 1、保存珊瑚渐变胶囊 weight 1.5。
 */
@Composable
internal fun LocalProfileRenameSheet(
    currentUser: String,
    busy: Boolean,
    status: String,
    onSubmit: (newName: String) -> Unit,
    onDismiss: () -> Unit,
    /** ACCEPT5（退出动画普查 M14）：宿主改名成功后置 true 请求动画退场；失败仍留驻回显 status。 */
    closeRequested: Boolean = false
) {
    // ACCEPT6（用户真机反馈 2026-11-04，同 SheetInputField 口径）：输入状态持 TextFieldValue，
    // 不走 String 重载的内部临时选区（回填重同步会丢选区/组合区，光标跳回最前）。
    var newName by remember(currentUser) { mutableStateOf(TextFieldValue()) }
    val onBg = MaterialTheme.colorScheme.onBackground
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = SoundwaveDesignPalette.Accent
    val fieldFill = if (dark) SoundwaveDesignPalette.NightSurface2 else SoundwaveDesignPalette.Surface2
    DesignSheetDialog(onDismiss = { if (!busy) onDismiss() }) {
        // 收尾棒 J8 句柄补齐：「取消」走关闭句柄退场（busy 时钮禁用、外壳 onDismiss 亦有 busy 门控）。
        // ACCEPT5（退出动画普查 M14）：「保存」成功由宿主置 closeRequested 信号触发退场（此前
        // 宿主成功点同步清 showProfileRenameSheet 瞬时卸载）；失败仍留驻回显 status（原语义）。
        val dismiss = rememberDesignSheetDismiss(onDismiss)
        androidx.compose.runtime.LaunchedEffect(closeRequested) { if (closeRequested) dismiss() }
        DesignSheetHeader("Local profile", "修改用户名")
        Text(
            "当前用户名：$currentUser",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("新用户名", color = onBg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        // ACCEPT6 dialogfrost：聚焦态按稿补齐——珊瑚描边 + 外圈柔光晕（静息态无边框外观不变）。
        val renameInteraction = remember { MutableInteractionSource() }
        val renameFocused by renameInteraction.collectIsFocusedAsState()
        TextField(
            newName, { newName = it }, modifier = Modifier.fillMaxWidth()
                .sheetFocusGlow(renameFocused, RoundedCornerShape(14.dp))
                .border(1.5.dp, if (renameFocused) accent else Color.Transparent, RoundedCornerShape(14.dp)),
            interactionSource = renameInteraction, singleLine = true,
            enabled = !busy,
            placeholder = { Text("3-40 个字符", color = xyz.cncnlp.soundwave.Muted) },
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = fieldFill, unfocusedContainerColor = fieldFill,
                disabledContainerColor = fieldFill,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = accent, focusedTextColor = onBg, unfocusedTextColor = onBg
            )
        )
        if (status.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                status, color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp, modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.weight(1f).height(52.dp)
                    .pressScale(PressMotion.SCALE_ROW)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, designLineColor(), RoundedCornerShape(999.dp))
                    .rippleGlow(accent.copy(alpha = 0.18f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Button, enabled = !busy, onClick = dismiss),
                contentAlignment = Alignment.Center
            ) {
                Text("取消", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = DesignFonts.Manrope)
            }
            Box(
                Modifier.weight(1.5f).height(52.dp)
                    .pressScale(PressMotion.SCALE_ROW)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.horizontalGradient(listOf(accent, Color(0xFFFF8A73))))
                    .rippleGlow(Color.White.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Button, enabled = !busy) {
                        onSubmit(newName.text)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(if (busy) "正在保存…" else "保存", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, fontFamily = DesignFonts.Manrope)
            }
        }
    }
}

/** 本地资料名校验（沿用原账号名规则：3–40 字符，字母/数字及 _ @ . -）。纯本地，无远端冲突检查。 */
internal fun localProfileNameError(raw: String): String? {
    val name = raw.trim()
    if (name.length !in 3..40) return "用户名需为 3-40 个字符"
    if (name.any { !(it.isLetterOrDigit() || it in "_@.-") }) {
        return "用户名只能包含字母、数字及 _ @ . -"
    }
    return null
}
