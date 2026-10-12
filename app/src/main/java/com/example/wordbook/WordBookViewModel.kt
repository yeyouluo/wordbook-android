package com.example.wordbook

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wordbook.data.AiClient
import com.example.wordbook.data.AiProvider
import com.example.wordbook.data.AiProviders
import com.example.wordbook.data.AppDatabase
import com.example.wordbook.data.Word
import com.example.wordbook.data.SyncRunner
import com.example.wordbook.data.WordDao
import com.example.wordbook.util.CsvUtil
import com.example.wordbook.util.ReviewSchedule
import com.example.wordbook.util.ReviewStats
import com.example.wordbook.util.SyncGate
import com.example.wordbook.util.SyncScheduler
import com.example.wordbook.util.WordTextUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 首页顶部统计卡片的数据 */
data class Stats(
    val total: Int = 0,
    val mastered: Int = 0,
    val learning: Int = 0,
    val dueWords: List<Word> = emptyList(),
    /** 正确率百分比 0-100；-1 表示还没有复习记录 */
    val accuracyPercent: Int = -1,
    val totalCorrect: Int = 0,
    val totalWrong: Int = 0,
    /** 连续复习天数 */
    val streakDays: Int = 0,
    /** 本周有复习的天数 */
    val weekReviewDays: Int = 0,
    /** 历史复习总次数（用于区分"从没复习过"和"v1.6 前的老记录"） */
    val totalReviews: Int = 0
)

data class ImportResult(val added: Int, val skipped: Int)

/** 批量 AI 查词状态机 */
data class BatchState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val inserted: Int = 0,
    val skippedExisting: Int = 0,
    val skippedInvalid: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val currentWord: String = "",
    val finished: Boolean = false,
    val canceled: Boolean = false,
    val truncated: Int = 0,
    val noApiKey: Boolean = false
)

/** 会话窗口里一张「可入库词卡」 */
data class ChatDraft(
    val word: Word,
    val existed: Boolean = false,
    val added: Boolean = false
)

/** 会话窗口的一条消息 */
data class ChatUiMessage(
    val role: String,                       // "user" / "assistant"
    val text: String = "",
    val drafts: List<ChatDraft> = emptyList(),
    val error: Boolean = false
)

/** WebDAV 同步状态 */
data class SyncState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val ok: Boolean = false,
    val message: String = ""
)

/**
 * 单例 ViewModel：持有数据库 DAO 与 SharedPreferences（存 API Key）。
 * UI 只跟它交互，不直接碰数据库和网络。
 */
class WordBookViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: WordDao = AppDatabase.get(app).wordDao()
    private val prefs: android.content.SharedPreferences =
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 搜索关键字 */
    val query = MutableStateFlow("")

    /** 剪贴板去重：只对「和上次处理过的不一样」的内容弹提示 */
    private var lastClipboardHandled: String = ""

    /**
     * 每次回到前台都允许检查剪贴板（Android 10+ 只允许有焦点的应用读取），
     * 但同一段内容只处理一次，避免反复弹窗。
     */
    fun shouldHandleClipboard(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t == lastClipboardHandled) return false
        lastClipboardHandled = t
        return true
    }

    /** 列表筛选：all / learning / mastered */
    val filter = MutableStateFlow("all")

    /** 是否按字母序排列 */
    val sortAlpha = MutableStateFlow(false)

    val words: StateFlow<List<Word>> =
        combine(dao.observeAll(), query, filter, sortAlpha) { list, q, f, alpha ->
            var result = when {
                q.isNotBlank() -> list.filter {
                    it.spelling.contains(q, ignoreCase = true) ||
                        it.meaning.contains(q, ignoreCase = true)
                }
                else -> list
            }
            result = when (f) {
                "learning" -> result.filter { !it.mastered }
                "mastered" -> result.filter { it.mastered }
                else -> result
            }
            if (alpha) result.sortedBy { it.spelling.lowercase() } else result
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<Stats> = dao.observeAll().map { list ->
        val reviewDays = prefs.getStringSet(K_REVIEW_DAYS, emptySet()) ?: emptySet()
        val correct = list.sumOf { it.totalCorrect }
        val wrong = list.sumOf { it.totalWrong }
        Stats(
            total = list.size,
            mastered = list.count { it.mastered },
            learning = list.count { !it.mastered },
            dueWords = ReviewSchedule.dueWords(list),
            accuracyPercent = ReviewStats.accuracyPercent(correct, wrong),
            totalCorrect = correct,
            totalWrong = wrong,
            streakDays = ReviewStats.streakDays(reviewDays),
            weekReviewDays = ReviewStats.weekReviewDays(reviewDays),
            totalReviews = list.sumOf { it.reviewCount }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Stats())

    // ---------- 艾宾浩斯记忆曲线调度（实现见 util/ReviewSchedule，UI 与小组件共用） ----------
    fun isDue(word: Word, now: Long = System.currentTimeMillis()): Boolean =
        ReviewSchedule.isDue(word, now)

    /** 记录一次复习结果；连续答对 MASTER_ON_CORRECT 次自动标记已掌握 */
    fun markReviewed(word: Word, correct: Boolean) {
        val now = System.currentTimeMillis()
        val nextCorrect = if (correct) word.reviewCorrectCount + 1 else 0
        // 记下"今天复习过"，用于连击天数统计（按天去重，只留最近 400 天）
        val days = prefs.getStringSet(K_REVIEW_DAYS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(K_REVIEW_DAYS, ReviewStats.withToday(days)).apply()
        viewModelScope.launch {
            dao.update(
                word.copy(
                    reviewCount = word.reviewCount + 1,
                    reviewCorrectCount = nextCorrect,
                    lastReviewedAt = now,
                    totalCorrect = word.totalCorrect + if (correct) 1 else 0,
                    totalWrong = word.totalWrong + if (correct) 0 else 1,
                    updatedAt = now,
                    mastered = word.mastered || nextCorrect >= ReviewSchedule.MASTER_ON_CORRECT
                )
            )
        }
    }

    // ---------- AI 服务配置（多厂商，均走 OpenAI 兼容协议） ----------
    fun providers(): List<AiProvider> = AiProviders.ALL

    fun currentProviderId(): String =
        prefs.getString(K_PROVIDER, AiProviders.ALL.first().id) ?: AiProviders.ALL.first().id

    /** 按厂商分别存 Key / 模型 / baseUrl；老版本只存过一个 deepseek_api_key，这里做兼容读取 */
    fun apiKeyFor(id: String): String {
        val saved = prefs.getString("api_key_$id", "") ?: ""
        if (saved.isBlank() && id == "deepseek") {
            return prefs.getString(LEGACY_KEY, "") ?: ""
        }
        return saved
    }

    fun modelFor(id: String): String {
        val saved = prefs.getString("ai_model_$id", "") ?: ""
        return saved.ifBlank { AiProviders.byId(id).defaultModel }
    }

    fun baseUrlFor(id: String): String {
        val saved = prefs.getString("ai_base_url_$id", "") ?: ""
        return saved.ifBlank { AiProviders.byId(id).baseUrl }
    }

    fun saveAiConfig(id: String, key: String, model: String, baseUrl: String) {
        prefs.edit()
            .putString(K_PROVIDER, id)
            .putString("api_key_$id", key.trim())
            .putString("ai_model_$id", model.trim())
            .putString("ai_base_url_$id", baseUrl.trim())
            .apply()
    }

    fun activeProviderName(): String = AiProviders.byId(currentProviderId()).displayName

    fun activeApiKey(): String = apiKeyFor(currentProviderId())

    fun hasApiKey(): Boolean = activeApiKey().isNotBlank()

    /** 用当前厂商配置构造客户端（所有 AI 调用统一走这里） */
    private fun newClient(): AiClient {
        val id = currentProviderId()
        return AiClient(
            provider = AiProviders.byId(id),
            apiKey = apiKeyFor(id),
            model = modelFor(id),
            baseUrl = baseUrlFor(id)
        )
    }

    /** 设置页「测试连接」：用输入框里的即时配置试一次，不入库 */
    fun testAiConnection(
        id: String,
        key: String,
        model: String,
        baseUrl: String,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val p = AiProviders.byId(id)
            val (ok, message) = AiClient(
                provider = p,
                apiKey = key.trim(),
                model = model.trim().ifBlank { p.defaultModel },
                baseUrl = baseUrl.trim().ifBlank { p.baseUrl }
            ).testConnection()
            onResult(ok, message)
        }
    }

    // ---------- CRUD ----------
    suspend fun getAllWords(): List<Word> = dao.getAllOnce()

    fun addWord(word: Word) {
        val now = System.currentTimeMillis()
        viewModelScope.launch { dao.insert(word.copy(createdAt = now, updatedAt = now)) }
    }

    fun addWords(words: List<Word>) {
        val now = System.currentTimeMillis()
        viewModelScope.launch { dao.insertAll(words.map { it.copy(createdAt = now, updatedAt = now) }) }
    }

    /** 编辑已有单词（保留 id / 复习统计 / 创建时间） */
    fun updateWord(word: Word) {
        viewModelScope.launch { dao.update(word.copy(updatedAt = System.currentTimeMillis())) }
    }

    fun toggleMastered(word: Word) {
        viewModelScope.launch {
            dao.update(word.copy(mastered = !word.mastered, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteWord(word: Word) {
        viewModelScope.launch { dao.delete(word) }
    }

    // ---------- AI ----------
    fun aiLookup(spelling: String, onResult: (AiClient.LookupResult?) -> Unit) {
        viewModelScope.launch {
            onResult(newClient().lookup(spelling))
        }
    }

    /** 同上，但把失败原因一并给出（表单里要显示具体原因，而不是笼统"失败"） */
    fun aiLookupOutcome(
        spelling: String,
        onResult: (AiClient.AiOutcome<AiClient.LookupResult>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(newClient().lookupOutcome(spelling))
        }
    }

    /** 围绕单词的多轮问答；history 为 (role, content) 对 */
    fun askAbout(word: Word, history: List<Pair<String, String>>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(newClient().chat(history))
        }
    }

    // ---------- 共享管道：词条构建与库内去重 ----------
    /** LookupResult → Word（同根词拼成 "w(释义), w(释义)" 存储） */
    private fun buildWord(spelling: String, r: AiClient.LookupResult): Word = Word(
        spelling = spelling,
        phonetic = r.phonetic,
        meaning = r.meaning,
        example = r.example,
        mnemonic = r.mnemonic,
        family = WordTextUtils.familyText(r.family)
    )

    /** 会话草稿 → Word */
    private fun buildWord(d: AiClient.DraftWord): Word = Word(
        spelling = d.spelling,
        phonetic = d.phonetic,
        meaning = d.meaning,
        example = d.example,
        mnemonic = d.mnemonic,
        family = WordTextUtils.familyText(d.family)
    )

    /** 库内已有单词（小写集合），两个入口统一用它去重 */
    private suspend fun existingSpellings(): Set<String> =
        dao.getAllSpellings().map { it.lowercase() }.toHashSet()

    // ---------- A. 批量 AI 查词 ----------
    val batch = MutableStateFlow(BatchState())
    private var batchJob: Job? = null

    /**
     * 顺序执行、失败重试 1 次、每成功一个立即入库。
     * 任务跑在 viewModelScope：关闭弹窗不中断；cancelBatch() 是唯一主动终止方式。
     */
    fun startBatch(input: String) {
        if (batch.value.running) return
        batchJob?.cancel()
        val hasKey = hasApiKey()
        batchJob = viewModelScope.launch {
            val parsed = WordTextUtils.parseBatch(input, existingSpellings(), max = BATCH_MAX)
            if (parsed.accepted.isEmpty() || !hasKey) {
                batch.value = BatchState(
                    finished = true,
                    skippedExisting = parsed.duplicates.size,
                    skippedInvalid = parsed.invalid,
                    truncated = parsed.truncated,
                    noApiKey = !hasKey
                )
                return@launch
            }
            batch.value = BatchState(
                running = true,
                total = parsed.accepted.size,
                skippedExisting = parsed.duplicates.size,
                skippedInvalid = parsed.invalid,
                truncated = parsed.truncated
            )
            val client = newClient()
            val failed = ArrayList<String>()
            var done = 0
            var inserted = 0
            for (spelling in parsed.accepted) {
                if (!isActive) break
                batch.value = batch.value.copy(currentWord = spelling)
                var result = client.lookup(spelling)
                if (result == null) {
                    delay(RETRY_DELAY_MS)
                    if (!isActive) break
                    result = client.lookup(spelling)
                }
                if (result != null) {
                    dao.insert(buildWord(spelling, result))
                    inserted++
                } else {
                    failed.add(spelling)
                }
                done++
                batch.value = batch.value.copy(
                    done = done,
                    inserted = inserted,
                    failed = failed.toList()
                )
                delay(WORD_GAP_MS)
            }
            batch.value = batch.value.copy(
                running = false,
                finished = true,
                currentWord = "",
                done = done,
                inserted = inserted,
                failed = failed.toList()
            )
        }
    }

    /** 取消：保留已入库的词，未跑的丢弃 */
    fun cancelBatch() {
        batchJob?.cancel()
        batch.value = batch.value.copy(running = false, finished = true, canceled = true, currentWord = "")
    }

    /** 只重跑失败项 */
    fun retryFailedBatch() {
        val failed = batch.value.failed
        if (failed.isEmpty() || batch.value.running) return
        batch.value = BatchState()
        startBatch(failed.joinToString("\n"))
    }

    /** 放弃当前批量结果（回到输入态） */
    fun resetBatch() {
        batchJob?.cancel()
        batch.value = BatchState()
    }

    // ---------- B. AI 会话窗口 ----------
    val chat = MutableStateFlow<List<ChatUiMessage>>(emptyList())
    val chatSending = MutableStateFlow(false)

    /** 一条用户消息 = 一次 AI 调用（贴一批词也只花一次调用） */
    fun sendChat(input: String) {
        val text = input.trim()
        if (text.isEmpty() || chatSending.value) return
        chat.value = chat.value + ChatUiMessage(role = "user", text = text)

        val hasKey = hasApiKey()
        if (!hasKey) {
            chat.value = chat.value + ChatUiMessage(
                role = "assistant",
                text = "还没有配置 AI 服务的 API Key，先去右上角 ⚙️ 选厂商并填 Key。",
                error = true
            )
            return
        }
        chatSending.value = true
        viewModelScope.launch {
            val client = newClient()
            var history = trimmedHistory()
            var outcome = client.converseOutcome(history)
            var note = ""

            // 失败但上下文不止一条时：自动精简到「只发这一步」重试一次
            // （用户之前只能靠"清空会话"绕过，这里替他自动做掉）
            if (!outcome.ok && history.size > 1) {
                history = listOf(history.last())
                outcome = client.converseOutcome(history)
                if (outcome.ok) note = "\n\n（上一轮上下文过长导致失败，已自动精简后重试成功）"
            }

            // 还不行就降级为纯文本问答：至少让用户拿到答案，只是没有词卡
            if (!outcome.ok) {
                val plain = client.chatOutcome(listOf(history.last()))
                chatSending.value = false
                if (plain.ok) {
                    chat.value = chat.value + ChatUiMessage(
                        role = "assistant",
                        text = plain.value!! + "\n\n（本次降级为纯文本回答：未能生成可入库的词卡，可点「重试」再试一次）",
                        error = false
                    )
                } else {
                    chat.value = chat.value + ChatUiMessage(
                        role = "assistant",
                        text = "AI 请求失败：${outcome.error ?: plain.error ?: "未知原因"}",
                        error = true
                    )
                }
                return@launch
            }

            val envelope = outcome.value!!
            chatSending.value = false
            val drafts = client.toDrafts(envelope)
            if (drafts.size > CHAT_MAX_WORDS) {
                chat.value = chat.value + ChatUiMessage(
                    role = "assistant",
                    text = "这条消息里识别到 ${drafts.size} 个单词，超过单次上限 ${CHAT_MAX_WORDS} 个。" +
                        "建议改用顶栏「批量 AI 查词」，那边有进度和失败重试。",
                    error = false
                )
                return@launch
            }
            val existing = existingSpellings()
            val cards = drafts.map { d ->
                ChatDraft(word = buildWord(d), existed = d.spelling.lowercase() in existing)
            }
            chat.value = chat.value + ChatUiMessage(
                role = "assistant",
                text = (
                    envelope.reply.ifBlank {
                        if (cards.isEmpty()) "（模型没有返回内容）" else "已备好 ${cards.size} 个词条，勾选后入库。"
                    }
                    ) + note,
                drafts = cards
            )
        }
    }

    /**
     * 送给模型的历史：只保留最近若干条，并限制总字数。
     * 这样长时间聊天也不会因为上下文过长而失败（问题 #2/#3 的根因）。
     */
    private fun trimmedHistory(): List<Pair<String, String>> {
        val msgs = chat.value.filter { !it.error }
        val tail = msgs.takeLast(CHAT_HISTORY_MAX_MESSAGES)
        val kept = ArrayDeque<Pair<String, String>>()
        var total = 0
        for (m in tail.asReversed()) {
            val cost = m.text.length
            if (total + cost > CHAT_HISTORY_MAX_CHARS && kept.isNotEmpty()) break
            total += cost
            kept.addFirst(m.role to m.text)
        }
        return kept.toList().ifEmpty { listOf("user" to (msgs.lastOrNull()?.text ?: "")) }
    }

    /** 把一条会话消息（含词卡）拼成可复制的纯文本 */
    fun chatMessageToPlainText(msg: ChatUiMessage): String = buildString {
        if (msg.role == "user") append("我：")
        append(msg.text)
        msg.drafts.forEach { d ->
            append("\n- ").append(d.word.spelling)
            if (d.word.phonetic.isNotBlank()) append(" ").append(d.word.phonetic)
            if (d.word.meaning.isNotBlank()) append("\n  释义：").append(d.word.meaning)
            if (d.word.example.isNotBlank()) append("\n  例句：").append(d.word.example)
            if (d.word.mnemonic.isNotBlank()) append("\n  记忆：").append(d.word.mnemonic)
            if (d.word.family.isNotBlank()) append("\n  同根词：").append(d.word.family)
        }
    }

    /** 复制整段会话 */
    fun chatToPlainText(): String =
        chat.value.joinToString("\n\n") { chatMessageToPlainText(it) }

    /** 勾选入库：positions 是某条 assistant 消息内词卡的下标 */
    fun addDrafts(messageIndex: Int, positions: Set<Int>) {
        val msgs = chat.value.toMutableList()
        val msg = msgs.getOrNull(messageIndex) ?: return
        val drafts = msg.drafts.toMutableList()
        val toInsert = ArrayList<Word>()
        for (p in positions) {
            val d = drafts.getOrNull(p) ?: continue
            if (d.existed || d.added) continue
            toInsert.add(d.word)
            drafts[p] = d.copy(added = true)
        }
        if (toInsert.isEmpty()) return
        msgs[messageIndex] = msg.copy(drafts = drafts)
        chat.value = msgs
        viewModelScope.launch { dao.insertAll(toInsert) }
    }

    /** 失败重试：丢掉末尾错误气泡与那条用户消息，重新发送 */
    fun retryLastChat() {
        if (chatSending.value) return
        val lastUser = chat.value.lastOrNull { it.role == "user" && !it.error }?.text ?: return
        val trimmed = chat.value.toMutableList()
        while (trimmed.isNotEmpty() && trimmed.last().error) trimmed.removeAt(trimmed.size - 1)
        if (trimmed.isNotEmpty() && trimmed.last().role == "user") trimmed.removeAt(trimmed.size - 1)
        chat.value = trimmed
        sendChat(lastUser)
    }

    fun clearChat() {
        chat.value = emptyList()
    }

    // ---------- WebDAV 云同步（P0） ----------
    // 配置、锚点、端到端逻辑都在 data/WebDavSync.kt 的 SyncRunner（Worker 与 VM 共用，不复制第二份）
    val sync = MutableStateFlow(SyncState())

    /** 需要给用户看的同步提示（冲突解决等），UI 弹完 toast 后清空 */
    val syncToast = MutableStateFlow<String?>(null)

    private var syncJob: Job? = null
    private var lastResumeSyncAt = 0L

    private val syncRunner: SyncRunner get() = SyncRunner(getApplication())

    fun syncConfigured(): Boolean = syncRunner.configured()
    fun syncServer(): String = syncRunner.server()
    fun syncBasePath(): String = syncRunner.basePath()
    fun syncUser(): String = syncRunner.user()
    fun syncPassword(): String = syncRunner.password()
    fun syncAuto(): Boolean = syncRunner.auto()
    fun syncIntervalMinutes(): Int = syncRunner.intervalMinutes()
    fun syncLastAt(): Long = syncRunner.lastSyncAt()
    fun syncLastRev(): String = syncRunner.lastRemoteRev()
    fun syncLastError(): String = syncRunner.lastError()

    /** 保存同步配置并按新的开关/间隔重排定时任务 */
    fun saveSyncConfig(
        server: String,
        basePath: String,
        user: String,
        password: String,
        auto: Boolean,
        intervalMinutes: Int
    ) {
        syncRunner.saveConfig(server, basePath, user, password, auto, intervalMinutes)
        SyncScheduler.apply(getApplication())
    }

    /** 重置同步状态：只清锚点，不动词库与凭据 */
    fun resetSyncState() {
        syncRunner.resetAnchors()
        sync.value = SyncState(finished = true, ok = true, message = "已重置同步状态，下次同步会重新比对两端")
    }

    /** 测试连接：PROPFIND 207 才算通 */
    fun testSync() {
        if (sync.value.running) return
        sync.value = SyncState(running = true, message = "正在连接…")
        viewModelScope.launch {
            val (ok, msg) = syncRunner.test()
            sync.value = SyncState(running = false, finished = true, ok = ok, message = msg)
        }
    }

    /**
     * 手动/定时同步。同一时刻只允许一个同步任务（防重入）。
     * @param manual 手动触发时，失败原因直接显示在设置页；静默触发时只写入 sync_last_error。
     */
    fun syncNow(manual: Boolean = true) {
        if (syncJob?.isActive == true) return
        if (!syncConfigured()) {
            if (manual) {
                sync.value = SyncState(
                    finished = true,
                    ok = false,
                    message = "请先填写服务器、路径、账号与密码"
                )
            }
            return
        }
        if (!SyncGate.tryEnter()) return
        syncJob = viewModelScope.launch {
            sync.value = SyncState(running = true, message = "正在同步…")
            val result = try {
                syncRunner.run()
            } catch (e: Exception) {
                com.example.wordbook.data.SyncResult(false, "同步异常：${e.message ?: "未知错误"}")
            } finally {
                SyncGate.leave()
            }
            sync.value = SyncState(
                running = false,
                finished = true,
                ok = result.ok,
                message = result.message
            )
            // 冲突必须让用户知道（即使静默触发）；普通静默失败只留在设置页
            if (manual || result.message.contains("冲突")) {
                syncToast.value = result.message
            }
        }
    }

    /** 触发时机之二：回到前台静默同步（30 秒内不重复触发） */
    fun syncOnResume() {
        val now = System.currentTimeMillis()
        if (now - lastResumeSyncAt < 30_000L) return
        lastResumeSyncAt = now
        syncNow(manual = false)
    }

    // ---------- CSV 导入 ----------
    suspend fun importCsvText(text: String): ImportResult {
        val table = CsvUtil.parseCsv(text)
        val header = table.header
        // 按表头名定位列：这样新旧导出格式（列数变化）都能正确回导
        fun col(vararg names: String, fallback: Int): Int {
            if (header == null) return fallback
            for (n in names) {
                val i = header.indexOf(n)
                if (i >= 0) return i
            }
            return fallback
        }
        val iSpelling = col("单词", fallback = 0)
        val iPhonetic = col("音标", fallback = 1)
        val iMeaning = col("释义", fallback = 2)
        val iExample = col("例句", fallback = 3)
        val iMnemonic = col("记忆法", fallback = 4)
        val iFamily = col("同根词", fallback = 5)
        val iMastered = col("已掌握", fallback = 6)
        val iReviewCount = col("复习次数", fallback = 7)
        val iReviewCorrect = col("复习正确次数", fallback = 8)
        val iTotalCorrect = col("累计答对", fallback = 9)
        val iTotalWrong = col("累计答错", fallback = 10)
        val iLastReviewed = col("上次复习", fallback = 11)

        val existing = dao.getAllSpellings().map { it.lowercase() }.toHashSet()
        val words = mutableListOf<Word>()
        var skipped = 0
        table.rows.forEach { r ->
            fun at(i: Int) = r.getOrNull(i)?.trim() ?: ""
            val spelling = at(iSpelling)
            if (spelling.isEmpty()) {
                skipped++
                return@forEach
            }
            if (spelling.lowercase() in existing) {
                skipped++
                return@forEach
            }
            existing.add(spelling.lowercase())
            val masteredCell = at(iMastered)
            val masteredYes = masteredCell == "是" ||
                masteredCell.equals("y", true) ||
                masteredCell.equals("true", true)
            words.add(
                Word(
                    spelling = spelling,
                    phonetic = at(iPhonetic),
                    meaning = at(iMeaning),
                    example = at(iExample),
                    mnemonic = at(iMnemonic),
                    family = at(iFamily),
                    mastered = masteredYes,
                    reviewCount = at(iReviewCount).toIntOrNull() ?: 0,
                    reviewCorrectCount = at(iReviewCorrect).toIntOrNull() ?: 0,
                    lastReviewedAt = parseDate(at(iLastReviewed)),
                    totalCorrect = at(iTotalCorrect).toIntOrNull() ?: 0,
                    totalWrong = at(iTotalWrong).toIntOrNull() ?: 0,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        if (words.isNotEmpty()) dao.insertAll(words)
        return ImportResult(added = words.size, skipped = skipped)
    }

    /** 随机抽题：从未掌握单词里挑一个（用回调，UI 直接可调） */
    fun quizNext(excludeIds: Set<Long> = emptySet(), onResult: (Word?) -> Unit) {
        viewModelScope.launch {
            onResult(dao.getUnmasteredOnce().filterNot { it.id in excludeIds }.randomOrNull())
        }
    }

    private fun parseDate(s: String): Long = try {
        // 与 CsvUtil 的导出格式保持一致：固定 Locale.US，避免本地历法差异
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .parse(s.trim())?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    companion object {
        /** 单批最多处理的词数（防成本失控） */
        const val BATCH_MAX = 100

        /** 单词失败后的重试间隔 */
        private const val RETRY_DELAY_MS = 1200L

        /** 词与词之间的间隔：单并发顺序跑，避免触发限流 */
        private const val WORD_GAP_MS = 250L

        /** 会话窗口单条消息最多接受多少词（超出建议改用批量入口） */
        const val CHAT_MAX_WORDS = 20

        /** 当前选中的 AI 厂商 */
        private const val K_PROVIDER = "ai_provider_id"

        /** 老版本只有一个 deepseek key，读取时做兼容 */
        private const val LEGACY_KEY = "deepseek_api_key"

        /** 有复习行为的日期集合（连击/本周统计用） */
        private const val K_REVIEW_DAYS = "review_days"

        /** 会话上下文裁剪：最多带几条历史、总字数上限（防上下文过长导致整轮失败） */
        private const val CHAT_HISTORY_MAX_MESSAGES = 8
        private const val CHAT_HISTORY_MAX_CHARS = 4000
    }
}
