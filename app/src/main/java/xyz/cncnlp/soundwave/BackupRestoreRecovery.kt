package xyz.cncnlp.soundwave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * A private, projected before/input copy plus an explicit replay checkpoint, NOT a cross-store
 * transaction. A torn/missing record or a changed identity/data snapshot fails closed.
 * Ordinary writers do not share [lock]; no claim is made about writes racing during replay.
 */
object BackupRestoreRecovery {
    internal val lock = Any()
    private const val PREF = "backup_restore_recovery_v1"
    private const val ACTIVE = "active_id"
    private const val EXPECTED = "expected_state"
    private const val STAGE = "stage"
    const val SCOPES = "收藏与歌单、备份包含的播放历史和下载记录、日统计、偏好设置、已有脚本顺序/启用配置及头像"
    data class Review internal constructor(val id: String, val state: String, val identity: String)
    data class Status(val pending: Boolean, val message: String)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun file(ctx: Context, id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "恢复记录标识无效" }
        return File(ctx.filesDir, "backup_restore_recovery/$id.json")
    }
    internal fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    internal fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{", postfix="}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[", postfix="]") { canonical(value.opt(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
    private fun identity(ctx: Context): String {
        val p = ctx.getSharedPreferences("soundwave", 0)
        // Hash identity only. Never read, copy, persist or log password/token values.
        return hash(canonical(JSONObject().put("user", p.getString("user", "").orEmpty())
            .put("logged_in", p.getBoolean("logged_in", false))))
    }
    private fun snapshot(ctx: Context): JSONObject = BackupStore.export(ctx).apply {
        val user = ctx.getSharedPreferences("soundwave", 0).getString("user", "").orEmpty()
        val avatar = AvatarStore.path(ctx, user).takeIf { it.isNotBlank() }?.let(::File)
        // BackupStore's current global bundle need not contain the actual visible avatar bytes.
        if (avatar?.isFile == true) check(avatar.length() <= 8L * 1024 * 1024) { "头像过大，无法安全保存恢复副本" }
        put("recoveryVisibleAvatar", if (avatar?.isFile == true)
            android.util.Base64.encodeToString(avatar.readBytes(), android.util.Base64.NO_WRAP) else "")
    }
    private fun fingerprint(ctx: Context) = hash(canonical(snapshot(ctx)))
    fun hasPending(ctx: Context): Boolean = runCatching { prefs(ctx).getString(ACTIVE, "").orEmpty().isNotBlank() }.getOrDefault(true)
    fun status(ctx: Context): Status = synchronized(lock) {
        if (!hasPending(ctx)) return@synchronized Status(false, "")
        val valid = runCatching { read(ctx); Unit }.isSuccess
        Status(true, if (valid) "上次恢复未完成。原资料副本和输入备份已保留；需重新确认后才能继续，期间不会自动上传。"
            else "未完成恢复的记录缺失或损坏。已阻止继续恢复和自动上传；保留现有资料与记录，不会自动覆盖。")
    }
    internal fun begin(ctx: Context, projectedInput: JSONObject) = synchronized(lock) {
        check(!hasPending(ctx)) { "请先处理上次未完成的恢复，不能用另一份备份覆盖恢复记录" }
        val id = UUID.randomUUID().toString()
        val owner = identity(ctx)
        val before = snapshot(ctx)
        check(owner == identity(ctx)) { "保存恢复副本期间身份变化；尚未写入资料" }
        val record = JSONObject().put("version", 1).put("id", id).put("identity", owner)
            .put("before", before).put("input", projectedInput)
        val body = canonical(record)
        val envelope = JSONObject().put("body", body).put("sha256", hash(body)).toString()
        val target = file(ctx, id)
        val dir = target.parentFile!!
        check(dir.isDirectory || dir.mkdirs()) { "无法保存恢复副本；尚未写入资料" }
        // filesDir is application-private; remove group/world bits on our own new directory/file.
        dir.setReadable(false, false); dir.setWritable(false, false); dir.setExecutable(false, false)
        dir.setReadable(true, true); dir.setWritable(true, true); dir.setExecutable(true, true)
        FileOutputStream(target).use { out -> out.write(envelope.toByteArray(Charsets.UTF_8)); out.flush(); out.fd.sync() }
        target.setReadable(false, false); target.setWritable(false, false)
        target.setReadable(true, true); target.setWritable(true, true)
        check(readEnvelope(target).getString("id") == id) { "恢复副本回读失败" }
        check(prefs(ctx).edit().putString(ACTIVE, id).putString(EXPECTED, hash(canonical(before)))
            .putString(STAGE, "prepared").commit()) { "恢复记录提交未确认；尚未写入资料" }
        id
    }
    private fun readEnvelope(file: File): JSONObject {
        val envelope = JSONObject(file.readText(Charsets.UTF_8))
        val body = envelope.getString("body")
        check(hash(body) == envelope.getString("sha256")) { "恢复记录校验失败" }
        return JSONObject(body).also { check(it.getInt("version") == 1) }
    }
    private fun read(ctx: Context): JSONObject {
        val id = prefs(ctx).getString(ACTIVE, "").orEmpty()
        val record = readEnvelope(file(ctx, id))
        check(record.getString("id") == id) { "恢复记录错配" }
        return record
    }
    fun review(ctx: Context): Review = synchronized(lock) {
        val record = read(ctx)
        check(identity(ctx) == record.getString("identity")) { "账号身份已变化；恢复记录和上传门禁保留，不会覆盖资料" }
        val state = fingerprint(ctx)
        check(state == prefs(ctx).getString(EXPECTED, "")) { "资料已变化或中断检查点未确认；恢复记录和上传门禁保留，不会覆盖新修改" }
        Review(record.getString("id"), state, record.getString("identity"))
    }
    internal fun inputForConfirmedReplay(ctx: Context, review: Review): JSONObject = synchronized(lock) {
        val current = review(ctx)
        check(current == review) { "确认后资料或身份已变化；请重新检查，未执行恢复" }
        read(ctx).getJSONObject("input")
    }
    internal fun verifyBeforeWrite(ctx: Context, id: String) {
        val current = review(ctx)
        check(current.id == id) { "恢复记录已变化；未执行恢复" }
    }
    internal fun checkIdentity(ctx: Context, id: String) {
        val record = read(ctx)
        check(record.getString("id") == id && record.getString("identity") == identity(ctx)) {
            "恢复期间身份变化；已停止并保留恢复记录"
        }
    }
    internal fun stage(ctx: Context, id: String, stage: String) {
        checkIdentity(ctx, id)
        check(prefs(ctx).edit().putString(STAGE, stage).commit()) { "恢复检查点提交未确认" }
    }
    internal fun interrupted(ctx: Context, id: String) {
        // Record a caught failure's current state, not a guess about where a killed process stopped.
        // Failure here leaves the older hash and therefore refuses unsafe replay.
        runCatching {
            checkIdentity(ctx, id)
            check(prefs(ctx).edit().putString(EXPECTED, fingerprint(ctx)).putString(STAGE, "interrupted").commit())
        }
    }
    internal fun complete(ctx: Context, id: String) {
        checkIdentity(ctx, id)
        check(prefs(ctx).edit().putString(ACTIVE, "").putString(STAGE, "complete").commit()) {
            "完成标记提交未确认；恢复副本仍保留"
        }
        // Do NOT delete input, before-image, avatar bytes or generic-restore provenance.
    }
    fun enforcePendingGate(ctx: Context) {
        if (hasPending(ctx)) ctx.getSharedPreferences("soundwave_userdata", 0).edit()
            .putBoolean(xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot.RESTORE_INVALIDATION_KEY, true).commit()
    }
}
