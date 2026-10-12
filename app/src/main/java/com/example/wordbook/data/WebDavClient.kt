package com.example.wordbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebDAV 同步客户端（自用场景，走最朴素的 PUT/GET）。
 *
 * 兼容坚果云、Nextcloud、群晖等标准 WebDAV：
 * - 上传：`PUT <文件 URL>`，body 是 JSON 快照
 * - 下载：`GET <文件 URL>`；文件不存在（404）视为"远端还没有快照"
 * - 探测：`PROPFIND` 目标目录（Depth: 0），用最少的请求判断地址/账号是否可用
 *
 * 注意：坚果云必须用「应用密码」而不是登录密码。
 */
class WebDavClient(
    private val fileUrl: String,
    private val username: String,
    private val password: String
) {

    /** 同步快照：整个词库 + 复习日期集合 */
    @Serializable
    data class Snapshot(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val device: String = "",
        val words: List<Word> = emptyList(),
        val reviewDays: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun authHeader(): String = Credentials.basic(username, password, Charsets.UTF_8)

    /** 只做一次 PROPFIND，用来验证地址与账号（不检查文件是否存在） */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (fileUrl.isBlank()) return@withContext false to "还没有填 WebDAV 地址"
        if (username.isBlank()) return@withContext false to "还没有填用户名"
        try {
            val dirUrl = fileUrl.substringBeforeLast('/', fileUrl) + "/"
            val request = Request.Builder()
                .url(dirUrl)
                .header("Authorization", authHeader())
                .header("Depth", "0")
                .method("PROPFIND", null)
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful || resp.code == 207 -> true to "连接成功，可以同步"
                    resp.code == 401 -> false to "认证失败（401）：坚果云请使用「应用密码」，不是登录密码"
                    resp.code == 404 -> false to "地址不存在（404）：请先在网盘里建好文件夹，URL 指向文件路径"
                    else -> false to describe(resp.code) + readError(resp.body?.string())
                }
            }
        } catch (e: Exception) {
            false to "网络异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 下载远端快照；远端还没有文件时返回 (null, null) */
    suspend fun download(): Pair<Snapshot?, String?> = withContext(Dispatchers.IO) {
        if (fileUrl.isBlank()) return@withContext null to "还没有填 WebDAV 地址"
        try {
            val request = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authHeader())
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> null to null
                    !resp.isSuccessful -> null to (describe(resp.code) + readError(resp.body?.string()))
                    else -> {
                        val text = resp.body?.string().orEmpty()
                        if (text.isBlank()) null to null
                        else try {
                            json.decodeFromString(Snapshot.serializer(), text) to null
                        } catch (e: Exception) {
                            null to "远端文件不是本 APP 的快照格式（解析失败）"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null to "网络异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 上传快照（覆盖写） */
    suspend fun upload(snapshot: Snapshot): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (fileUrl.isBlank()) return@withContext false to "还没有填 WebDAV 地址"
        try {
            val body = json.encodeToString(Snapshot.serializer(), snapshot)
            val request = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authHeader())
                .put(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> true to "上传成功（${snapshot.words.size} 个单词）"
                    resp.code == 401 -> false to "认证失败（401）：坚果云请使用「应用密码」"
                    resp.code == 409 -> false to "父目录不存在（409）：请先在网盘里创建文件夹"
                    else -> false to (describe(resp.code) + readError(resp.body?.string()))
                }
            }
        } catch (e: Exception) {
            false to "网络异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun describe(code: Int): String = when (code) {
        401 -> "认证失败（401）"
        403 -> "没有写入权限（403）"
        404 -> "路径不存在（404）"
        405 -> "服务端不允许该操作（405）"
        409 -> "父目录不存在（409）"
        423 -> "文件被锁定（423）"
        507 -> "网盘空间不足（507）"
        in 500..599 -> "服务端异常（$code）"
        else -> "HTTP $code"
    }

    private fun readError(body: String?): String {
        val snippet = body?.take(200)?.replace("\n", " ")?.trim().orEmpty()
        return if (snippet.isBlank()) "" else "：$snippet"
    }
}
