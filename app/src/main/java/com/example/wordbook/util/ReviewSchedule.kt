package com.example.wordbook.util

import com.example.wordbook.data.Word
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 艾宾浩斯复习调度的**唯一实现点**。
 * ViewModel（首页统计）、DailyNotifyWorker（每日通知）、桌面小组件三处都用它，
 * 避免"同一套间隔表被抄三遍"的逻辑漂移。
 */
object ReviewSchedule {

    /** 答对 N 次后的复习间隔（天） */
    val INTERVALS_DAYS = intArrayOf(1, 2, 4, 7, 15, 30)

    /** 连续答对多少次自动标记为已掌握 */
    const val MASTER_ON_CORRECT = 5

    private const val DAY_MS = 24L * 3600_000L

    /** 该词是否到了复习时间（已掌握的词永不进入复习队列） */
    fun isDue(word: Word, now: Long = System.currentTimeMillis()): Boolean {
        if (word.mastered) return false
        val base = if (word.lastReviewedAt > 0) word.lastReviewedAt else word.createdAt
        val index = word.reviewCorrectCount.coerceIn(0, INTERVALS_DAYS.size - 1)
        return now >= base + INTERVALS_DAYS[index] * DAY_MS
    }

    /** 今日待复习的词 */
    fun dueWords(words: List<Word>, now: Long = System.currentTimeMillis()): List<Word> =
        words.filter { isDue(it, now) }

    /** 今日待复习数量（小组件/通知用） */
    fun dueCount(words: List<Word>, now: Long = System.currentTimeMillis()): Int =
        words.count { isDue(it, now) }
}

/**
 * 复习战绩统计：
 * - 正确率来自词库累计的 totalCorrect / totalWrong（Room v3 新增列）
 * - 连击天数来自"有复习行为的日期"集合（存在 SharedPreferences，按天去重）
 */
object ReviewStats {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 把今天记入复习日期集合（按天去重，只保留最近 400 天，避免无限增长） */
    fun withToday(days: Set<String>): Set<String> {
        val today = LocalDate.now().format(DATE_FMT)
        return (days + today).sortedDescending().take(400).toSet()
    }

    /**
     * 连击天数：从今天（或昨天，给"今天还没复习"留缓冲）往前数连续有复习的天数。
     * 例：今天复习了、昨天复习了、前天没复习 → 2 天。
     */
    fun streakDays(days: Set<String>): Int {
        if (days.isEmpty()) return 0
        var cursor = LocalDate.now()
        // 今天还没开始复习时，从昨天起算（连击不因"今天尚未学习"而中断）
        if (cursor.format(DATE_FMT) !in days) cursor = cursor.minusDays(1)
        var streak = 0
        while (cursor.format(DATE_FMT) in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 本周（周一起算）有复习行为的天数 */
    fun weekReviewDays(days: Set<String>): Int {
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (0..6).count { offset ->
            monday.plusDays(offset.toLong()).format(DATE_FMT) in days
        }
    }

    /** 正确率百分比（0-100），没有复习记录时返回 -1 表示"暂无数据" */
    fun accuracyPercent(correct: Int, wrong: Int): Int {
        val total = correct + wrong
        if (total <= 0) return -1
        return Math.round(correct * 100f / total)
    }

    /** 今天是否已有复习行为（连击文案的分支依据） */
    fun isToday(days: Set<String>): Boolean = LocalDate.now().format(DATE_FMT) in days
}
