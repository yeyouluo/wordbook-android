package com.example.wordbook

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
import com.example.wordbook.util.TtsController
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

    private var pendingSpeak: String? = null

    private var pendingWord by mutableStateOf<String?>(null)
    private var pendingAdd by mutableStateOf(false)
    private var pendingReview by mutableStateOf(false)
    private var _showTtsHint by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initTts()
        TtsController.onReadyChanged {
            if (TtsController.isReady()) {
                _showTtsHint = false
                flushPendingSpeak()
            } else {
                _showTtsHint = true
            }
        }
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
        TtsController.init(this)
    }

    private fun speak(text: String) {
        if (TtsController.isReady()) {
            TtsController.speak(text)
            return
        }
        // 引擎尚未就绪：重建一次并记下这次要读的内容（用户切完引擎回来点喇叭即可用）
        pendingSpeak = text
        TtsController.shutdown()
        TtsController.init(this)
    }

    private fun showNoEngineToast() {
        _showTtsHint = true
    }

    /** 引擎就绪后补播用户点的那一次 */
    private fun flushPendingSpeak() {
        val text = pendingSpeak ?: return
        if (TtsController.isReady()) {
            pendingSpeak = null
            TtsController.speak(text)
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时刷新小组件，保证看到的待复习数是最新的
        WordBookWidgetProvider.refresh(this)
        // 触发时机之二：回到前台静默同步（内部有 30 秒去抖，失败静默）
        vm.syncOnResume()
    }

    override fun onStop() {
        super.onStop()
        // 离开 APP 时刷新桌面小组件（复习/加词后的计数立刻跟上）
        WordBookWidgetProvider.refresh(this)
        // 离开时也静默同步一次（与启动/前台/定时共用同一引擎）
        vm.syncNow(manual = false)
    }

    override fun onDestroy() {
        TtsController.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PENDING_WORD = "pending_word"
        const val EXTRA_OPEN_ADD = "open_add"
        const val EXTRA_OPEN_REVIEW = "open_review"
    }
}
