package xyz.cncnlp.soundwave

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignFonts
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.ui.common.DesignLineGlyphs
import xyz.cncnlp.soundwave.ui.settings.SettingsActionButton
import xyz.cncnlp.soundwave.ui.settings.SettingsButtonKind
import xyz.cncnlp.soundwave.ui.settings.SettingsCard
import xyz.cncnlp.soundwave.ui.settings.settingsPressFeedback
import xyz.cncnlp.soundwave.ui.settings.SettingsChevron
import xyz.cncnlp.soundwave.ui.settings.SettingsDetailHero
import xyz.cncnlp.soundwave.ui.settings.SettingsHairline
import xyz.cncnlp.soundwave.ui.settings.SettingsLineGlyphs
import xyz.cncnlp.soundwave.ui.settings.SettingsPageGlyphs
import xyz.cncnlp.soundwave.ui.settings.SettingsPageList
import xyz.cncnlp.soundwave.ui.settings.SettingsPageSection
import xyz.cncnlp.soundwave.ui.settings.SettingsStatTile
import xyz.cncnlp.soundwave.ui.settings.settingsMutedColor

/*
 * r656 item 14：数据流向 / 关于 APP 由弹窗改为设置详情页（设计稿 settingDetails.about 与
 * settings-detail 语言：珊瑚眉题 + 衬线标题 → 透明行 / 线框卡）。隐私说明文案保持原语义。
 */

/** 数据流向说明（标题 → 完整说明）。privacy-data-flow-copy-guard 逐行核对这些边界。 */
internal val DATA_FLOW_ROWS: List<Pair<String, String>> = listOf(
    "WebDAV 同步" to "默认关闭自动同步。先成功手动同步本机资料与所选 WebDAV 地址、用户名，才可单独开启本次应用会话的自动同步；重启后需重新开启。只在应用回到前台或本机资料数据变化后合并触发，没有定时后台任务。同步本机资料独立 v2 文件中的收藏、在线歌单、顺序、删除标记、最近 30 天播放历史（最多 300 条）与最近 30 天真实听歌统计，以及音源脚本（含代码、启用状态与顺序）、智能换源策略、音质与个性化样式设置和不喜欢记录；不读取或上传旧版无归属统计，不上传音乐文件或扫描库。本机确认的头像独立保存。WebDAV 登录凭据仅在本机加密保存，并发送给你选择的 HTTPS WebDAV 服务。更换 WebDAV 地址或账号、忘记凭据会关闭并取消自动同步；上传结果未确认时暂停自动尝试，需先检查远端并完成手动同步。",
    "联网请求" to "搜索、榜单、歌单、歌词和取链会按各自实现向音乐平台或应用配置的 SoundWave 服务发送必要参数；平台直连、服务端请求与脚本联网是不同链路。",
    "脚本联网" to "脚本发出的 HTTP 请求一律从本机直连其目标服务器，不再经过 SoundWave 服务器中转；脚本给出的请求地址、方法与正文只发往目标服务器。请求发出前会校验目标地址，校验通过的公网地址按校验结果直连，个别代理环境下经系统网络栈直连，其余一律拒绝。请求头会先过白名单过滤再发出，过滤后仍可能含 x-token、x-request-key、x-card-key 等头值。",
    "本机存储" to "收藏、歌单及歌曲记录、头像等同步数据，以及播放历史和下载记录，保存在本机应用私有 SharedPreferences 中并以 JSON 文本写入；本机播放历史最多保留 30 天，每日听歌统计最多保留 365 天。应用没有对这些用户数据使用 Android Keystore AES-GCM 加密。WebDAV 登录凭据则单独通过 Android Keystore AES-GCM 加密后保存。",
    "崩溃报告" to "致命崩溃报告先脱敏（去掉网址参数、令牌/Cookie、歌曲 key、邮箱、设备标识和本机路径）再存本机，只保存在本机，不会上传到任何服务器。可在崩溃诊断页查看、复制或清除，也会另存一份到下载目录便于导出。这个流程不读取 Android ID。",
    "播放历史与本机清理" to "播放历史页删除单条或批量选中的记录时，会写入对应单次播放删除标记。WebDAV 会在下次同步时传播删除标记，其他使用同一同步数据的设备下次同步后会移除该次播放；未开启 WebDAV 同步时标记仅保存在本机。清空全部历史只改本机独立历史记录。本机播放历史最多保留最近 30 天；WebDAV 同步的播放历史为最近 30 天且最多 300 条。歌单页内的“清空历史”只更新本机。单纯进入歌单库不会单独触发同步。",
)

private fun dataFlowIcon(title: String): ImageVector = when (title) {
    "WebDAV 同步" -> SettingsPageGlyphs.Server
    "联网请求" -> SettingsPageGlyphs.Globe
    "脚本联网" -> SettingsPageGlyphs.Code
    "本机存储" -> SettingsPageGlyphs.Device
    "崩溃报告" -> SettingsPageGlyphs.Pulse
    "播放历史与本机清理" -> SettingsLineGlyphs.of("history")
    else -> SettingsPageGlyphs.Box
}

