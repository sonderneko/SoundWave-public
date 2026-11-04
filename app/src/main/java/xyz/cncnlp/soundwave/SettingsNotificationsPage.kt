package xyz.cncnlp.soundwave

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.cncnlp.soundwave.ui.common.DesignCircleIconButton
import xyz.cncnlp.soundwave.ui.common.pressScale
import xyz.cncnlp.soundwave.ui.notice.AppNotice
import xyz.cncnlp.soundwave.ui.settings.*

@Composable
internal fun SettingsNotificationButton(onClick: () -> Unit) {
    val unread by AppNotice.unreadCount.collectAsState()
    Box {
        DesignCircleIconButton(Icons.Outlined.Notifications, "查看通知", onClick = onClick)
        if (unread > 0) {
            // 角标（验收二轮 E10，用户裁定照设计稿「3 · 通知角标」覆盖 B 波数字丸）：
            // 8dp 珊瑚圆点 + 2dp 底色描边环，位于 42dp 铃铛钮内 top 9dp / right 10dp；
            // 出现时 spring 弹入（稿子 v8bdgin 0.42s），随后 1.5dp 珊瑚环 ping 两轮
            // （稿子 v8bdgping：2.4s、延迟 0.6s、scale .6→1.9、opacity .7→0，共 2 次）。
            // 计数逻辑不变：未读计数与 99+ 上限仍驱动显隐与无障碍语义，只是可见形态改圆点。
            val reduceBadge = xyz.cncnlp.soundwave.ui.common.LocalReduceMotion.current
                ?: MotionPolicy.rememberReduceMotion()
            val badgeAnimate = MotionPolicy.allowDecorativeLoops(reduceBadge)
            val badgeIn = remember { androidx.compose.animation.core.Animatable(0f) }
            val badgePing = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(unread > 0) {
                if (!badgeAnimate) {
                    badgeIn.snapTo(1f)
                    return@LaunchedEffect
                }
                badgeIn.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f))
                kotlinx.coroutines.delay(600)
                repeat(2) {
                    badgePing.snapTo(0f)
                    badgePing.animateTo(1f, androidx.compose.animation.core.tween(durationMillis = 2400, easing = androidx.compose.animation.core.LinearEasing))
                }
                badgePing.snapTo(0f)
            }
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = 9.dp)
                    .semantics { contentDescription = if (unread > 99) "99+ 条未读消息" else "$unread 条未读消息" }
                    // 外 12dp 底色环 + 内 8dp 珊瑚点（稿子 box-shadow 0 0 0 2px 的等价画法）。
                    .size(12.dp)
                    .graphicsLayer { scaleX = badgeIn.value; scaleY = badgeIn.value }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(SoundwaveDesignPalette.Accent)
            )
            if (badgeAnimate && badgePing.value > 0f && badgePing.value < 1f) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = (-9).dp, y = 8.dp)
                        .size(14.dp)
                        .graphicsLayer {
                            val p = badgePing.value
                            scaleX = 0.6f + 1.3f * p; scaleY = 0.6f + 1.3f * p
                            alpha = 0.7f * (1f - p)
                        }
                        .border(1.5.dp, SoundwaveDesignPalette.Accent, CircleShape)
                )
            }
        }
    }
}

/** 消息分类色标：五类各一色，全部取设置分组既有色（SettingsGroupMarks），不新造色板。 */
internal fun inboxCategoryColor(category: AppNotice.Category): Color = when (category) {
    AppNotice.Category.PLAYBACK -> SettingsGroupMarks.Playback
    AppNotice.Category.SCRIPT -> SettingsGroupMarks.Sources
    AppNotice.Category.SYNC -> SettingsGroupMarks.Data
    AppNotice.Category.ACCOUNT -> SettingsGroupMarks.Appearance
    AppNotice.Category.SYSTEM -> SettingsGroupMarks.Audio
}

