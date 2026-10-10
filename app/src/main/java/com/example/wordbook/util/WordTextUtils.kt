package com.example.wordbook.util

import com.example.wordbook.data.AiClient

/**
 * 文本 → 单词候选 的纯函数工具（批量查词、分享接收共用）。
 * 无 Android 依赖，便于日后直接写单测。
 */
object WordTextUtils {

    /** 严格的"单个英文单词"（允许连字符与撇号） */
    private val STRICT_WORD = Regex("^[A-Za-z][A-Za-z\\-'’]{1,40}$")

    /** 从一段文本里找第一个英文单词 */
    private val FIND_WORD = Regex("[A-Za-z][A-Za-z\\-'’]{1,40}")

    /** token 分隔符：空白、逗号、分号、顿号、竖线、制表 */
    private val SPLIT = Regex("[\\s,，;；、|\\t]+")

    private const val MAX_WORD_LEN = 40

    /**
     * 从一行/一个 token 里抠出候选单词：
     * - 整段就是纯英文单词 → 直接返回（大小写与撇号保留原样）
     * - 夹带中文、词性、音标（如 "resign v.辞职"）→ 返回第一个英文单词
     * - 抠不到 → null
     */
    fun candidateFrom(text: String): String? {
        val trimmed = text.trim().trim(
            '.', '。', ',', '，', ';', '；', ':', '：', '!', '！', '?', '？',
            '"', '\'', '“', '”', '‘', '’', '(', ')', '（', '）', '[', ']', '【', '】', '-', '—'
        )
        if (trimmed.isEmpty()) return null
        if (STRICT_WORD.matches(trimmed)) return trimmed
        return FIND_WORD.find(trimmed)?.value
    }

    /**
     * 批量解析输入文本。
     * @param existing 已存在的单词集合（小写），用于跳过库内重复
     * @param max 单批最多接受多少个词（超出计入 truncated）
     */
    fun parseBatch(input: String, existing: Set<String>, max: Int = 100): BatchParse {
        val accepted = ArrayList<String>()
        val duplicates = ArrayList<String>()
        val invalid = ArrayList<String>()
        var truncated = 0

        val seen = HashSet<String>()
        existing.forEach { seen.add(it.lowercase()) }

        for (rawLine in input.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            for (token in SPLIT.split(line)) {
                if (token.isBlank()) continue
                val candidate = candidateFrom(token)
                if (candidate == null || candidate.length > MAX_WORD_LEN) {
                    invalid.add(if (token.length > 30) token.take(30) + "…" else token)
                    continue
                }
                val key = candidate.lowercase()
                if (!seen.add(key)) {
                    duplicates.add(candidate)
                    continue
                }
                if (accepted.size >= max) {
                    truncated++
                    continue
                }
                accepted.add(candidate)
            }
        }
        return BatchParse(
            accepted = accepted,
            duplicates = duplicates,
            invalid = invalid,
            truncated = truncated
        )
    }

    /** 同根词列表 → 存储文本，如 "resignation(辞职), resigned(已辞职的)" */
    fun familyText(items: List<AiClient.FamilyItem>): String =
        items.joinToString(", ") { item ->
            if (item.m.isNotBlank()) "${item.w}(${item.m})" else item.w
        }
}

data class BatchParse(
    val accepted: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
    val truncated: Int = 0
)
