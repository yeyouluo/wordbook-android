package com.example.wordbook.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 统一的 AI 客户端：任何 OpenAI 兼容的 /chat/completions 服务都能用
 * （DeepSeek / Kimi / 通义千问 / 智谱 / 硅基流动 / OpenRouter / OpenAI / 自定义）。
 *
 * 兼容性策略：
 * - response_format=json_object 只在厂商支持且本次调用需要 JSON 时带；
 * - 若带该参数返回失败（部分厂商 400），自动去掉该参数重试一次 —— 提示词里已要求"只输出 JSON"，
 *   加上现有的 `{...}` 区间截取与字段宽容解析，足以兜住建模质量差异。
 */
class AiClient(
    private val provider: AiProvider,
    private val apiKey: String,
    private val model: String = provider.defaultModel,
    private val baseUrl: String = provider.baseUrl
) {

    data class LookupResult(
        val phonetic: String,
        val meaning: String,
        val example: String,
        val mnemonic: String,
        val family: List<FamilyItem> = emptyList()
    )

    /** 同根词条目：w=单词，m=一句话中文释义（允许缺失） */
    data class FamilyItem(val w: String, val m: String)

    /** 会话窗口返回的「待入库词条」 */
    data class DraftWord(
        val spelling: String,
        val phonetic: String,
        val meaning: String,
        val example: String,
        val mnemonic: String,
        val family: List<FamilyItem> = emptyList()
    )

    @Serializable
    data class LookupPayload(
        val phonetic: String = "",
        val meaning: String = "",
        val example: String = "",
        val mnemonic: String = "",
        // family 宽松解析：模型可能输出字符串数组或对象数组
        val family: kotlinx.serialization.json.JsonElement? = null
    )

    @Serializable
    data class DraftWordPayload(
        val spelling: String = "",
        val phonetic: String = "",
        val meaning: String = "",
        val example: String = "",
        val mnemonic: String = "",
        val family: kotlinx.serialization.json.JsonElement? = null
    )

    /**
     * 会话接口的返回信封：一次调用同时给出「给用户看的文字」与「可入库的结构化词条」。
     * 意图识别交给模型：用户只是提问时 words 为空数组。
     */
    @Serializable
    data class ChatEnvelope(
        val reply: String = "",
        val words: List<DraftWordPayload> = emptyList()
    )

    /** 会话结果 → 干净的领域对象（过滤空 spelling，补齐缺省字段） */
    fun toDrafts(envelope: ChatEnvelope): List<DraftWord> = envelope.words.mapNotNull { p ->
        val spelling = p.spelling.trim()
        if (spelling.isEmpty()) null
        else DraftWord(
            spelling = spelling,
            phonetic = p.phonetic.trim(),
            meaning = p.meaning.trim(),
            example = p.example.trim(),
            mnemonic = p.mnemonic.trim(),
            family = parseFamily(p.family)
        )
    }

    /** 把 JsonElement 形态的 family 归一为 List<FamilyItem> */
    private fun parseFamily(element: kotlinx.serialization.json.JsonElement?): List<FamilyItem> {
        val arr = (element as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is kotlinx.serialization.json.JsonPrimitive ->
                    FamilyItem(w = item.content.trim(), m = "")
                is kotlinx.serialization.json.JsonObject -> {
                    fun str(key: String): String? =
                        (item[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val w = str("w") ?: str("word") ?: str("spelling")
                    val m = str("m") ?: str("meaning") ?: str("cn")
                    if (!w.isNullOrBlank()) FamilyItem(w = w.trim(), m = (m ?: "").trim()) else null
                }
                else -> null
            }
        }.filter { it.w.isNotBlank() }
    }

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        @SerialName("response_format") val responseFormat: ResponseFormat? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        val temperature: Double? = null
    )

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatChoice(val message: ChatMessage)

    @Serializable
    private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 输入一个单词，让 AI 返回音标/释义/例句/记忆法/同根词 */
    suspend fun lookup(spelling: String): LookupResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !isConfigured()) return@withContext null

        val system = ChatMessage(
            role = "system",
            content = "你是英语单词学习助手。用户给你一个英文单词，你只返回一个 JSON 对象，" +
                "字段为：phonetic(含 IPA 音标)、meaning(简体中文释义，多词性用换行分隔)、" +
                "example(一个地道常用英文例句，单行)、mnemonic(词根/联想记忆法，中文，一句话)、" +
                "family(同词根家族词数组，3-6 个常见派生词；每项是一个 JSON 对象，" +
                "形如{\"w\":\"单词\",\"m\":\"不超过10字的中文简短释义\"},按词频从高到低排序)。" +
                "不要输出 JSON 以外的任何内容。"
        )
        val content = callCompletions(listOf(system, ChatMessage(role = "user", content = spelling)), jsonOnly = true)
            ?: return@withContext null
        parseLookupJson(content)
    }

    /**
     * 围绕某个单词的多轮问答（用法陷阱、辨析、词源等）——单词详情页使用，纯文字回复。
     * history 为 (role, content) 对，可包含之前的 user/assistant 消息。
     */
    suspend fun chat(history: List<Pair<String, String>>): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !isConfigured() || history.isEmpty()) return@withContext null
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(
            role = "system",
            content = "你是英语单词学习助手，用户正在背单词。请用简体中文简洁回答（不超过 150 字），" +
                "重点讲用法、辨析、例句或记忆技巧。"
        )
        messages += history.map { ChatMessage(role = it.first, content = it.second) }
        callCompletions(messages, jsonOnly = false)
    }

    /**
     * AI 会话窗口：一次调用既回文字、又回「可入库的结构化词条」。
     * - 用户贴一批单词 → words 里给出每个词的全字段（不额外逐词调用，省时省钱）
     * - 用户只是提问 → words 为空数组
     * 意图识别交给模型（system prompt 里写明契约），APP 不再写规则判断。
     */
    suspend fun converse(history: List<Pair<String, String>>): ChatEnvelope? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !isConfigured() || history.isEmpty()) return@withContext null
        val system = ChatMessage(
            role = "system",
            content = "你是单词本 APP 里的英语学习助手。你必须只返回一个 JSON 对象：" +
                "{\"reply\":\"给用户看的简体中文回复（不超过 200 字，可含要点）\"," +
                "\"words\":[{\"spelling\":\"单词\",\"phonetic\":\"音标\",\"meaning\":\"中文释义\"," +
                "\"example\":\"一个地道例句\",\"mnemonic\":\"一句话记忆法\"," +
                "\"family\":[{\"w\":\"同根词\",\"m\":\"不超过10字中文释义\"}]}]}。" +
                "规则：只有当用户消息里包含需要加入词库的英文单词时才填 words，" +
                "每个词字段尽量填全（family 3-6 个，按词频排序）；" +
                "words 最多 20 个，超出时只取前 20 个并在 reply 里说明；" +
                "用户只是提问、聊天、问用法时 words 必须是空数组 []。" +
                "不要输出 JSON 以外的任何内容。"
        )
        val messages = mutableListOf(system)
        messages += history.map { ChatMessage(role = it.first, content = it.second) }
        val content = callCompletions(messages, jsonOnly = true) ?: return@withContext null
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return@withContext null
        try {
            json.decodeFromString(ChatEnvelope.serializer(), content.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    /** 设置页「测试连接」：返回 (是否成功, 给用户看的原因) */
    suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext false to "还没有填 API Key"
        if (!isConfigured()) return@withContext false to "baseUrl 或模型名为空"
        try {
            val body = json.encodeToString(
                ChatRequest.serializer(),
                ChatRequest(
                    model = model,
                    messages = listOf(ChatMessage(role = "user", content = "ping")),
                    responseFormat = null,
                    maxTokens = 1
                )
            )
            val request = buildRequest(body)
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    true to "连接成功（${provider.displayName} · $model）"
                } else {
                    val err = resp.body?.string()?.take(200) ?: ""
                    false to "HTTP ${resp.code}：$err"
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "网络异常")
        }
    }

    private fun isConfigured(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()

    private fun buildRequest(bodyJson: String): Request {
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
        AiProviders.extraHeaders(provider).forEach { (k, v) -> builder.addHeader(k, v) }
        return builder.post(bodyJson.toRequestBody("application/json".toMediaType())).build()
    }

    private suspend fun callCompletions(
        messages: List<ChatMessage>,
        jsonOnly: Boolean
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val wantJson = jsonOnly && provider.supportsJsonMode
        val first = doCall(messages, wantJson)
        // 厂商不支持 response_format 时会 400：去掉该参数再试一次
        if (first == null && wantJson) doCall(messages, false) else first
    }

    private fun doCall(messages: List<ChatMessage>, withJsonMode: Boolean): String? {
        return try {
            val body = json.encodeToString(
                ChatRequest.serializer(),
                ChatRequest(
                    model = model,
                    messages = messages,
                    responseFormat = if (withJsonMode) ResponseFormat(type = "json_object") else null,
                    temperature = 0.6
                )
            )
            client.newCall(buildRequest(body)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                val chatResp = json.decodeFromString(ChatResponse.serializer(), text)
                chatResp.choices.firstOrNull()?.message?.content
            }
        } catch (e: Exception) {
            null
        }
    }

    /** JSON 文本 → LookupResult（lookup 与 converse 共用同一套字段映射） */
    private fun parseLookupJson(content: String): LookupResult? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.decodeFromString(LookupPayload.serializer(), content.substring(start, end + 1))
                .toLookupResult()
        } catch (e: Exception) {
            null
        }
    }

    /** LookupPayload → LookupResult：唯一转换点，避免两处字段映射漂移 */
    private fun LookupPayload.toLookupResult(): LookupResult = LookupResult(
        phonetic = phonetic.trim(),
        meaning = meaning.trim(),
        example = example.trim(),
        mnemonic = mnemonic.trim(),
        family = parseFamily(family)
    )
}
