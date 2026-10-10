package com.example.wordbook.data

/**
 * AI 服务商预设。
 *
 * 关键洞察：国内外主流厂商都提供 **OpenAI 兼容的 /chat/completions** 协议，
 * 差别只有 baseUrl / model / apiKey 三个变量，所以不需要为每家写适配器。
 * 少数厂商不支持 response_format=json_object，由 AiClient 自动去掉该参数重试。
 */
data class AiProvider(
    val id: String,
    val displayName: String,
    /** 不含 /chat/completions 的根地址，如 https://api.deepseek.com/v1 */
    val baseUrl: String,
    val defaultModel: String,
    /** 是否支持 response_format={"type":"json_object"} */
    val supportsJsonMode: Boolean = true,
    /** 申请 key 的地方（显示在设置页） */
    val hint: String = ""
)

object AiProviders {

    const val CUSTOM_ID = "custom"

    val ALL: List<AiProvider> = listOf(
        AiProvider(
            id = "deepseek",
            displayName = "DeepSeek（深度求索）",
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            supportsJsonMode = true,
            hint = "platform.deepseek.com"
        ),
        AiProvider(
            id = "moonshot",
            displayName = "Kimi（月之暗面）",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            supportsJsonMode = true,
            hint = "platform.moonshot.cn"
        ),
        AiProvider(
            id = "dashscope",
            displayName = "通义千问（阿里云百炼）",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            supportsJsonMode = true,
            hint = "bailian.console.aliyun.com"
        ),
        AiProvider(
            id = "zhipu",
            displayName = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            supportsJsonMode = true,
            hint = "open.bigmodel.cn"
        ),
        AiProvider(
            id = "siliconflow",
            displayName = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            supportsJsonMode = true,
            hint = "cloud.siliconflow.cn"
        ),
        AiProvider(
            id = "openrouter",
            displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "openai/gpt-4o-mini",
            supportsJsonMode = true,
            hint = "openrouter.ai/keys"
        ),
        AiProvider(
            id = "openai",
            displayName = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            supportsJsonMode = true,
            hint = "platform.openai.com"
        ),
        AiProvider(
            id = CUSTOM_ID,
            displayName = "自定义（OpenAI 兼容）",
            baseUrl = "",
            defaultModel = "",
            supportsJsonMode = true,
            hint = "填自己的中转站 / 局域网模型（Ollama、vLLM 等）"
        )
    )

    fun byId(id: String?): AiProvider =
        ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** 兼容 OpenAI 风格的多模态/代理站可能要求额外头，这里预留 */
    fun extraHeaders(provider: AiProvider): Map<String, String> = when (provider.id) {
        "openrouter" -> mapOf("HTTP-Referer" to "https://localhost/wordbook", "X-Title" to "WordBook")
        else -> emptyMap()
    }
}
