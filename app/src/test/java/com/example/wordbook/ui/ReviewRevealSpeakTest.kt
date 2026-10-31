package com.example.wordbook.ui

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.wordbook.WordBookViewModel
import com.example.wordbook.data.AppDatabase
import com.example.wordbook.data.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复习弹窗的交互约定：
 * 点「显示释义」时要**同时连读两遍**这个单词（用户要求：不想再点一次小喇叭，且一遍常常只听个尾音）。
 * 这里驱动真实 ReviewDialog，断言「连读回调被调用一次」（遍数由 MainActivity 的 speak(times=2) 负责），
 * 且不会再额外触发单遍回调 —— 否则就会变成读三遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReviewRevealSpeakTest {

    @get:Rule
    val compose = createComposeRule()

    private val dayMs = 24L * 3600 * 1000

    /** 造 3 个已到期的词；createdAt 递增 → 列表按 createdAt DESC 排列，w02 在最上面 */
    private fun seedDueWords(n: Int): WordBookViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val base = System.currentTimeMillis() - 3 * dayMs
        runBlocking {
            val dao = AppDatabase.get(app).wordDao()
            dao.deleteAll()
            repeat(n) { i ->
                dao.insert(
                    Word(
                        spelling = "w%02d".format(i),
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
    fun `点显示释义会连读两遍这个单词`() {
        val vm = seedDueWords(3)
        val single = mutableListOf<String>()
        val twice = mutableListOf<String>()
        compose.setContent {
            ReviewDialog(
                vm = vm,
                onSpeak = { single += it },
                onSpeakTwice = { twice += it },
                onClose = {}
            )
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("w02")).fetchSemanticsNodes().isNotEmpty()
        }

        // 复用「未展示释义时不该自动读」这条，避免把「打开弹窗就读」误当成实现
        assertEquals("渲染复习弹窗本身不应该发音", emptyList<String>(), twice)

        compose.onAllNodes(hasText("显示释义")).onFirst().performClick()
        compose.waitForIdle()

        assertEquals("点「显示释义」应触发一次连读（最上面那个词）", listOf("w02"), twice)
        assertEquals("不应再额外触发单遍发音，否则会读三遍", emptyList<String>(), single)
    }
}
