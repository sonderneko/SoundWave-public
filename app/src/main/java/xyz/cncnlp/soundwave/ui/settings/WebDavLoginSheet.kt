package xyz.cncnlp.soundwave.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import xyz.cncnlp.soundwave.SoundwaveDesignPalette
import xyz.cncnlp.soundwave.ThemeTokens
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.sheetFocusGlow
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import xyz.cncnlp.soundwave.ui.common.designLineColor
import xyz.cncnlp.soundwave.ui.common.designStatusContainerColor
import xyz.cncnlp.soundwave.ui.common.designStatusContentColor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.height
import xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.sync.WebDavDiscoveredProfile
import xyz.cncnlp.soundwave.sync.WebDavHttpPolicy
import xyz.cncnlp.soundwave.sync.normalizeWebDavBaseUrl

internal const val DEFAULT_WEBDAV_URL = "https://dav.jianguoyun.com/dav/"

internal fun normalizeWebDavInitialUrl(savedUrl: String): String =
    savedUrl.trim().ifEmpty { DEFAULT_WEBDAV_URL }

@Composable
internal fun WebDavLoginSheet(
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    busy: Boolean,
    status: String,
    statusIsError: Boolean,
    onTestConnection: (String, String, String) -> Unit,
    onSync: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
    /** 1.0.656 #13：非空 = 已安全保存登录信息，跳过表单只显示「同步」（标签已打码）。 */
    savedAccountLabel: String? = null,
    onSyncSaved: () -> Unit = {},
    onEditCredentials: () -> Unit = {},
    onForgetSaved: () -> Unit = {},
    onBindingEdited: () -> Unit = {},
    /** http 按服务器例外（用户 2026-11-03 拍板）：当前已确认的例外键集合与授予/撤销回调。 */
    httpExceptions: Set<String> = emptySet(),
    onGrantHttpException: (String) -> Unit = {},
    onRevokeHttpException: (String) -> Unit = {},
    /** 云端资料发现结果（名字已解密）；非空时在表单下方给出「导入」入口。 */
    discoveredProfiles: List<WebDavDiscoveredProfile> = emptyList(),
    importingProfile: Boolean = false,
    onImportProfile: (WebDavDiscoveredProfile) -> Unit = {}
) {
    var url by remember(initialUrl) { mutableStateOf(normalizeWebDavInitialUrl(initialUrl)) }
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember(initialUrl, initialUsername, initialPassword, savedAccountLabel) { mutableStateOf(initialPassword) }
    // ACCEPT6（用户真机反馈 2026-11-04，同 SheetInputField 口径）：三字段的编辑状态持
    // TextFieldValue（选区/组合区自持，初值与程序化回填置于末尾）；上方 String 状态仍是
    // 逻辑真源（校验/例外判定/提交语义不变），仅文本变化时回写。
    var urlField by remember(initialUrl) {
        val t = normalizeWebDavInitialUrl(initialUrl)
        mutableStateOf(TextFieldValue(text = t, selection = TextRange(t.length)))
    }
    var usernameField by remember(initialUsername) {
        mutableStateOf(TextFieldValue(text = initialUsername, selection = TextRange(initialUsername.length)))
    }
    var passwordField by remember(initialUrl, initialUsername, initialPassword, savedAccountLabel) {
        mutableStateOf(TextFieldValue(text = initialPassword, selection = TextRange(initialPassword.length)))
    }
    LaunchedEffect(url) { if (url != urlField.text) urlField = TextFieldValue(url, TextRange(url.length)) }
    LaunchedEffect(username) { if (username != usernameField.text) usernameField = TextFieldValue(username, TextRange(username.length)) }
    LaunchedEffect(password) { if (password != passwordField.text) passwordField = TextFieldValue(password, TextRange(password.length)) }
    var formError by remember { mutableStateOf("") }
    var showHttpConfirm by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    // r658b 23（用户 10-19）：与其他设置抽屉统一——珊瑚强调（聚焦描边/标签/光标/主按钮），不再用主题蓝。
    val accent = SoundwaveDesignPalette.Accent
    // dialog-polish 波：圆角/字号对齐家族字段规格（SheetInputField 同值：FieldRadius / FieldTextSize / Manrope Medium）；
    // 浮动标签保留——多字段表单靠标签区分字段，属结构不动；聚焦珊瑚边与标签原已具备。
    val fieldShape = RoundedCornerShape(ThemeTokens.PlaylistDialog.FieldRadius)
    val fieldText = TextStyle(fontSize = ThemeTokens.PlaylistDialog.FieldTextSize, fontFamily = DesignFonts.Manrope, fontWeight = FontWeight.Medium)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colorScheme.onSurface,
        unfocusedTextColor = colorScheme.onSurface,
        disabledTextColor = colorScheme.onSurfaceVariant,
        focusedContainerColor = colorScheme.surface,
        unfocusedContainerColor = colorScheme.surface,
        disabledContainerColor = colorScheme.surfaceVariant,
        errorContainerColor = colorScheme.surface,
        focusedBorderColor = accent,
        unfocusedBorderColor = designLineColor(),
        disabledBorderColor = colorScheme.outlineVariant,
        errorBorderColor = colorScheme.error,
        focusedLabelColor = accent,
        unfocusedLabelColor = colorScheme.onSurfaceVariant,
        disabledLabelColor = colorScheme.onSurfaceVariant,
        errorLabelColor = colorScheme.error,
        focusedPlaceholderColor = colorScheme.onSurfaceVariant,
        unfocusedPlaceholderColor = colorScheme.onSurfaceVariant,
        disabledPlaceholderColor = colorScheme.onSurfaceVariant,
        errorPlaceholderColor = colorScheme.onSurfaceVariant,
        cursorColor = accent,
        errorCursorColor = colorScheme.error
    )
    fun submit(action: (String, String, String) -> Unit) {
        val urlInvalid = normalizeWebDavBaseUrl(url) == null &&
            normalizeWebDavBaseUrl(url, httpExceptions = httpExceptions) == null
        formError = when {
            url.isBlank() -> "请输入 WebDAV 地址"
            urlInvalid && url.trim().startsWith("http://", ignoreCase = true) ->
                "该 http 地址尚未开启例外：请先在地址下方确认风险并开启例外，或改用 HTTPS 地址"
            urlInvalid -> "请输入有效的 HTTPS WebDAV 地址（当前仅支持 HTTPS）"
            username.isBlank() -> "请输入用户名"
            password.isBlank() -> "请输入密码"
            else -> ""
        }
        if (formError.isEmpty() && !busy) action(url.trim(), username.trim(), password)
    }

    DesignSheetDialog(onDismiss = { if (!busy) onDismiss() }, scrollContent = true, dismissEnabled = !busy) {
        DesignSheetHeader("WebDAV sync", "WebDAV 同步")
        SettingsSheetCopy("当前资料独立 v2 文件 · 默认手动，可另行授权前台自动")
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (savedAccountLabel != null) {
                WebDavSavedAccountPanel(savedAccountLabel, busy, onSyncSaved, onEditCredentials, onForgetSaved)
            } else {
                OutlinedTextField(
                    value = urlField,
                    onValueChange = { urlField = it; if (it.text != url) { url = it.text; formError = ""; onBindingEdited() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("WebDAV 地址") },
                    placeholder = { Text(DEFAULT_WEBDAV_URL) },
                    supportingText = { Text("默认填入坚果云 WebDAV 地址，也可手动修改为其他服务地址。", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !busy,
                    shape = fieldShape,
                    textStyle = fieldText,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    colors = fieldColors
                )
                val httpExceptionKey = WebDavHttpPolicy.exceptionKey(url.trim())
                val httpGranted = httpExceptionKey != null && httpExceptionKey in httpExceptions
                if (url.trim().startsWith("http://", ignoreCase = true) && !httpGranted) {
                    Text(
                        "安全提醒：当前填写的是 http:// 明文地址。同步会传输 WebDAV 密码与个人数据（收藏、歌单、历史、脚本与设置），明文传输可能被窃听或篡改。默认仅接受 https:// 地址；如确需使用此服务器，可在确认风险后为它单独开启例外（仅对该地址生效，可随时撤销）。",
                        color = colorScheme.error,
                        fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope
                    )
                    Text(
                        "了解风险，为此服务器开启 http 例外",
                        color = accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !busy) { showHttpConfirm = true }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                } else if (url.trim().startsWith("http://", ignoreCase = true) && httpGranted) {
                    Text(
                        "已对此服务器开启 http 例外：连接不加密，密码与同步数据将以明文传输，请仅在可信网络中使用。更换服务器地址后需重新确认，例外不会自动沿用。",
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope
                    )
                    Text(
                        "撤销此服务器的 http 例外",
                        color = colorScheme.error, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .clickable(enabled = !busy) { onRevokeHttpException(url.trim()) }
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = usernameField,
                    onValueChange = { usernameField = it; if (it.text != username) { username = it.text; formError = ""; onBindingEdited() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = !busy,
                    shape = fieldShape,
                    textStyle = fieldText,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = passwordField,
                    onValueChange = { passwordField = it; if (it.text != password) { password = it.text; formError = "" } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !busy,
                    shape = fieldShape,
                    textStyle = fieldText,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit(onSync) }),
                    colors = fieldColors
                )
                Text(
                    "默认仅支持 HTTPS（http 地址需逐台确认风险并开启例外后才可使用），凭据仅在本机加密保存。坚果云请填写第三方应用密码。\n同步：读取、合并并上传当前资料独立的 v2 文件（soundwave-account-v2-*.json）；旧版无归属文件保持不变。默认手动同步，可单独授权前台恢复或本机变化时自动同步；无定时后台任务。\n测试：检查读写权限，写入并删除临时文件，不同步音乐数据。",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope
                )
                if (formError.isNotEmpty()) Text(formError, color = colorScheme.error, fontSize = 12.sp)
            }
            discoveredProfiles.forEach { profile ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, designLineColor(), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("发现云端资料", color = colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope)
                    Text(profile.profileName, color = colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, fontFamily = DesignFonts.Manrope)
                    Text(
                        if (profile.updatedAt > 0L) {
                            "最后同步：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(profile.updatedAt)) + " · 含 " + profile.playlistCount + " 个歌单"
                        } else {
                            "含 " + profile.playlistCount + " 个歌单"
                        },
                        color = colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope
                    )
                }
                WebDavPill(
                    if (importingProfile) "正在导入…" else "导入此资料并同步",
                    primary = true, busy = importingProfile, enabled = !busy && !importingProfile,
                    modifier = Modifier.fillMaxWidth()
                ) { onImportProfile(profile) }
            }
            if (status.isNotEmpty()) {
                Surface(
                    color = designStatusContainerColor(statusIsError),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        status,
                        color = designStatusContentColor(statusIsError),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            if (savedAccountLabel == null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WebDavPill("仅测试连接", primary = false, busy = false, enabled = !busy, modifier = Modifier.weight(1f)) { submit(onTestConnection) }
                    WebDavPill(if (busy) "正在检查并同步…" else "登录并同步", primary = true, busy = busy, enabled = !busy, modifier = Modifier.weight(1f)) { submit(onSync) }
                }
            }
        }
    }
    if (showHttpConfirm) {
        val publicHost = WebDavHttpPolicy.hostClass(url.trim()) == WebDavHttpPolicy.HostClass.PUBLIC
        DesignSheetDialog(onDismiss = { showHttpConfirm = false }) {
            // ACCEPT5（退出动画普查 T23）：两钮走本层退场句柄，showHttpConfirm=false 由退场后
            // onDismiss 承担（此前直置状态、确认层瞬时消失）。
            val dismissHttp = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showHttpConfirm = false }
            DesignSheetHeader("HTTP plaintext", "明文连接风险确认")
            Text(
                if (publicHost) {
                    "该地址是公网服务器。使用 http 明文连接时，WebDAV 密码与全部同步数据（收藏、歌单、历史、脚本与设置）都会以未加密形式经过互联网传输，路径上的节点可能窃听或篡改。强烈建议改用 https 地址；只有在你完全清楚风险时才开启例外。"
                } else {
                    "该地址看起来是局域网或私有地址。使用 http 明文连接时，WebDAV 密码与同步数据在这段网络内不加密，同一网络中的不可信设备仍可能窃听。仅建议在你自己的可信网络（例如家庭局域网）中使用。"
                },
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = DesignFonts.Manrope,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "例外仅对这一台服务器（按地址与端口）生效，可随时在同步对话框中撤销；更换服务器地址不会自动沿用。",
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = DesignFonts.Manrope,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WebDavPill("取消", primary = false, busy = false, enabled = true, modifier = Modifier.weight(1f)) { dismissHttp() }
                WebDavPill("确认开启例外", primary = true, busy = false, enabled = true, modifier = Modifier.weight(1.4f)) {
                    onGrantHttpException(url.trim())
                    dismissHttp()
                }
            }
        }
    }
}

/**
 * ACCEPT5 账号对话框：WebDAV 表单对话框（单一组件两态，账号流唯一表单入口）。
 * - 登录模式（无保存绑定）：点账号卡未登录时直达；主钮「登录并同步」= loginAndSync（同步成功才存凭据）。
 * - 编辑模式（已有绑定）：账号中心 WebDAV 行本体进入；服务器/账号预填现值，Ready 态密码预填已存值，
 *   主钮「保存」= 连接测试验通才存凭据（现行编辑语义）。
 * 排版照 v8 稿登录页 + DesignSheet 表单规范（对齐 LocalProfileRenameSheet）：眉题 + 标题 + 标签在上
 * 的暖填充字段 + 「自动同步」开关（标签仅此四字，开关为保存/登录成功后生效的意图位）+ 取消描边胶囊 /
 * 确认实心胶囊（站内 WebDavPill）。http 明文警告与例外、状态面、校验、云端资料发现均沿现行件。
 * [completed] 由控制器在主钮成功后置位，表单随即走 J8 句柄退场（宿主编排后续界面）。
 */
@Composable
internal fun WebDavAccountFormSheet(
    editMode: Boolean,
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    busy: Boolean,
    status: String,
    statusIsError: Boolean,
    initialAutoSyncEnabled: Boolean,
    completed: Boolean,
    onLogin: (String, String, String, Boolean) -> Unit,
    onSaveCredentials: (String, String, String, Boolean) -> Unit,
    onBindingEdited: () -> Unit,
    onDismiss: () -> Unit,
    httpExceptions: Set<String> = emptySet(),
    onGrantHttpException: (String) -> Unit = {},
    onRevokeHttpException: (String) -> Unit = {},
    discoveredProfiles: List<WebDavDiscoveredProfile> = emptyList(),
    importingProfile: Boolean = false,
    onImportProfile: (WebDavDiscoveredProfile) -> Unit = {}
) {
    var url by remember(initialUrl) { mutableStateOf(normalizeWebDavInitialUrl(initialUrl)) }
    var username by remember(initialUsername) { mutableStateOf(initialUsername) }
    var password by remember(initialUrl, initialUsername, initialPassword) { mutableStateOf(initialPassword) }
    var formError by remember { mutableStateOf("") }
    var autoSyncChoice by remember(initialAutoSyncEnabled) { mutableStateOf(initialAutoSyncEnabled) }
    var showHttpConfirm by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val dismiss = rememberDesignSheetDismiss(onDismiss)
    LaunchedEffect(completed) { if (completed) dismiss() }
    fun submit() {
        val urlInvalid = normalizeWebDavBaseUrl(url) == null &&
            normalizeWebDavBaseUrl(url, httpExceptions = httpExceptions) == null
        formError = when {
            url.isBlank() -> "请输入 WebDAV 地址"
            urlInvalid && url.trim().startsWith("http://", ignoreCase = true) ->
                "该 http 地址尚未开启例外：请先在地址下方确认风险并开启例外，或改用 HTTPS 地址"
            urlInvalid -> "请输入有效的 HTTPS WebDAV 地址（当前仅支持 HTTPS）"
            username.isBlank() -> "请输入用户名"
            password.isBlank() -> "请输入密码"
            else -> ""
        }
        if (formError.isEmpty() && !busy) {
            if (editMode) onSaveCredentials(url.trim(), username.trim(), password, autoSyncChoice)
            else onLogin(url.trim(), username.trim(), password, autoSyncChoice)
        }
    }

    DesignSheetDialog(onDismiss = { if (!busy) onDismiss() }, scrollContent = true, dismissEnabled = !busy) {
        DesignSheetHeader("SoundWave account", if (editMode) "编辑登录信息" else "登录 WebDAV")
        if (!editMode) {
            Text(
                "登录后同步收藏、歌单、近期历史与统计；不登录也可以正常听歌。",
                color = colorScheme.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 18.sp,
                fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 10.dp)
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WebDavFormField(
                label = "服务器地址",
                value = url,
                onValueChange = { url = it; formError = ""; onBindingEdited() },
                enabled = !busy,
                placeholder = DEFAULT_WEBDAV_URL,
                supporting = "默认填入坚果云 WebDAV 地址，也可手动修改为其他服务地址。",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            val httpExceptionKey = WebDavHttpPolicy.exceptionKey(url.trim())
            val httpGranted = httpExceptionKey != null && httpExceptionKey in httpExceptions
            if (url.trim().startsWith("http://", ignoreCase = true) && !httpGranted) {
                Text(
                    "安全提醒：当前填写的是 http:// 明文地址。同步会传输 WebDAV 密码与个人数据（收藏、歌单、历史、脚本与设置），明文传输可能被窃听或篡改。默认仅接受 https:// 地址；如确需使用此服务器，可在确认风险后为它单独开启例外（仅对该地址生效，可随时撤销）。",
                    color = colorScheme.error,
                    fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope
                )
                Text(
                    "了解风险，为此服务器开启 http 例外",
                    color = accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !busy) { showHttpConfirm = true }
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            } else if (url.trim().startsWith("http://", ignoreCase = true) && httpGranted) {
                Text(
                    "已对此服务器开启 http 例外：连接不加密，密码与同步数据将以明文传输，请仅在可信网络中使用。更换服务器地址后需重新确认，例外不会自动沿用。",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope
                )
                Text(
                    "撤销此服务器的 http 例外",
                    color = colorScheme.error, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = !busy) { onRevokeHttpException(url.trim()) }
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
            WebDavFormField(
                label = "账号",
                value = username,
                onValueChange = { username = it; formError = ""; onBindingEdited() },
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next, autoCorrectEnabled = false)
            )
            WebDavFormField(
                label = "密码",
                value = password,
                onValueChange = { password = it; formError = "" },
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { submit() })
            )
            if (formError.isNotEmpty()) Text(formError, color = colorScheme.error, fontSize = 12.sp)
            // 自动同步开关：标签仅「自动同步」（用户裁定砍副文案）；意图位，登录/保存成功后由控制器应用。
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy) { autoSyncChoice = !autoSyncChoice }
                    .semantics { role = Role.Switch },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "自动同步", color = colorScheme.onSurface, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope,
                    modifier = Modifier.weight(1f)
                )
                SettingsSwitch(checked = autoSyncChoice, enabled = !busy, onClick = null)
            }
            if (!editMode) {
                discoveredProfiles.forEach { profile ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, designLineColor(), RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("发现云端资料", color = colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope)
                        Text(profile.profileName, color = colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, fontFamily = DesignFonts.Manrope)
                        Text(
                            if (profile.updatedAt > 0L) {
                                "最后同步：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(profile.updatedAt)) + " · 含 " + profile.playlistCount + " 个歌单"
                            } else {
                                "含 " + profile.playlistCount + " 个歌单"
                            },
                            color = colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope
                        )
                    }
                    WebDavPill(
                        if (importingProfile) "正在导入…" else "导入此资料并同步",
                        primary = true, busy = importingProfile, enabled = !busy && !importingProfile,
                        modifier = Modifier.fillMaxWidth()
                    ) { onImportProfile(profile) }
                }
            }
            if (status.isNotEmpty()) {
                Surface(
                    color = designStatusContainerColor(statusIsError),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        status,
                        color = designStatusContentColor(statusIsError),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WebDavPill("取消", primary = false, busy = false, enabled = !busy, modifier = Modifier.weight(1f)) { dismiss() }
                WebDavPill(
                    if (busy) (if (editMode) "正在保存…" else "正在同步…") else (if (editMode) "保存" else "登录并同步"),
                    primary = true, busy = busy, enabled = !busy, modifier = Modifier.weight(1.5f)
                ) { submit() }
            }
        }
    }
    if (showHttpConfirm) {
        val publicHost = WebDavHttpPolicy.hostClass(url.trim()) == WebDavHttpPolicy.HostClass.PUBLIC
        DesignSheetDialog(onDismiss = { showHttpConfirm = false }) {
            // 与旧表单 T23 同口径：两钮走本层退场句柄，showHttpConfirm=false 由退场后 onDismiss 承担。
            val dismissHttp = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { showHttpConfirm = false }
            DesignSheetHeader("HTTP plaintext", "明文连接风险确认")
            Text(
                if (publicHost) {
                    "该地址是公网服务器。使用 http 明文连接时，WebDAV 密码与全部同步数据（收藏、歌单、历史、脚本与设置）都会以未加密形式经过互联网传输，路径上的节点可能窃听或篡改。强烈建议改用 https 地址；只有在你完全清楚风险时才开启例外。"
                } else {
                    "该地址看起来是局域网或私有地址。使用 http 明文连接时，WebDAV 密码与同步数据在这段网络内不加密，同一网络中的不可信设备仍可能窃听。仅建议在你自己的可信网络（例如家庭局域网）中使用。"
                },
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = DesignFonts.Manrope,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "例外仅对这一台服务器（按地址与端口）生效，可随时在同步对话框中撤销；更换服务器地址不会自动沿用。",
                color = colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = DesignFonts.Manrope,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WebDavPill("取消", primary = false, busy = false, enabled = true, modifier = Modifier.weight(1f)) { dismissHttp() }
                WebDavPill("确认开启例外", primary = true, busy = false, enabled = true, modifier = Modifier.weight(1.4f)) {
                    onGrantHttpException(url.trim())
                    dismissHttp()
                }
            }
        }
    }
}

