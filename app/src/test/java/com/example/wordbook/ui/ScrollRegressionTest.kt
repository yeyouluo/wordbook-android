package com.example.wordbook.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.wordbook.WordBookViewModel
import com.example.wordbook.data.AppDatabase
import com.example.wordbook.data.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 只能靠「手势」才能验证的行为，在这里上锁（Robolectric 跑在 JVM，不需要真机/模拟器）。
 *
 * 背景（0.3.2 修的真实 bug）：弹窗里写成了
 *     Modifier.verticalScroll(state).heightIn(max = 380.dp)
 * 修饰符顺序反了。看 foundation 的 ScrollingLayoutNode.measure 实现：
 *     val placeable = measurable.measure(constraints.copy(maxHeight = Infinity))
 *     val height = placeable.height.coerceAtMost(constraints.maxHeight)
 *     scrollerState.maxValue = placeable.height - height
 * heightIn 在 verticalScroll **之后**时，被钳住的是「内容」而不是「容器」，
 * 于是内容高 = 容器高 → maxValue = 0 → 滚动范围为零：怎么滑都不动，超出部分直接看不见。
 * 正确顺序（heightIn 在前）才能让内容按无限高测量、容器取 min(内容, 上限)。
 *
 * 这里不复制一份 UI 代码来测，而是直接驱动**真实的** ReviewDialog / SettingsDialog，
 * 这样「顺序写反」一旦复发，测试就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ScrollRegressionTest {

    @get:Rule
    val compose = createComposeRule()

    private val dayMs = 24L * 3600 * 1000

    /** 造 n 个「已到期该复习」的词：createdAt 放到 3 天前，reviewCorrectCount = 0（第 1 天档） */
    private fun seedDueWords(n: Int): WordBookViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val base = System.currentTimeMillis() - 3 * dayMs
        runBlocking {
            val dao = AppDatabase.get(app).wordDao()
            dao.deleteAll()
            repeat(n) { i ->
                // createdAt 递增 → 列表按 createdAt DESC 排序，w(n-1) 在最上面、w0 在最下面
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

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `复习弹窗可以下滑到最后一个待复习词`() {
        val vm = seedDueWords(12)
        compose.setContent { ReviewDialog(vm = vm, onSpeak = {}, onClose = {}) }
        waitForText("w11")                                   // 最新创建的排在最上面
        compose.onNodeWithText("w11").assertIsDisplayed()

        // 最关键的一步：滚到列表最后一项。滚动范围为 0 时这里永远到不了 → 直接失败。
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("w00"))
        compose.onNodeWithText("w00").assertIsDisplayed()
    }

    @Test
    fun `AI厂商选择弹窗可以下滑到最后一个厂商`() {
        val vm = seedDueWords(1)
        compose.setContent { SettingsDialog(vm = vm, onClose = {}) }
        waitForText("AI 服务设置")

        // 打开「选择 AI 厂商」
        compose.onNode(hasText("厂商：", substring = true)).performClick()
        waitForText("选择 AI 厂商")

        // 8 个厂商（含自定义）在 400dp 上限里放不下，最后一个必须能滑到
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("自定义（OpenAI 兼容）"))
        compose.onNodeWithText("自定义（OpenAI 兼容）").assertIsDisplayed()
    }

    @Test
    fun `单词列表滚到底会自动续渲染下一批`() {
        val vm = seedDueWords(70)
        compose.setContent {
            WordBookApp(
                vm = vm,
                onSpeak = {},
                pendingWord = null,
                pendingAdd = false,
                pendingReview = false,
                onPendingConsumed = {}
            )
        }
        waitForText("w69") // 70 个词，最新的在最上面

        fun rowCount(): Int =
            compose.onNode(hasScrollAction()).fetchSemanticsNode()
                .config[SemanticsProperties.CollectionInfo].rowCount

        val before = rowCount()
        assertTrue("首批不应一次渲染全部 70 个词（实际 $before 行）", before < 70)

        // 滑到底部（哨兵进入可视区）→ 触发自动续渲染
        compose.onNode(hasScrollAction()).performScrollToIndex(before - 1)
        compose.waitForIdle()

        val after = rowCount()
        assertTrue("滚到底部后应续渲染更多（$before → $after）", after > before)
    }
}
