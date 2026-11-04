package xyz.cncnlp.soundwave.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import xyz.cncnlp.soundwave.ui.notice.AppNotice

// 自 MainActivity 迁出的权限请求逻辑（P1-1 SLICE 3）：
// 检查/请求/请求码与迁出前完全一致（READ_MEDIA_AUDIO(API 33+) / READ_EXTERNAL_STORAGE、
// 请求码 4101、提示文案「请允许读取音频文件后再次扫描」（原 Toast 已迁顶部横幅）、Android 13+ 启动时探测 POST_NOTIFICATIONS）。
// 本地扫描协程体仍留在 MainActivity.AppRoot（写 userBundle/playlists），这里只保留「权限门」。

/**
 * 本地音频扫描权限门。已授予 READ_MEDIA_AUDIO(API 33+) / READ_EXTERNAL_STORAGE 时返回 true；
 * 未授予则发出权限请求（请求码 4101，与迁出前一致）并横幅提示后返回 false，调用方应中止本次扫描。
 */
internal fun ensureAudioPermission(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
        (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(permission), 4101) }
        AppNotice.attach(context.applicationContext)
        AppNotice.banner("请允许读取音频文件后再次扫描")
        return false
    }
    return true
}

/** Android 13+ 启动时按需请求通知权限（POST_NOTIFICATIONS）；请求结果原样忽略（与迁出前一致）。 */
internal fun requestNotificationPermissionIfNeeded(activity: ComponentActivity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