/** 表单字段（DesignSheet 表单规范，对齐改名弹层）：标签在上 + 暖圆角填充输入，无指示线，珊瑚光标。 */
@Composable
private fun WebDavFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String = "",
    supporting: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions(),
    keyboardActions: KeyboardActions = KeyboardActions(),
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = SoundwaveDesignPalette.Accent
    val fieldFill = if (dark) SoundwaveDesignPalette.NightSurface2 else SoundwaveDesignPalette.Surface2
    // ACCEPT6（用户真机反馈 2026-11-04，同 SheetInputField 口径）：编辑状态持 TextFieldValue，
    // 初值与程序化回填（登录↔编辑模式切换）选区置于末尾；对上仍是 String 接口，宿主逻辑不变。
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(text = value, selection = TextRange(value.length))
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = onBg, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = DesignFonts.Manrope)
        Spacer(Modifier.height(6.dp))
        // ACCEPT6 dialogfrost：聚焦态按稿补齐——珊瑚描边 + 外圈柔光晕（静息态无边框外观不变）。
        val davInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val davFocused by davInteraction.collectIsFocusedAsState()
        TextField(
            fieldValue, { edited ->
                fieldValue = edited
                if (edited.text != value) onValueChange(edited.text)
            }, modifier = Modifier.fillMaxWidth()
                .sheetFocusGlow(davFocused, RoundedCornerShape(14.dp))
                .border(1.5.dp, if (davFocused) accent else Color.Transparent, RoundedCornerShape(14.dp)),
            interactionSource = davInteraction, singleLine = true,
            enabled = enabled,
            placeholder = if (placeholder.isNotBlank()) {
                { Text(placeholder, color = xyz.cncnlp.soundwave.Muted, fontSize = ThemeTokens.PlaylistDialog.FieldTextSize, fontFamily = DesignFonts.Manrope) }
            } else null,
            shape = RoundedCornerShape(14.dp),
            textStyle = TextStyle(fontSize = ThemeTokens.PlaylistDialog.FieldTextSize, fontFamily = DesignFonts.Manrope, fontWeight = FontWeight.Medium),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = fieldFill, unfocusedContainerColor = fieldFill,
                disabledContainerColor = fieldFill,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = accent, focusedTextColor = onBg, unfocusedTextColor = onBg
            )
        )
        if (supporting.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope)
        }
    }
}

