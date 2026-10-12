package com.example.wordbook.data

import android.content.Context
import com.example.wordbook.util.CsvUtil
import com.example.wordbook.util.SyncCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 同步客户端（P0）。
 *
 * 目标服务：任意标准 WebDAV；默认预填 Seafile（现网 http://47.121.121.215:9998，
 * 路径 /seafdav/yeyouluoVault/单词本）。
 *
 * 方法语义（按需求约定）：
 * - PROPFIND → 207 视为成功（也接受 200）
 * - MKCOL    → 201 新建成功；405 视为"目录已存在"（两者都算通过）
 * - PUT      → 200 / 201 / 204 成功
 * - GET      → 404 返回 null（远端还没有文件）
 *
 * 远端文件只有一个：`wordbook-sync.json = {meta:{rev,exportedAt,device}, words:[...]}`
 * 注意：路径含中文，必须用 HttpUrl.addPathSegment 逐段编码（不要手拼字符串）。
 */
class WebDavSync(
    private val server: String,
    private val basePath: String,
    private val username: String,
    private val password: String
) {

    @Serializable
    data class Meta(
        val rev: String = "",
        val exportedAt: Long = 0L,
        val device: String = ""
    )

    @Serializable
    data class Snapshot(
        val meta: Meta = Meta(),
        val words: List<Word> = emptyList()
    )

    /** 同步结果：要么拿到快照，要么拿到错误；404 时 snapshot=null 且 error=null */
    data class DownloadResult(val snapshot: Snapshot?, val error: String? = null, val notFound: Boolean = false)

    companion object {
        const val FILE_NAME = "wordbook-sync.json"

        /** 默认预填：现网 Seafile */
        const val DEFAULT_SERVER = "http://47.121.121.215:9998"
        const val DEFAULT_BASE_PATH = "/seafdav/yeyouluoVault/单词本"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun auth(): String = Credentials.basic(username, password, Charsets.UTF_8)

    private fun baseUrl(): HttpUrl? {
        val root = server.trim().trimEnd('/').let { if (it.isEmpty()) return null else "$it/" }
        val builder = root.toHttpUrlOrNull()?.newBuilder() ?: return null
        basePath.split('/').filter { it.isNotBlank() }.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun fileUrl(): HttpUrl? = baseUrl()?.newBuilder()?.addPathSegment(FILE_NAME)?.build()

    private fun configured(): String? = when {
        server.isBlank() -> "服务器地址为空"
        basePath.isBlank() -> "同步路径为空"
        username.isBlank() -> "账号为空"
        password.isBlank() -> "密码为空"
        baseUrl() == null -> "服务器地址不是合法的 http(s) URL"
        else -> null
    }

    /** 测试连接：对目录做 PROPFIND（Depth: 0），207/200 才算通 */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        configured()?.let { return@withContext false to it }
        try {
            val request = Request.Builder()
                .url(baseUrl()!!)
                .header("Authorization", auth())
                .header("Depth", "0")
                .method("PROPFIND", null)
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    207, 200 -> true to "连接成功（207 Multi-Status）"
                    401, 403 -> false to "账号或密码不对（HTTP ${resp.code}）"
                    404 -> false to "路径不存在（404）：确认同步路径，或先让 App 自动创建"
                    else -> false to describe(resp.code) + snippet(resp.body?.string())
                }
            }
        } catch (e: Exception) {
            false to "网络异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 确保目录存在：MKCOL；201 新建成功，405 表示已存在（也算通过） */
    suspend fun ensureDirectory(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        configured()?.let { return@withContext false to it }
        try {
            val request = Request.Builder()
                .url(baseUrl()!!)
                .header("Authorization", auth())
                .method("MKCOL", null)
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    201 -> true to "目录已创建"
                    405 -> true to "目录已存在"
                    401, 403 -> false to "账号或密码不对（HTTP ${resp.code}）"
                    409 -> false to "上级目录不存在（409）"
                    else -> false to describe(resp.code) + snippet(resp.body?.string())
                }
            }
        } catch (e: Exception) {
            false to "网络异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 下载快照：404 → notFound=true（远端还没有文件） */
    suspend fun download(): DownloadResult = withContext(Dispatchers.IO) {
        configured()?.let { return@withContext DownloadResult(null, it) }
        try {
            val request = Request.Builder()
                .url(fileUrl()!!)
                .header("Authorization", auth())
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> DownloadResult(null, null, notFound = true)
                    !resp.isSuccessful -> DownloadResult(null, describe(resp.code) + snippet(resp.body?.string()))
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        if (text.isBlank()) DownloadResult(null, null, notFound = true)
                        else try {
                            DownloadResult(json.decodeFromString(Snapshot.serializer(), text))
                        } catch (e: Exception) {
                            DownloadResult(null, "远端文件不是本 App 的快照格式（JSON 解析失败）")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DownloadResult(null, "网络异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 上传快照（整体覆盖写，带 If-Match 无意义——LWW 策略下直接覆盖） */
    suspend fun upload(snapshot: Snapshot): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        configured()?.let { return@withContext false to it }
        try {
            val body = json.encodeToString(Snapshot.serializer(), snapshot)
            val request = Request.Builder()
                .url(fileUrl()!!)
                .header("Authorization", auth())
                .put(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200, 201, 204 -> true to "上传成功（${snapshot.words.size} 个单词）"
                    401, 403 -> false to "账号或密码不对 / 无权限（HTTP ${resp.code}）"
                    409 -> false to "父目录不存在（409）"
                    507 -> false to "网盘空间不足（507）"
                    else -> false to describe(resp.code) + snippet(resp.body?.string())
                }
            }
        } catch (e: Exception) {
            false to "网络异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun describe(code: Int): String = when (code) {
        401 -> "认证失败（401）"
        403 -> "没有权限（403）"
        404 -> "路径不存在（404）"
        405 -> "服务端不允许该操作（405）"
        409 -> "父目录不存在（409）"
        423 -> "文件被锁定（423）"
        507 -> "空间不足（507）"
        in 500..599 -> "服务端异常（$code）"
        else -> "HTTP $code"
    }

    private fun snippet(body: String?): String {
        val s = body?.take(160)?.replace("\n", " ")?.trim().orEmpty()
        return if (s.isBlank()) "" else "：$s"
    }
}

/** 一次同步的结果（message 直接面向用户） */
data class SyncResult(val ok: Boolean, val message: String)

/**
 * 端到端同步执行器：ViewModel 与 WorkManager Worker **共用这一份逻辑**。
 * 配置与同步锚点全部走独立 SharedPreferences 键（与 AI key 同等隐私待遇）：
 * sync_server / sync_base_path / sync_user / sync_pass / sync_auto / sync_interval_minutes
 * 锚点：sync_last_fp（本机内容摘要）、sync_last_remote_rev（远端 rev）、sync_last_at（上次同步时间）
 */
class SyncRunner(private val context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val dao = AppDatabase.get(context).wordDao()

    fun server(): String = prefs.getString(K_SERVER, WebDavSync.DEFAULT_SERVER) ?: ""
    fun basePath(): String = prefs.getString(K_PATH, WebDavSync.DEFAULT_BASE_PATH) ?: ""
    fun user(): String = prefs.getString(K_USER, "") ?: ""
    fun password(): String = prefs.getString(K_PASS, "") ?: ""
    fun auto(): Boolean = prefs.getBoolean(K_AUTO, true)
    fun intervalMinutes(): Int = prefs.getInt(K_INTERVAL, 30)
    fun lastSyncAt(): Long = prefs.getLong(K_LAST_AT, 0L)
    fun lastRemoteRev(): String = prefs.getString(K_LAST_REV, "") ?: ""
    fun lastError(): String = prefs.getString(K_LAST_ERR, "") ?: ""

    fun configured(): Boolean =
        server().isNotBlank() && basePath().isNotBlank() && user().isNotBlank() && password().isNotBlank()

    fun saveConfig(
        server: String,
        basePath: String,
        user: String,
        password: String,
        auto: Boolean,
        intervalMinutes: Int
    ) {
        prefs.edit()
            .putString(K_SERVER, server.trim())
            .putString(K_PATH, basePath.trim())
            .putString(K_USER, user.trim())
            .putString(K_PASS, password.trim())
            .putBoolean(K_AUTO, auto)
            .putInt(K_INTERVAL, intervalMinutes)
            .apply()
    }

    /** 重置同步状态：只清锚点（不动词库、不动凭据） */
    fun resetAnchors() {
        prefs.edit()
            .remove(K_LAST_FP)
            .remove(K_LAST_REV)
            .remove(K_LAST_AT)
            .remove(K_LAST_ERR)
            .apply()
    }

    private fun client(): WebDavSync = WebDavSync(server(), basePath(), user(), password())

    suspend fun test(): Pair<Boolean, String> = client().testConnection()

    /**
     * 执行一次同步（三态触发共用）：LWW 冲突策略 + 败者自动备份。
     * 失败一律返回 SyncResult(ok=false)，不抛异常（静默失败由调用方决定）。
     */
    suspend fun run(): SyncResult = withContext(Dispatchers.IO) {
        if (!configured()) return@withContext SyncResult(false, "同步未配置：请填写服务器、路径、账号与密码")
        try {
            val client = client()
            val local = dao.getAllOnce()
            val localFp = SyncCore.fingerprint(local)
            val lastFp = SyncCore.Fingerprint.decode(prefs.getString(K_LAST_FP, null))
            val lastRev = prefs.getString(K_LAST_REV, null)
            val lastSyncAt = prefs.getLong(K_LAST_AT, 0L)

            val dl = client.download()
            if (dl.error != null) return@withContext fail("下载失败：${dl.error}")

            val remote = dl.snapshot
            val decision = SyncCore.decide(
                localFp = localFp,
                lastFp = lastFp,
                remoteRev = remote?.meta?.rev,
                lastRemoteRev = lastRev,
                remoteExists = remote != null,
                remoteExportedAt = remote?.meta?.exportedAt ?: 0L,
                lastSyncAt = lastSyncAt
            )

            when (decision.action) {
                SyncCore.Action.NONE ->
                    success("两端一致，无需同步")

                SyncCore.Action.PUSH_FIRST, SyncCore.Action.PUSH -> {
                    client.ensureDirectory()
                    val snapshot = makeSnapshot(local)
                    val (ok, msg) = client.upload(snapshot)
                    if (!ok) return@withContext fail("上传失败：$msg")
                    saveAnchors(localFp.encode(), snapshot.meta.rev)
                    success("已上传 ${local.size} 个单词（${decision.reason}）")
                }

                SyncCore.Action.PULL -> {
                    // pull 全量覆盖前先备份本机
                    val backup = writeBackupCsv(local, "local-before-pull")
                    dao.replaceAll(remote!!.words)
                    saveAnchors(SyncCore.fingerprint(remote.words).encode(), remote.meta.rev)
                    success("已拉取 ${remote.words.size} 个单词覆盖本机（覆盖前备份：$backup）")
                }

                SyncCore.Action.CONFLICT_PULL -> {
                    // 败者 = 本机
                    val backup = writeBackupCsv(local, "conflict-local")
                    dao.replaceAll(remote!!.words)
                    saveAnchors(SyncCore.fingerprint(remote.words).encode(), remote.meta.rev)
                    success(SyncCore.conflictMessage("远端", backup))
                }

                SyncCore.Action.CONFLICT_PUSH -> {
                    // 败者 = 远端
                    val backup = writeBackupCsv(remote!!.words, "conflict-remote")
                    client.ensureDirectory()
                    val snapshot = makeSnapshot(local)
                    val (ok, msg) = client.upload(snapshot)
                    if (!ok) return@withContext fail("上传失败：$msg")
                    saveAnchors(localFp.encode(), snapshot.meta.rev)
                    success(SyncCore.conflictMessage("本机", backup))
                }
            }
        } catch (e: Exception) {
            fail("同步异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun makeSnapshot(words: List<Word>): WebDavSync.Snapshot = WebDavSync.Snapshot(
        meta = WebDavSync.Meta(
            rev = SyncCore.fingerprint(words).encode(),
            exportedAt = System.currentTimeMillis(),
            device = android.os.Build.MODEL ?: "android"
        ),
        words = words
    )

    private fun saveAnchors(fingerprint: String, rev: String) {
        prefs.edit()
            .putString(K_LAST_FP, fingerprint)
            .putString(K_LAST_REV, rev)
            .putLong(K_LAST_AT, System.currentTimeMillis())
            .remove(K_LAST_ERR)
            .apply()
    }

    private fun success(message: String): SyncResult {
        prefs.edit().remove(K_LAST_ERR).apply()
        return SyncResult(true, message)
    }

    private fun fail(message: String): SyncResult {
        // 只记录给设置页看，不写日志明文（凭据永不落日志）
        prefs.edit().putString(K_LAST_ERR, message).apply()
        return SyncResult(false, message)
    }

    /** 冲突备份 CSV：进入与每日备份相同的清理管线（保留最近 7 份 wordbook-*.csv） */
    private fun writeBackupCsv(words: List<Word>, side: String): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Documents").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "wordbook-sync-$side-$ts.csv")
        file.writeText(CsvUtil.buildCsv(words))
        dir.listFiles { f -> f.name.startsWith("wordbook-") && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.name }
            ?.drop(7)
            ?.forEach { it.delete() }
        return file.name
    }

    companion object {
        const val K_SERVER = "sync_server"
        const val K_PATH = "sync_base_path"
        const val K_USER = "sync_user"
        const val K_PASS = "sync_pass"
        const val K_AUTO = "sync_auto"
        const val K_INTERVAL = "sync_interval_minutes"
        private const val K_LAST_FP = "sync_last_fp"
        private const val K_LAST_REV = "sync_last_remote_rev"
        private const val K_LAST_AT = "sync_last_at"
        private const val K_LAST_ERR = "sync_last_error"
    }
}
