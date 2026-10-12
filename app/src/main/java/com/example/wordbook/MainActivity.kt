package com.example.wordbook

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.wordbook.ui.WordBookApp
import com.example.wordbook.ui.appColorScheme
import java.util.Locale

/**
 * 单词本主入口。
 * Activity 只负责三件事：TTS 生命周期、Intent 定向入口（分享/快捷方式 pending）、主题宿主。
 * 所有界面在 ui 包下。
 */
class MainActivity : ComponentActivity() {

    /** Activity 持有 VM：离开 App 时要触发「自动云同步」 */
    private val vm: WordBookViewModel by viewModels()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null

    private var pendingWord by mutableStateOf<String?>(null)
    private var pendingAdd by mutableStateOf(false)
    private var pendingReview by mutableStateOf(false)
    private var _showTtsHint by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initTts()
        readIntent(intent)
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                MaterialTheme(colorScheme = appColorScheme()) {
                    WordBookApp(
                        vm = vm,
                        onSpeak = { spelling -> speak(spelling) },
                        pendingWord = pendingWord,
                        pendingAdd = pendingAdd,
                        pendingReview = pendingReview,
                        onPendingConsumed = {
                            pendingWord = null
                            pendingAdd = false
                            pendingReview = false
                            // 同时清掉 Intent 里的 extra：否则屏幕旋转/Activity 重建时
                            // 会从 getIntent() 重新读到这些标记，把弹窗又弹一次
                            intent?.removeExtra(EXTRA_PENDING_WORD)
                            intent?.removeExtra(EXTRA_OPEN_ADD)
                            intent?.removeExtra(EXTRA_OPEN_REVIEW)
                        }
                    )
                }
            }
            if (_showTtsHint) {
                TtsHintDialog()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun TtsHintDialog() {
        AlertDialog(
            onDismissRequest = { _showTtsHint = false },
            title = { Text("还没有可用的语音引擎") },
            text = {
                Text(
                    "单词本的发音依赖系统「文字转语音（TTS）」服务。\n\n" +
                        "你的手机目前没有可用引擎（国行小米/澎湃OS 常见）。\n\n" +
                        "解决办法：\n" +
                        "1. 安装一个语音引擎应用：Google 语音服务（需网络）或「讯飞语记」等；\n" +
                        "2. 到系统设置里把「首选引擎」切换为新装的那个；\n" +
                        "3. 回到单词本，点喇叭即可发音。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    _showTtsHint = false
                    try {
                        // 各家 ROM 通用的 TTS 设置页 action
                        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(this@MainActivity, "未能打开系统设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("打开系统语音设置") }
            },
            dismissButton = {
                TextButton(onClick = { _showTtsHint = false }) { Text("知道了") }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        intent ?: return
        pendingWord = intent.getStringExtra(EXTRA_PENDING_WORD)
        pendingAdd = intent.getBooleanExtra(EXTRA_OPEN_ADD, false)
        pendingReview = intent.getBooleanExtra(EXTRA_OPEN_REVIEW, false)
    }

    private fun initTts() {
        tts?.stop()
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 语言降级链：美式英语 -> 英式英语 -> 简体中文 -> 系统默认。
                // 只要任意一档可用就视为引擎就绪（有的引擎没有英文数据仍会读单词）。
                val candidates = listOf(
                    Locale.US,
                    Locale.UK,
                    Locale.SIMPLIFIED_CHINESE
                )
                var anyUsable = false
                for (l in candidates) {
                    val r = tts?.setLanguage(l)
                    if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                        anyUsable = true
                        break
                    }
                }
                if (!anyUsable) {
                    tts?.setLanguage(Locale.getDefault())
                }
                ttsReady = true
                // 引擎重绑成功后补播用户点的那一次
                pendingSpeak?.let { p ->
                    pendingSpeak = null
                    speak(p)
                }
            } else {
                // 手机上仍没有可用引擎：给出引导（装引擎/切引擎后再点喇叭会自动重试）
                ttsReady = false
                pendingSpeak = null
                _showTtsHint = true
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady && tts != null) {
            try {
                tts?.setSpeechRate(0.9f)
                val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wordbook_tts")
                if (res == TextToSpeech.ERROR) {
                    Toast.makeText(
                        this,
                        "语音引擎暂时异常，可到 系统设置-更多设置-无障碍-TTS 重新选择语音引擎",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                // 引擎被换/被杀后旧实例失效：重建并直接补播这一次
                ttsReady = false
                pendingSpeak = text
                initTts()
            }
        } else {
            // 每次“点击都重试重建”——用户在系统设置里切完引擎回来，点喇叭即可用，无需杀 APP
            pendingSpeak = text
            initTts()
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时刷新小组件，保证看到的待复习数是最新的
        WordBookWidgetProvider.refresh(this)
    }

    override fun onStop() {
        super.onStop()
        // 离开 APP 时刷新桌面小组件（复习/加词后的计数立刻跟上）
        WordBookWidgetProvider.refresh(this)
        // 开了「离开时自动同步」就静默上传一次
        vm.webdavAutoSyncIfEnabled()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PENDING_WORD = "pending_word"
        const val EXTRA_OPEN_ADD = "open_add"
        const val EXTRA_OPEN_REVIEW = "open_review"
    }
}
