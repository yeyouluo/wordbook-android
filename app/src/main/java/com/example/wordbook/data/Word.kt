package com.example.wordbook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单词本里的一条记录。
 * - spelling：单词本身
 * - phonetic / meaning / example / mnemonic / family：音标、释义、例句、记忆技巧、同根词（AI 生成）
 * - mastered：是否已掌握
 * - reviewCount / reviewCorrectCount / lastReviewedAt：复习调度（艾宾浩斯）
 * - totalCorrect / totalWrong：累计答对/答错次数（算正确率，答错不丢历史）
 */
@Entity(tableName = "words")
data class Word(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spelling: String,
    val phonetic: String = "",
    val meaning: String = "",
    val example: String = "",
    val mnemonic: String = "",
    val family: String = "",
    val mastered: Boolean = false,
    val reviewCount: Int = 0,
    val reviewCorrectCount: Int = 0,
    val lastReviewedAt: Long = 0,
    val totalCorrect: Int = 0,
    val totalWrong: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
