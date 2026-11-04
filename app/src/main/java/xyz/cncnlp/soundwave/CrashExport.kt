package xyz.cncnlp.soundwave

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter

/**
 * r658e（10-20 真机启动即闪退，进不了设置页看崩溃报告）：崩溃时额外把脱敏后的报告写到
 * 「下载/SoundWave/soundwave-crash.txt」，用文件管理器即可打开复制。仅本机文件，不上传；API 29+ 走 MediaStore，无需存储权限。
 * 包在 CrashGuard 的处理器外层：先导出，再交给 CrashGuard（及系统）原有流程。
 */
internal object CrashExport {
    private var installed = false

    fun install(context: Context) {
        if (installed || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) try { write(app, CrashReportRedactor.redact(report(app, thread, throwable)).take(24_000)) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun report(app: Context, thread: Thread, throwable: Throwable): String {
        val out = StringWriter()
        PrintWriter(out).use { w ->
            val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Throwable) { "?" }
            w.println("版本: $version")
            w.println("机型: ${Build.MANUFACTURER} ${Build.MODEL} API${Build.VERSION.SDK_INT}")
            w.println("线程: ${thread.name}")
            throwable.printStackTrace(w)
        }
        return out.toString()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun write(app: Context, text: String) {
        val resolver = app.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = "soundwave-crash.txt"
        val rel = Environment.DIRECTORY_DOWNLOADS + "/SoundWave"
        try {
            resolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name), null
            )?.use { c -> while (c.moveToNext()) resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null) }
        } catch (_: Throwable) {}
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
