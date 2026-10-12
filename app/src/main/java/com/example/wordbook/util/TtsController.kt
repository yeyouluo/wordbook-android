package com.example.wordbook.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * TTS 控制器（P3）：把语音引擎的生命周期、音色选择、语速集中到一处，
 * MainActivity（发音）与设置页（下拉/滑杆/试听）共用，避免两处各建一个引擎。
 *
 * 音色自动挑选策略（默认）：
 *   Natural（自然语音） > Google > Premium/Enhanced > Network > 本地
 * 用户手动选过之后记在 prefs（tts_voice），以后优先用它。
 */
object TtsController {

    private const val K_VOICE = "tts_voice"
    private const val K_RATE = "tts_rate"
    const val DEFAULT_RATE = 1.0f
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f

    private var tts: TextToSpeech? = null
    private var ready = false
    /** 当前语速（引擎没有 getter，自己记；初值来自 prefs） */
    private var currentRate = DEFAULT_RATE
    private var englishVoices: List<Voice> = emptyList()
    private var listeners = mutableListOf<() -> Unit>()

    /** 初始化（幂等）：MainActivity 启动时调用一次 */
    fun init(context: Context) {
        if (tts != null) return
        val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        currentRate = prefs.getFloat(K_RATE, DEFAULT_RATE).coerceIn(MIN_RATE, MAX_RATE)
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                applyLanguage(engine)
                englishVoices = engine.voices
                    ?.filter { v -> v.locale.language == "en" }
                    ?.sortedBy { v -> v.name }
                    .orEmpty()
                ready = true
                val saved = prefs.getString(K_VOICE, null)
                val voice = englishVoices.firstOrNull { it.name == saved } ?: autoPick(englishVoices)
                voice?.let { engine.voice = it }
                engine.setSpeechRate(prefs.getFloat(K_RATE, DEFAULT_RATE))
                notifyListeners()
            } else {
                ready = false
                notifyListeners()
            }
        }
    }

    private fun applyLanguage(engine: TextToSpeech) {
        val candidates = listOf(Locale.US, Locale.UK, Locale.SIMPLIFIED_CHINESE)
        var usable = false
        for (l in candidates) {
            val r = engine.setLanguage(l)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                usable = true
                break
            }
        }
        if (!usable) engine.setLanguage(Locale.getDefault())
    }

    /** 引擎是否可用（未就绪时设置页/发音都应给出引导） */
    fun isReady(): Boolean = ready

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        englishVoices = emptyList()
    }

    fun onReadyChanged(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    /**
     * 自动挑选：Natural > Google > Premium/Enhanced > Network > 其余本地音色。
     * 纯函数（不依赖引擎实例），便于单测。
     */
    fun autoPick(voices: List<Voice>): Voice? {
        if (voices.isEmpty()) return null
        fun score(v: Voice): Int {
            val name = v.name.lowercase(Locale.US)
            var s = 0
            if (name.contains("natural")) s += 100
            if (name.contains("google")) s += 40
            if (v.isNetworkConnectionRequired) s -= 5
            if (name.contains("premium")) s += 20
            if (name.contains("enhanced")) s += 15
            if (name.contains("network")) s += 10
            return s
        }
        return voices.maxByOrNull { score(it) }
    }

    /** 供设置页下拉展示：name -> 人类可读标签 */
    fun voiceOptions(): List<Pair<String, String>> = englishVoices.map { v ->
        val quality = when {
            v.name.contains("natural", true) -> "Natural"
            v.name.contains("premium", true) -> "Premium"
            v.name.contains("enhanced", true) -> "Enhanced"
            v.isNetworkConnectionRequired -> "Network"
            else -> "Local"
        }
        v.name to "${v.locale.displayName} · $quality"
    }

    fun currentVoiceName(): String? = tts?.voice?.name

    fun rate(): Float = currentRate.coerceIn(MIN_RATE, MAX_RATE)

    /** 应用并持久化音色（null = 回到自动挑选） */
    fun setVoice(context: Context, voiceName: String?) {
        val engine = tts ?: return
        val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (voiceName.isNullOrBlank()) {
            prefs.edit().remove(K_VOICE).apply()
            autoPick(englishVoices)?.let { engine.voice = it }
        } else {
            englishVoices.firstOrNull { it.name == voiceName }?.let {
                engine.voice = it
                prefs.edit().putString(K_VOICE, voiceName).apply()
            }
        }
    }

    fun setRate(context: Context, rate: Float) {
        val safe = rate.coerceIn(MIN_RATE, MAX_RATE)
        currentRate = safe
        tts?.setSpeechRate(safe)
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putFloat(K_RATE, safe).apply()
    }

    /** 试听（设置页固定句子） */
    fun speakPreview(text: String = "The quick brown fox jumps over the lazy dog.") {
        speak(text)
    }

    fun speak(text: String) {
        val engine = tts ?: return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wordbook_tts")
    }
}