/** 折叠时显示的首句摘要（到第一个句号/分号）。 */
internal fun dataFlowSummary(desc: String): String {
    val cut = desc.indexOfFirst { it == '。' || it == '；' }
    return if (cut in 1 until desc.length - 1) desc.substring(0, cut) + "…" else desc
}

@Composable
fun DataFlowPage() {
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = settingsMutedColor()
    var expanded by remember { mutableStateOf(setOf<String>()) }
    SettingsPageList {
        item { SettingsDetailHero("同步与数据", "数据流向", "了解账户资料的本机与云端范围：哪些只留在本机、哪些会联网、什么时候才会上传。") }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth()) {
                    SettingsStatTile("本机历史", "30", "天", Modifier.weight(1f))
                    SettingsStatTile("云端历史", "300", "条", Modifier.weight(1f))
                    SettingsStatTile("音乐文件", "0", "上传", Modifier.weight(1f), accent = SoundwaveDesignPalette.Accent)
                }
                Spacer(Modifier.height(12.dp))
                Text("本地优先：断网或服务器不可用时，本地缓存与部分平台直连仍可播放。", color = muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        item {
            SettingsPageSection(
                "逐项说明",
                trailing = if (expanded.size == DATA_FLOW_ROWS.size) "全部收起" else "全部展开",
                onTrailing = { expanded = if (expanded.size == DATA_FLOW_ROWS.size) emptySet() else DATA_FLOW_ROWS.map { it.first }.toSet() }
            )
        }
        item { SettingsHairline() }
        DATA_FLOW_ROWS.forEach { (title, desc) ->
            item(key = title) {
                val open = title in expanded
                Column(
                    Modifier.fillMaxWidth().animateContentSize()
                        .clickable { expanded = if (open) expanded - title else expanded + title }
                        .semantics { role = Role.Button; stateDescription = if (open) "已展开" else "已收起" }
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(30.dp).padding(top = 1.dp), contentAlignment = Alignment.CenterStart) {
                            Icon(dataFlowIcon(title), null, tint = onBg, modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = onBg, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (open) desc else dataFlowSummary(desc), color = muted,
                                fontSize = if (open) 11.sp else 9.5.sp, lineHeight = if (open) 18.sp else 14.sp,
                                maxLines = if (open) Int.MAX_VALUE else 2,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(DesignLineGlyphs.Chevron, null, tint = muted, modifier = Modifier.padding(top = 2.dp).size(15.dp).rotate(if (open) 270f else 90f))
                    }
                    SettingsHairline()
                }
            }
        }
    }
}

/** 版本状态里只认检查更新产生的文案，避免把播放状态（如“智能取链…”）显示在关于页。 */
internal fun aboutUpdateStatus(status: String): String =
    status.takeIf { it.startsWith("发现新版本") || it.startsWith("已是最新") || it.startsWith("检查失败") || it.startsWith("尚无可用 GitHub") || it.startsWith("正在检查 GitHub") }.orEmpty()