internal fun inboxCategoryLabel(category: AppNotice.Category): String = when (category) {
    AppNotice.Category.PLAYBACK -> "播放"
    AppNotice.Category.SCRIPT -> "音源脚本"
    AppNotice.Category.SYNC -> "同步与备份"
    AppNotice.Category.ACCOUNT -> "账号"
    AppNotice.Category.SYSTEM -> "系统"
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前（未来时刻按刚刚计，避免时钟偏差出现负数）。 */
internal fun inboxRelativeTime(timeMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val elapsed = (nowMillis - timeMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "刚刚"
        hours < 1L -> "$minutes 分钟前"
        days < 1L -> "$hours 小时前"
        else -> "$days 天前"
    }
}

internal data class ScriptNotification(
    val id: String,
    val title: String,
    val summary: String,
    val checkedAt: Long?,
    /** 完整报告文本（展开时展示）；无报告时为 null。完整报告本体存音源脚本页，此处只是呈现。 */
    val detail: String? = null
)

/** 验收二轮 E3（用户裁定）：脚本检测报告在通知中心只保留 30 分钟（以报告完成时间为准）。 */
internal const val SCRIPT_REPORT_NOTICE_RETENTION_MS = 30L * 60L * 1_000L

/** 报告是否还在通知中心的保留窗内：无时间戳/未发生的报告一律不算新鲜。 */
internal fun isScriptReportFresh(checkedAt: Long?, nowMillis: Long): Boolean =
    checkedAt != null && checkedAt > 0L && nowMillis - checkedAt <= SCRIPT_REPORT_NOTICE_RETENTION_MS

/** A presentation of an existing report, never an inferred success or a new probe. */
internal fun scriptNotification(script: Script, report: ScriptProbeCache.Report?): ScriptNotification {
    val summary = when {
        report == null -> "暂无检测报告，请前往音源脚本手动检测"
        !ScriptProbeCache.isReportCurrent(report, script) -> "上次检测已过期 · 脚本内容或运行环境已变化 · 请重新检测"
        else -> buildString {
            append(if (report.type == ScriptProbeCache.ReportType.QUICK) "旧版快速抽检" else report.type.label)
            if (!report.complete) append(" · 未完成")
            append(" · 已验证可播放 ${report.valid.size} / ${report.attempts.size} 项实际尝试")
            append(" · 仅代表本次检测覆盖范围")
        }
    }
    return ScriptNotification(
        script.id, ScriptProbeCache.safeDiagnosticText(script.name), summary,
        report?.checkedAt?.takeIf { it > 0L },
        detail = report?.let { ScriptProbeCache.format(it) }
    )
}

@Composable
internal fun SettingsNotificationsPage(scripts: List<Script>, onOpenScripts: () -> Unit) {
    // 离开消息中心时统一标已读：Hub 经 SettingsPushHost（AnimatedContent）承载本页，
    // 导航离开后旧页退出组合，onDispose 必然触发（转场结束即释放旧页）。
    DisposableEffect(Unit) {
        onDispose { AppNotice.markAllRead() }
    }
    // A 波追加（用户裁定）：版本更新类卡片整体移出消息中心——更新检查只保留在关于页；
    // 本页不再观察 GitHubReleases 的检查状态，也不再提供检查更新入口。
    // 打开消息中心时先清洗落盘收件箱：脚本检测类卡超过 30 分钟即移除（完整报告在音源脚本页）。
    LaunchedEffect(Unit) { AppNotice.pruneExpiredScriptNotices() }
    val revision by ScriptProbeCache.reportRevision.collectAsState()
    val notices = remember(scripts, revision) {
        // 验收二轮 E3（用户裁定）：脚本检测区只呈现「已完成且 30 分钟内」的报告——
        // 没有报告的脚本不占位成条，检测进行中（报告尚未落盘）也不会出现。
        val now = System.currentTimeMillis()
        scripts.map { it to ScriptProbeCache.latestReport(it.id) }
            .filter { (_, report) -> report != null && isScriptReportFresh(report.checkedAt, now) }
            .map { (script, report) -> scriptNotification(script, report) }
            .sortedByDescending { it.checkedAt ?: Long.MIN_VALUE }
    }
    SettingsNotificationsContent(notices, scripts.isNotEmpty(), onOpenScripts)
}

@Composable
internal fun SettingsNotificationsContent(
    notices: List<ScriptNotification>,
    hasScripts: Boolean = false,
    onOpenScripts: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val allInboxItems by AppNotice.inboxItems.collectAsState()
    // 展示侧同步执行 30 分钟保留（落盘清洗在打开页面时由 pruneExpiredScriptNotices 完成）：
    // 脚本检测类卡一过保留窗即不在本页出现，其余分类不受影响。
    val inboxItems = remember(allInboxItems) {
        val now = System.currentTimeMillis()
        allInboxItems.filter {
            it.category != AppNotice.Category.SCRIPT || isScriptReportFresh(it.timeMillis, now)
        }
    }
    // A 波追加：卡片点按展开/收起全部内容。展开状态仅为本页本地 UI 状态，
    // 不写已读、不改角标计数（已读仍由离开本页时 markAllRead 统一处理）。
    var expandedInboxIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var expandedScriptIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    SettingsPageList {
        if (inboxItems.isNotEmpty()) {
            item {
                SettingsPageSection("消息", trailing = "清空", onTrailing = {
                    val snapshot = AppNotice.clearInbox()
                    AppNotice.undo("已清空消息") { AppNotice.restoreInbox(snapshot) }
                })
            }
            items(inboxItems, key = { "inbox-${it.id}" }) { message ->
                val expanded = message.id in expandedInboxIds
                SettingsCard(Modifier.padding(top = 12.dp)) {
                    // 按压反馈走 pressScale + 无指示（SW-12-01 既定搭配）：不再让共享按压指示在
                    // 减少动效/长按时把整块内容区刷成矩形色块（验收二轮 E3 截图灰块）。
                    Column(Modifier.fillMaxWidth().pressScale(xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_CARD)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        expandedInboxIds = if (expanded) expandedInboxIds - message.id else expandedInboxIds + message.id
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.semantics { contentDescription = inboxCategoryLabel(message.category) }) {
                                SettingsStatusDot(inboxCategoryColor(message.category))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                message.title, color = ink,
                                fontWeight = if (message.read) FontWeight.Normal else FontWeight.Bold,
                                fontSize = 14.sp, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (!message.read) {
                                Spacer(Modifier.width(6.dp))
                                Box(Modifier.size(7.dp).clip(CircleShape).background(SoundwaveDesignPalette.Accent))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(inboxRelativeTime(message.timeMillis), color = muted, fontSize = 11.sp, maxLines = 1)
                        }
                        message.body?.takeIf { it.isNotBlank() }?.let { body ->
                            Text(body, color = muted, fontSize = 12.sp, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp))
                            Text(if (expanded) "收起" else "展开全文", color = muted, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp).align(Alignment.End))
                        }
                    }
                }
            }
        }
        item {
            SettingsPageSection("脚本检测")
            Text("来源：本机保存的检测报告，优先展示完整检测，没有时展示旧版抽检。查看通知不会启动检测，也不会改变定时检测设置。",
                color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
            SettingsActionButton("前往音源脚本", Modifier.fillMaxWidth(), onClick = onOpenScripts)
        }
        if (notices.isEmpty()) {
            item {
                SettingsCard(Modifier.padding(top = 12.dp)) {
                    // 无新鲜报告时只留一条区级说明，不再逐脚本占位成条（验收二轮 E3）。
                    Text(
                        if (hasScripts) "最近 30 分钟内没有新的检测报告，完整报告请前往音源脚本查看"
                        else "尚未导入音源脚本，暂无检测报告",
                        color = muted, fontSize = 13.sp
                    )
                }
            }
        }
        items(notices, key = { it.id }) { notice ->
            val expanded = notice.id in expandedScriptIds
            SettingsCard(Modifier.padding(top = 12.dp)) {
                Column(Modifier.fillMaxWidth().pressScale(xyz.cncnlp.soundwave.ui.common.PressMotion.SCALE_CARD)
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                    expandedScriptIds = if (expanded) expandedScriptIds - notice.id else expandedScriptIds + notice.id
                }) {
                    Text(notice.title.ifBlank { "未命名脚本" }, color = ink, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                    Text(notice.summary, color = muted, fontSize = 12.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp))
                    notice.checkedAt?.let { timestamp ->
                        val time = remember(timestamp) {
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                .format(java.util.Date(timestamp))
                        }
                        Text("报告时间：$time", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    // 展开 = 完整报告全文（与音源脚本页同一份报告文本）；收起回到摘要。
                    if (expanded && !notice.detail.isNullOrBlank()) {
                        Text(notice.detail, color = muted, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    if (!notice.detail.isNullOrBlank() || notice.summary.length > 42) {
                        Text(if (expanded) "收起" else "展开全文", color = muted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp).align(Alignment.End))
                    }
                }
            }
        }
    }
}
