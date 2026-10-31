package com.example.wordbook.ui

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.wordbook.WordBookViewModel
import com.example.wordbook.data.AppDatabase
import com.example.wordbook.data.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 随机抽测的交互约定：
 * **出题时**自动连读两遍这个单词（用户要求：抽测也要自动朗读、连读两遍），
 * 点「看释义」不再重读（刚读过两遍，再读会吵）。
 *
 * 与 ReviewRevealSpeakTest 一样，这里只断言「连读回调被调用了一次、且没额外触发单遍回调」，
 * 遍数本身由 MainActivity 的 speak(times = 2) 负责。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QuizAutoSpeakTest {

    @get:Rule
    val compose = createComposeRule()

    /** 造 n 个「学习中」的词（mastered = false 才会被抽测到） */
    private fun seedUnmasteredWords(n: Int): WordBookViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val base = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
        runBlocking {
            val dao = AppDatabase.get(app).wordDao()
            dao.deleteAll()
            repeat(n) { i ->
                dao.insert(
                    Word(
                        spelling = "q%02d".format(i),
                        meaning = "释义 $i",
                        createdAt = base + i * 1000L,
                        updatedAt = base + i * 1000L
                    )
                )
            }
        }
        return WordBookViewModel(app)
    }

    @Test
    fun `出题时会自动连读两遍这个单词`() {
        val vm = seedUnmasteredWords(2)
        val single = mutableListOf<String>()
        val twice = mutableListOf<String>()
        compose.setContent {
            QuizDialog(
                vm = vm,
                onSpeak = { single += it },
                onSpeakTwice = { twice += it },
                onClose = {}
            )
        }

        // 出题是异步的（viewModelScope 里取随机词），等连读回调落地
        compose.waitUntil(timeoutMillis = 10_000) { twice.isNotEmpty() }
        compose.waitForIdle()

        assertEquals("出题应触发一次连读", 1, twice.size)
        assertTrue("应该读的是被抽到的那道题的单词", twice.single() in listOf("q00", "q01"))
        assertEquals("不应再额外触发单遍发音，否则会读三遍", emptyList<String>(), single)
    }

    @Test
    fun `点看释义不再重读`() {
        val vm = seedUnmasteredWords(2)
        val twice = mutableListOf<String>()
        compose.setContent {
            QuizDialog(
                vm = vm,
                onSpeak = {},
                onSpeakTwice = { twice += it },
                onClose = {}
            )
        }
        compose.waitUntil(timeoutMillis = 10_000) { twice.isNotEmpty() }

        compose.onAllNodes(hasText("看释义"))[0].performClick()
        compose.waitForIdle()

        assertEquals("「看释义」只是对照答案，不该再读（否则刚读的两遍后立刻又读）", 1, twice.size)
    }
}