/**
 * ACCEPT5：同步面板（登录后的同步控制面）——已保存账号面板 + 状态面。
 * 登录表单已收编至 [WebDavAccountFormSheet] 为唯一入口；本面板只在凭据 Ready 且未编辑时呈现
 * （设置备份页宿主使用；账号路径的编辑/同步/退出分别由账号中心行本体、右区与危险组承接）。
 */
@Composable
internal fun WebDavSyncPanelSheet(
    savedAccountLabel: String,
    busy: Boolean,
    status: String,
    statusIsError: Boolean,
    onSyncSaved: () -> Unit,
    onEditCredentials: () -> Unit,
    onForgetSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    DesignSheetDialog(onDismiss = { if (!busy) onDismiss() }, scrollContent = true, dismissEnabled = !busy) {
        DesignSheetHeader("WebDAV sync", "WebDAV 同步")
        SettingsSheetCopy("当前资料独立 v2 文件 · 默认手动，可另行授权前台自动")
        Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WebDavSavedAccountPanel(savedAccountLabel, busy, onSyncSaved, onEditCredentials, onForgetSaved)
            if (status.isNotEmpty()) {
                Surface(
                    color = designStatusContainerColor(statusIsError),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        status,
                        color = designStatusContentColor(statusIsError),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** 底部胶囊按钮：与 SettingsSheetButtons 同尺寸（48dp、全圆角、Manrope 800 14sp）；主按钮珊瑚底白字，次按钮线框。 */
@Composable
private fun WebDavPill(text: String, primary: Boolean, busy: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val accent = SoundwaveDesignPalette.Accent
    val shape = RoundedCornerShape(999.dp)
    val base = modifier.heightIn(min = 48.dp).clip(shape)
    Row(
        (if (primary) base.background(if (enabled || busy) accent else colorScheme.surfaceVariant)
        else base.border(1.dp, designLineColor(), shape))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            // ACCEPT4：忙碌态改音柱（稿加载语言），珊瑚按钮上用表面色柱体。
            xyz.cncnlp.soundwave.ui.common.EqBars(color = colorScheme.surface, height = 12.dp, barWidth = 2.5.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (primary) (if (enabled || busy) colorScheme.surface else colorScheme.onSurfaceVariant) else colorScheme.onSurface,
            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, maxLines = 1
        )
    }
}


/** 已保存登录信息时的精简视图：账号标签 + 一键「同步」，可编辑连接信息或忘记登录信息。 */
@Composable
private fun ColumnScope.WebDavSavedAccountPanel(
    label: String,
    busy: Boolean,
    onSyncSaved: () -> Unit,
    onEditCredentials: () -> Unit,
    onForgetSaved: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, designLineColor(), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text("已安全保存登录信息", color = colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = DesignFonts.Manrope)
        Text(label, color = colorScheme.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, fontFamily = DesignFonts.Manrope)
    }
    Text("密码仅以本机加密形式保存；点「同步」即可读取、合并并上传，无需再次输入。", color = colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WebDavPill("编辑连接信息", primary = false, busy = false, enabled = !busy, modifier = Modifier.weight(1f)) { onEditCredentials() }
        WebDavPill(if (busy) "正在同步…" else "同步", primary = true, busy = busy, enabled = !busy, modifier = Modifier.weight(1f)) { if (!busy) onSyncSaved() }
    }
    Text(
        "忘记登录信息", color = colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope,
        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp))
            .clickable(enabled = !busy, onClick = onForgetSaved).padding(horizontal = 16.dp, vertical = 14.dp)
    )
}
