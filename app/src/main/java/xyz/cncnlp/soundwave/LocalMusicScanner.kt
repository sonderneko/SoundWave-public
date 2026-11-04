package xyz.cncnlp.soundwave

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import java.io.File

/** 全盘扫描本机音频（MediaStore），对齐壳「本地歌单/扫描」 */
object LocalMusicScanner {
    private fun embeddedCover(context: Context, path: String): String {
        if (path.isBlank()) return ""
        return try {
            val retriever = MediaMetadataRetriever()
            if (path.startsWith("content://")) retriever.setDataSource(context, Uri.parse(path))
            else retriever.setDataSource(path.removePrefix("file://"))
            val bytes = retriever.embeddedPicture
            retriever.release()
            if (bytes == null || bytes.isEmpty()) return ""
            val dir = File(context.filesDir, "local-covers").apply { mkdirs() }
            val out = File(dir, "${path.hashCode().toUInt().toString(16)}.jpg")
            if (!out.exists() || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
            out.absolutePath
        } catch (_: Exception) { "" }
    }

    fun scan(context: Context, limit: Int = 2000, isCancelled: () -> Boolean = { false }): List<Song> {
        if (isCancelled()) return emptyList()
        val out = ArrayList<Song>()
        val seen = HashSet<String>()
        LocalScanFolders.audioDocuments(context, limit).forEach { (uri, title) ->
            if (isCancelled()) return@forEach
            val path = uri.toString()
            if (seen.add(path)) out.add(Song(id = path, name = title, singer = "", cover = embeddedCover(context, path), source = "local", resolvedUrl = path))
        }
        // 下载目录直接文件遍历：MediaStore 未索引的新下载歌曲也能扫到（本地扫描全盘）
        try {
            val exts = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "ape", "wma")
            // 常见音乐/下载目录：自家下载目录 + 网易云 / QQ音乐 / 酷狗 / 酷我 / 咪咕 的典型落盘路径。
            // 只递归一层，避免全盘扫导致限时超时；命中即加入，MediaStore 未索引也能发现。
            val roots = listOf(DownloadHelper.dir(context)) + listOf(
                "Music/netease/cloudmusic/Music", "netease/cloudmusic/Music",
                "Music/NetEase", "NetEase",
                "Music/qqmusic", "qqmusic", "Android/data/com.tencent.qqmusic/files/qqmusic",
                "Music/kugou", "kugou",
                "Music/kuwo", "kuwo",
                "Music/migu", "migu"
            ).mapNotNull { rel ->
                try { java.io.File(android.os.Environment.getExternalStorageDirectory(), rel).takeIf { it.isDirectory } } catch (_: Exception) { null }
            }
            fun addFile(f: java.io.File) {
                if (isCancelled()) return
                if (out.size >= limit || !f.isFile || f.length() <= 0L || !exts.contains(f.extension.lowercase())) return
                val path = "file://${f.absolutePath}"
                if (seen.add(path)) out.add(Song(id = f.name, name = f.nameWithoutExtension, singer = "", cover = embeddedCover(context, path), source = "local", resolvedUrl = path, album = "本地文件", duration = 0))
            }
            fun walk(dir: java.io.File, depth: Int) {
                if (isCancelled() || out.size >= limit || depth > 8) return
                try {
                    (dir.listFiles() ?: emptyArray()).forEach { f ->
                        if (out.size >= limit) return@forEach
                        if (f.isDirectory) walk(f, depth + 1) else addFile(f)
                    }
                } catch (_: Exception) {}
            }
            val seenDir = HashSet<String>()
            roots.forEach { dir ->
                val key = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                if (seenDir.add(key)) walk(dir, 0)
            }
        } catch (_: Exception) {}
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC}!=0 AND ${MediaStore.Audio.Media.DURATION}>30000"
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        return try {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                var n = 0
                while (c.moveToNext() && out.size < limit) {
                    if (isCancelled()) break
                    val id = c.getLong(iId).toString()
                    val title = c.getString(iTitle)?.ifBlank { null } ?: "未知歌曲"
                    val artist = c.getString(iArtist)?.ifBlank { null } ?: "未知歌手"
                    val album = c.getString(iAlbum).orEmpty()
                    val dur = c.getLong(iDur)
                    val path = if (iData >= 0) c.getString(iData).orEmpty() else ""
                    val playPath = if (path.isNotEmpty()) "file://$path"
                    else "content://media/external/audio/media/$id"
                    if (!seen.add(playPath)) continue
                    out.add(
                        Song(
                            id = id,
                            name = title,
                            singer = artist,
                            source = "local",
                            album = album,
                            cover = embeddedCover(context, playPath),
                            duration = dur,
                            resolvedUrl = playPath
                        )
                    )
                    n++
                }
            }
            out
        } catch (_: Exception) {
            // MediaStore 在部分 Android 版本/权限状态下会抛异常；不能丢掉前面
            // 已经递归扫到的文件，否则用户明明有本地歌曲却得到空歌单。
            out
        }
    }
}