@Composable
fun AboutAppPage(status: String, serverUrl: String, onCheckUpdate: () -> Unit, onOpenDataFlow: () -> Unit) {
    // r657 第三轮 b（用户：关于 APP 页与设计稿不符）：按 v5 pAbout 重做——
    // .card.abc（r24 表面卡、18dp 内距）：52dp r16 珊瑚径向渐变「S」+ 衬线 600 26sp「SoundWave」+ DM Mono 11sp 版本；
    // 「APP 简介」800 17sp + 11.5/17 灰简介；50dp 珊瑚渐变「检查更新」；700 11.5sp 珊瑚状态行。
    // 分组 = .glabel（中文 13sp 700 + DM Mono 9.5sp .16em）+ .grp 卡：版本信息（键值行 62dp、值 DM Mono 600 12.5sp，点按复制）、
    // 法律与许可（开源许可 行内展开 + 隐私说明）；页尾 11.5/17 免责声明。
    val ctx = LocalContext.current
    val onBg = MaterialTheme.colorScheme.onBackground
    val muted = settingsMutedColor()
    val accent = SoundwaveDesignPalette.Accent
    val updateStatus = aboutUpdateStatus(status)
    var showLicenses by remember { mutableStateOf(false) }
    val copy: (String, String) -> Unit = { k, v ->
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(k, v))
        AppNotice.toast("已复制$k", AppNotice.Kind.SUCCESS)
    }
    SettingsPageList {
        item { SettingsDetailHero("系统与实验", "关于 APP", "查看版本、更新渠道、许可与开源声明。") }
        item {
            SettingsCard(Modifier.padding(top = 18.dp), padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.size(52.dp)
                            .shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = accent.copy(alpha = 0.35f), spotColor = accent.copy(alpha = 0.45f))
                            .clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Brush.radialGradient(
                                0f to Color(0xFFFF8A73), 0.6f to accent, 1f to Color(0xFFE8452F),
                                center = androidx.compose.ui.geometry.Offset(52f * 0.3f * ctx.resources.displayMetrics.density, 52f * 0.2f * ctx.resources.displayMetrics.density),
                                radius = 52f * 1.2f * ctx.resources.displayMetrics.density
                            )),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("S", color = Color.White, fontSize = 26.sp, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("SoundWave", color = onBg, fontSize = 26.sp, lineHeight = 30.sp, fontFamily = DesignFonts.Newsreader, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em, maxLines = 1)
                        Text("版本 ${BuildConfig.VERSION_NAME}", color = muted, fontSize = 11.sp, fontFamily = DesignFonts.DmMono, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
                Text("APP 简介", color = onBg, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "SoundWave 是面向个人使用的音乐播放器，支持本地音乐管理、播放与歌词显示；在线音源由用户自行配置。",
                    color = muted, fontSize = 11.5.sp, lineHeight = 17.sp, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 4.dp)
                )
                AboutUpdateAction(updateStatus = updateStatus, onCheckUpdate = onCheckUpdate)
                if (updateStatus.isNotBlank()) {
                    SettingsActionButton("前往下载", Modifier.fillMaxWidth().padding(top = 8.dp), icon = SettingsPageGlyphs.Import) {
                        val url = GitHubReleases.downloadPage()
                        if (url.startsWith("http")) {
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)); xyz.cncnlp.soundwave.ui.settings.SettingsGroupLabel("版本信息", "Version") }
        item {
            xyz.cncnlp.soundwave.ui.settings.SettingsGroupCard {
                val rows = listOf(
                    "版本号" to BuildConfig.VERSION_NAME,
                    "运行环境" to "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"
                )
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) SettingsHairline(Modifier.padding(start = 14.dp))
                    AboutKeyValueRow(k, v) { copy(k, v) }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)); xyz.cncnlp.soundwave.ui.settings.SettingsGroupLabel("法律与许可", "Legal") }
        item {
            xyz.cncnlp.soundwave.ui.settings.SettingsGroupCard {
                AboutProtoRow(SettingsPageGlyphs.Code, "开源许可", "Jetpack Compose、Media3 / ExoPlayer、Coil、OkHttp…", onClick = { showLicenses = !showLicenses },
                    expanded = if (showLicenses) "Jetpack Compose (Apache 2.0) · Media3 / ExoPlayer (Apache 2.0) · Coil (Apache 2.0) · OkHttp (Apache 2.0) · " +
                        "Kotlin Coroutines (Apache 2.0) · Haze (Apache 2.0) · Manrope / Newsreader / DM Mono (SIL OFL 1.1) · 思源宋体 (SIL OFL 1.1)。" else null
                ) {
                    Icon(DesignLineGlyphs.Chevron, null, tint = muted, modifier = Modifier.size(16.dp).rotate(if (showLicenses) 270f else 90f))
                }
                SettingsHairline(Modifier.padding(start = 63.dp))
                AboutProtoRow(SettingsPageGlyphs.Shield, "隐私说明", "了解账户资料的本机与云端范围", onClick = onOpenDataFlow) { SettingsChevron() }
            }
        }
        item {
            Text(
                "免责声明：本应用仅供个人学习研究使用，不提供任何音乐版权内容与商业服务。请用户自行准备合法合规音源脚本，并遵守当地法律法规与平台服务条款。",
                color = muted, fontSize = 11.5.sp, lineHeight = 17.sp, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp)
            )
        }
    }
}

/** v5 .srow.kvrow：62dp，左 600 14sp 键、右 DM Mono 600 12.5sp 墨色值；点按复制。 */
@Composable
private fun AboutKeyValueRow(key: String, value: String, onCopy: () -> Unit) {
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp)
            .settingsPressFeedback(ix)
            .clickable(interactionSource = ix, indication = null, onClick = onCopy)
            .semantics { role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.Manrope, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = DesignFonts.DmMono, maxLines = 1)
    }
}

/** v5 .srow：36dp r12 中性图标块 + 标题 / 副标题；[expanded] 非空时在副标题下展开说明（.accb）。 */
@Composable
private fun AboutProtoRow(
    icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, expanded: String? = null,
    trailing: @Composable () -> Unit
) {
    val ix = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).animateContentSize()
            .settingsPressFeedback(ix)
            .clickable(interactionSource = ix, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        xyz.cncnlp.soundwave.ui.settings.SettingsIconTile(icon, xyz.cncnlp.soundwave.ui.settings.SettingsTileTone.Neutral)
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, fontFamily = DesignFonts.Manrope, maxLines = 1)
            Text(subtitle, color = settingsMutedColor(), fontSize = 11.5.sp, lineHeight = 16.sp, fontFamily = DesignFonts.Manrope, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (expanded != null) Text(expanded, color = settingsMutedColor(), fontSize = 12.sp, lineHeight = 19.sp, fontFamily = DesignFonts.Manrope, modifier = Modifier.padding(top = 8.dp))
        }
        trailing()
    }
}
