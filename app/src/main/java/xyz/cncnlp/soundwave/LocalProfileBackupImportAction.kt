package xyz.cncnlp.soundwave

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import xyz.cncnlp.soundwave.ui.common.DesignSheetDialog
import xyz.cncnlp.soundwave.ui.common.DesignSheetHeader
import xyz.cncnlp.soundwave.ui.common.SheetActionButtons
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Local-backup import is intentionally separate from the legacy BackupStore restore route. */
@Composable
internal fun LocalProfileBackupImportAction(onImported: (ProfileId) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<PreparedLocalProfileBackup?>(null) }
    var localSelection by remember { mutableStateOf<PreparedLegacyCopy?>(null) }
    var selectedDomains by remember { mutableStateOf(emptySet<LocalCopyDomain>()) }
    var showLegacyCopyOptions by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // ACCEPT5：异步完成点的动画退场信号。完成时 busy/confirming 仍为真、DesignSheetDialog 的
    // close() 被 dismissEnabled 门控为空转，故完成点只置信号并复位门控位，由内容侧
    // LaunchedEffect 在重组后调句柄退场；onDismiss 触发时复位信号，防止下次打开误触。
    var legacyCloseRequested by remember { mutableStateOf(false) }
    var importCloseRequested by remember { mutableStateOf(false) }
    var importCompleted by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            status = "已取消选择；未写入本机资料。"
            return@rememberLauncherForActivityResult
        }
        if (busy) return@rememberLauncherForActivityResult
        busy = true
        localSelection = null
        status = "正在读取并校验所选文件；此阶段只在内存中预览。"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("无法读取所选文件")
                    input.use(LocalProfileBackupImporter::read)
                }
                prepared = result
                status = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                prepared = null
                status = "导入预览失败：${failure.message?.takeIf { it.isNotBlank() } ?: "文件无效"}。本机资料未改变。"
            } finally {
                busy = false
            }
        }
    }

    // Both actions preview into a new inactive copy and never replace the current profile.
    Column(Modifier.fillMaxWidth()) {
        BackupActionRow(
            title = "导入本机资料备份", icon = xyz.cncnlp.soundwave.ui.settings.SettingsPageGlyphs.Import, subtitle = "预览新格式备份，确认后创建隔离副本",
            iconTint = xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Data,
            enabled = !busy && prepared == null, onClick = {
                try { picker.launch(arrayOf("application/json")) }
                catch (_: Exception) { status = "无法打开系统文件选择器；本机资料未改变。" }
            })
        BackupActionRow(
            title = "复制旧本机资料", icon = xyz.cncnlp.soundwave.ui.settings.SettingsPageGlyphs.Convert, subtitle = "收藏、歌单和历史，先预览再确认创建副本",
            iconTint = xyz.cncnlp.soundwave.ui.settings.SettingsGroupMarks.Data,
            enabled = !busy && prepared == null, onClick = { showLegacyCopyOptions = true })
        if (showLegacyCopyOptions) {
            // 对话框清扫：AlertDialog 白框改 DesignSheet 弹层；说明文案/可选域/预览链路逐字不变。
            DesignSheetDialog(
                onDismiss = { if (!busy) { showLegacyCopyOptions = false; legacyCloseRequested = false } },
                scrollContent = true, dismissEnabled = !busy
            ) {
                // 收尾棒 J8 句柄补齐：「返回」走关闭句柄退场（fallback 复刻 onDismiss 的 busy 门控）；
                // ACCEPT5（退出动画普查 B1）：主钮异步预览成功点改调 dismiss() 退场（此前同步清
                // showLegacyCopyOptions 瞬时卸载）；显隐由退场后 onDismiss 清（busy 已在 finally 复位）。
                val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss { if (!busy) { showLegacyCopyOptions = false; legacyCloseRequested = false } }
                LaunchedEffect(legacyCloseRequested, busy) { if (legacyCloseRequested && !busy) dismiss() }
                Box(Modifier.semantics { heading() }) { DesignSheetHeader("Backup", "复制旧本机资料") }
                Spacer(Modifier.height(10.dp))
                Text(
                    "默认只复制收藏、歌单和播放历史。下面是可选的附加资料；它们只放进新建的隔离副本，不会影响当前资料，也不会启用、上传或运行脚本。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LocalCopyDomain.entries.forEach { domain ->
                    androidx.compose.material3.FilterChip(
                        selected = domain in selectedDomains,
                        enabled = !busy && prepared == null,
                        onClick = {
                            selectedDomains = if (domain in selectedDomains) selectedDomains - domain else selectedDomains + domain
                        },
                        label = { Text("${domain.displayName}（${domain.description}）", color = MaterialTheme.colorScheme.onSurface) }
                    )
                }
                SheetActionButtons(
                    secondary = "返回", onSecondary = dismiss, secondaryEnabled = !busy,
                    primary = "预览旧本机资料", primaryEnabled = !busy && prepared == null,
                    onPrimary = {
                        busy = true
                        scope.launch {
                            try {
                                val selection = withContext(Dispatchers.IO) {
                                    LegacyCopyMigration.preview(context, selectedDomains.toSet())
                                }
                                                                localSelection = selection
                                prepared = selection.prepared
                                status = "旧版本机资料已在内存中预览；副本不会覆盖当前资料，也不会复制设备授权、下载文件或凭据。"
                                legacyCloseRequested = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                status = failure.message ?: "本机复制预览失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
            }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    prepared?.let { pending ->
        var confirming by remember(pending) { mutableStateOf(false) }
        // 对话框清扫：AlertDialog 白框改 DesignSheet 弹层（滚动改由弹层承接）；预览全文与确认链路逐字不变。
        DesignSheetDialog(
            onDismiss = {
                importCloseRequested = false
                if (importCompleted) {
                    // ACCEPT5（退出动画普查 B2）：创建完成（成功/失败）的退场——完成文案已在
                    // 完成点写好，这里只清预览状态，不覆写为取消文案。
                    importCompleted = false
                    prepared = null
                } else if (!confirming) {
                    prepared = null
                    status = "已取消导入；未写入本机资料。"
                }
            },
            scrollContent = true, dismissEnabled = !confirming
        ) {
            // 收尾棒 J8 句柄补齐：「取消」走关闭句柄退场（清 prepared 与状态文案由 onDismiss 在退场后执行，
            // 与原即时写回净效果一致、仅晚 220ms 且文案在弹层之后不可见）。
            // ACCEPT5（退出动画普查 B2）：「确认并创建」的完成点（成功/失败）改置退场信号，
            // 不再同步 prepared = null 瞬时卸载；onDismiss 按 importCompleted 区分完成/取消清状态。
            val dismiss = xyz.cncnlp.soundwave.ui.common.rememberDesignSheetDismiss {
                importCloseRequested = false
                if (importCompleted) {
                    importCompleted = false
                    prepared = null
                } else if (!confirming) {
                    prepared = null
                    status = "已取消导入；未写入本机资料。"
                }
            }
            LaunchedEffect(importCloseRequested, confirming) { if (importCloseRequested && !confirming) dismiss() }
            Box(Modifier.semantics { heading() }) { DesignSheetHeader("Backup", "预览本机资料备份") }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                    Text("已在内存中校验 SoundWave 本机资料备份 v${InactiveProfileBackupSerializer.VERSION}。现在仍未写入任何资料。")
                    Text("收藏歌曲：${pending.preview.favoriteCount} 首")
                    Text("歌单：${pending.preview.playlistCount} 个（不含“喜欢 / 本地歌单”默认入口）")
                    Text("歌单中歌曲项：${pending.preview.playlistSongCount} 首")
                    Text("播放历史：${pending.preview.historyCount} 条")
                    localSelection?.let { selection ->
                        val selectedNames = selection.domains.keys.joinToString("、") { it.displayName }.ifEmpty { "无" }
                        Text("附加资料：$selectedNames")
                    }
                    if (pending.preview.playlistNames.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text("歌单名称：${pending.preview.playlistNames.joinToString("、")}")
                    }
                    if (pending.preview.songNames.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text("歌曲样本：${pending.preview.songNames.joinToString("；")}")
                    }
                    Text("确认后会使用全新随机 ProfileId 创建独立的 IMPORTED_LOCAL_NOT_ACTIVE 本机资料；不会覆盖当前资料、旧本机资料或其他已有资料，也不会激活、切换、登录、同步或访问网络。")
                    Text("文件仅包含收藏、歌单、歌曲元数据与播放历史。不会导入原 ProfileId、账号/用户名/密码/token/服务器来源、脚本、头像、下载记录、已下载文件、本机路径、SAF URI 或 resolvedUrl。封面仅接受无 user-info、query、fragment 的公开 HTTP(S) URL。")
                    Text("取消、返回或关闭此预览不会写入资料。只有点击下方独立确认按钮后才开始创建；写入失败会尝试清除部分新资料并回报结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SheetActionButtons(
                secondary = "取消", onSecondary = dismiss, secondaryEnabled = !confirming,
                primary = if (confirming) "正在创建…" else "确认并创建新的未启用资料", primaryEnabled = !confirming,
                onPrimary = {
                    if (!confirming) {
                        confirming = true
                        busy = true
                        scope.launch {
                            try {
                                val profileId = withContext(Dispatchers.IO) {
                                    val generated = ProfileId.of("localbackup_${UUID.randomUUID().toString().replace("-", "")}")
                                    val selection = localSelection
                                    if (selection != null) LegacyCopyMigration.copyConfirmed(context, generated, selection)
                                    else LocalStore.importLocalBackupToUnactivatedProfile(context, generated, pending)
                                    generated
                                }
                                status = "已创建新的本机未启用资料；当前资料保持不变。"
                                onImported(profileId)
                                importCompleted = true
                                confirming = false
                                importCloseRequested = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                status = "创建失败：${failure.message?.takeIf { it.isNotBlank() } ?: "本机写入未完成"}。未激活资料。"
                                importCompleted = true
                                confirming = false
                                importCloseRequested = true
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            )
        }
    }
}
