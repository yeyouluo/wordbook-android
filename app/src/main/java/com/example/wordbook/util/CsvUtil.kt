package com.example.wordbook.util

import com.example.wordbook.data.Word
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV 导出/导入/备份共用工具，UI 与后台 Worker 都用它，保证格式一致 */
object CsvUtil {

    val HEADER = listOf(
        "单词", "音标", "释义", "例句", "记忆法", "同根词", "已掌握",
        "复习次数", "复习正确次数", "累计答对", "累计答错", "上次复习", "添加时间"
    )

    // 日期是"数据格式"而不是"展示格式"：强制 Locale.US，
    // 否则在泰历/和历等区域会输出 2569 年这种本地历年，导入回环就断了
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** 解析结果：表头（若有）+ 数据行 */
    data class CsvTable(val header: List<String>?, val rows: List<List<String>>)

    fun buildCsv(words: List<Word>): String {
        val sb = StringBuilder()
        // BOM：让 Excel 正确识别 UTF-8
        sb.append('\uFEFF')
        sb.append(HEADER.joinToString(",")).append('\n')
        words.forEach { w ->
            val row = listOf(
                w.spelling,
                w.phonetic,
                w.meaning,
                w.example,
                w.mnemonic,
                w.family,
                if (w.mastered) "是" else "否",
                w.reviewCount.toString(),
                w.reviewCorrectCount.toString(),
                w.totalCorrect.toString(),
                w.totalWrong.toString(),
                if (w.lastReviewedAt > 0) fmt.format(Date(w.lastReviewedAt)) else "",
                fmt.format(Date(w.createdAt))
            ).joinToString(",") { escape(it) }
            sb.append(row).append('\n')
        }
        return sb.toString()
    }

    /**
     * RFC-4180 风格 CSV 解析：支持双引号包裹、字段内逗号/换行、双引号转义。
     * 返回每行的字段列表。
     */
    fun parseCsv(text: String): CsvTable {
        val rows = mutableListOf<List<String>>()
        var i = 0
        val n = text.length
        var field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var afterQuote = false

        fun endField() {
            row.add(field.toString())
            field = StringBuilder()
            afterQuote = false
        }

        fun endRow() {
            if (row.isNotEmpty() || field.isNotEmpty()) {
                row.add(field.toString())
                rows.add(row)
                field = StringBuilder()
                row = mutableListOf()
            }
            afterQuote = false
        }

        while (i < n) {
            val c = text[i]
            when {
                inQuotes -> when (c) {
                    '"' -> {
                        if (i + 1 < n && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                            afterQuote = true
                        }
                    }
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> endField()
                c == '\r' -> { /* CR-LF */
                    if (i + 1 < n && text[i + 1] == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        endRow()
        val cleaned = rows.filter { it.any { s -> s.isNotBlank() } }
        // 第一行以「单词」开头 → 视为表头，单独返回（导入时按列名匹配，兼容新旧格式）
        val first = cleaned.firstOrNull()
        val firstCell = first?.firstOrNull()?.trim()?.removePrefix("\uFEFF")
        return if (first != null && firstCell == "单词") {
            CsvTable(
                header = first.map { it.trim().removePrefix("\uFEFF") },
                rows = cleaned.drop(1)
            )
        } else {
            CsvTable(header = null, rows = cleaned)
        }
    }

    private fun escape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }
}
