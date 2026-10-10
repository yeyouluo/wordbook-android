package com.example.wordbook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.wordbook.util.WordTextUtils

/** 接收其他 APP 分享的文本：提取单词后转给主界面并自动弹出添加框 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        // 抠词逻辑与批量查词共用同一套纯函数，避免两处正则漂移
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val word = WordTextUtils.candidateFrom(firstLine) ?: firstLine
        val toMain = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_PENDING_WORD, word)
        }
        startActivity(toMain)
        finish()
    }
}
