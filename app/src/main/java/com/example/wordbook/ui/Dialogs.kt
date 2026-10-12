package com.example.wordbook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wordbook.Stats
import com.example.wordbook.WordBookViewModel
import com.example.wordbook.data.AiProviders
import com.example.wordbook.data.Word
import com.example.wordbook.util.SyncScheduler
import com.example.wordbook.data.WebDavSync
import com.example.wordbook.util.WordTextUtils
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import com.example.wordbook.util.TtsController

/**
 * 今日复习（P1）：
 * - 「先回忆后判定」：默认只显示 单词+音标+🔊，点「显示释义」后才显示 释义/记忆法/同根词，
 *   未显示释义时 ✓/✗ 不可点（设置里可关掉这个行为）
 * - 分页：每页 10 个，上一页/下一页 + 「第 x / y 页（本页 n 个）」；答过的词立即移出，下一页自动顶替
 */
@Composable
fun ReviewDialog(vm: WordBookViewModel, onSpeak: (String) -> Unit, onClose: () -> Unit) {
    val stats by vm.stats.collectAsState()
    val recallFirst = remember { vm.reviewRecallFirst() }
    val pageSize = 10
    var page by remember { mutableIntStateOf(0) }
    // wordId -> 是否已展开释义（答过的词会被移出列表，这里的记录随词条消失）
    val revealed = remember { mutableStateMapOf<Long, Boolean>() }

    val due = stats.dueWords
    val pageCount = if (due.isEmpty()) 1 else (due.size + pageSize - 1) / pageSize
    val safePage = page.coerceIn(0, pageCount - 1)
    val pageWords = due.drop(safePage * pageSize).take(pageSize)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("今日复习（艾宾浩斯）") },
        text = {
            if (due.isEmpty()) {
                Text("今天的复习计划已完成 🎉\n\n该复习的词：加入后第 1、2、4、7、15、30 天各复习一次")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "第 ${safePage + 1} / $pageCount 页（本页 ${pageWords.size} 个）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 380.dp)
                    ) {
                        pageWords.forEach { word ->
                            val shown = !recallFirst || revealed[word.id] == true
                            Card {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(word.spelling, fontWeight = FontWeight.SemiBold)
                                        if (word.phonetic.isNotBlank()) {
                                            Text(
                                                "  ${word.phonetic}",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { onSpeak(word.spelling) }) {
                                            Icon(
                                                Icons.Filled.VolumeUp,
                                                contentDescription = "发音",
                                                tint = SpeakBlue
                                            )
                                        }
                                        Spacer(Modifier.weight(1f))
                                        IconButton(
                                            onClick = { vm.markReviewed(word, true) },
                                            enabled = shown
                                        ) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "记住了",
                                                tint = if (shown) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { vm.markReviewed(word, false) },
                                            enabled = shown
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "没记住",
                                                tint = if (shown) MaterialTheme.colorScheme.error
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (!shown) {
                                        TextButton(onClick = { revealed[word.id] = true }) {
                                            Text("显示释义", fontSize = 12.sp)
                                        }
                                    } else {
                                        if (word.meaning.isNotBlank()) {
                                            Text(word.meaning, fontSize = 13.sp)
                                        }
                                        if (word.mnemonic.isNotBlank()) {
                                            Text(
                                                "💡 ${word.mnemonic}",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (word.family.isNotBlank()) {
                                            Text(
                                                "🔗 ${word.family}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            "答对 ${word.reviewCorrectCount} 次",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { page = (safePage - 1).coerceAtLeast(0) },
                            enabled = safePage > 0
                        ) { Text("上一页") }
                        TextButton(
                            onClick = { page = (safePage + 1).coerceAtMost(pageCount - 1) },
                            enabled = safePage < pageCount - 1
                        ) { Text("下一页") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } }
    )
}

/**
 * 复习战绩明细（P1）：点统计卡的正确率区打开。
 * 五项数据与统计卡片同源（都来自 vm.stats），口径写清楚避免误读。
 */
@Composable
fun StatsDetailDialog(stats: Stats, onClose: () -> Unit) {
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("复习战绩明细") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stats.accuracyPercent >= 0) {
                    Text(
                        "累计正确率：${stats.accuracyPercent}%（对 ${stats.totalCorrect} · 错 ${stats.totalWrong}）",
                        fontSize = 14.sp
                    )
                } else {
                    Text("累计正确率：暂无数据（还没点过 ✓/✗）", fontSize = 14.sp)
                }
                Text("复习总次数：${stats.totalReviews}", fontSize = 13.sp)
                Text(
                    "连续复习天数：${stats.streakDays} 天 · " +
                        if (stats.streakDays == 0) "今天还没开始复习"
                        else if (stats.reviewedToday) "今天已复习，连击继续"
                        else "今天还没复习，连击暂从昨天算起",
                    fontSize = 13.sp
                )
                Text("本周复习天数：${stats.weekReviewDays} 天（周一起算）", fontSize = 13.sp)
                Text(
                    "最近一次复习：" +
                        if (stats.lastReviewedAtMax > 0) fmt.format(java.util.Date(stats.lastReviewedAtMax))
                        else "从未复习",
                    fontSize = 13.sp
                )
                Text(
                    "口径：正确率 = 累计答对 ÷（累计答对 + 累计答错），答错不会抹掉历史；" +
                        "连续天数在有复习行为的日子上连续累计，某天完全没复习才会中断；" +
                        "本周天数按周一起算、最多 7 天。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}

/** 随机抽测：盖释义 + 记住了/没记住，结果写入艾宾浩斯调度 */
@Composable
fun QuizDialog(vm: WordBookViewModel, onSpeak: (String) -> Unit, onClose: () -> Unit) {
    var current by remember { mutableStateOf<Word?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var seenCount by remember { mutableIntStateOf(0) }
    val seen = remember { mutableStateListOf<Long>() }

    fun loadNext() {
        revealed = false
        vm.quizNext(seen.toSet()) { next ->
            if (next == null) {
                finished = true
            } else {
                seen.add(next.id)
                seenCount++
                current = next
            }
        }
    }

    LaunchedEffect(Unit) { loadNext() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("随机抽测") },
        text = {
            when {
                finished -> Text(
                    if (seenCount > 1) "本轮刷完 ${seenCount - 1} 个没记牢的词，明天继续！"
                    else "词库里还没有「学习中」的单词可以抽测"
                )
                current == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp))
                    Text(" 出题中…", fontSize = 13.sp)
                }
                else -> {
                    val word = current!!
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(word.spelling, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { onSpeak(word.spelling) }) {
                                Icon(Icons.Filled.VolumeUp, contentDescription = "发音", tint = SpeakBlue)
                            }
                            Text(
                                "第 ${seenCount} 题",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (word.phonetic.isNotBlank()) {
                            Text(word.phonetic, fontSize = 13.sp)
                        }
                        if (revealed) {
                            Text(word.meaning, fontSize = 15.sp)
                            if (word.example.isNotBlank()) {
                                Text(
                                    word.example,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val word = current
            when {
                finished -> TextButton(onClick = onClose) { Text("结束") }
                word != null && !revealed -> TextButton(onClick = { revealed = true }) { Text("看释义") }
                word != null && revealed -> Row {
                    TextButton(onClick = {
                        vm.markReviewed(word, false)
                        loadNext()
                    }) { Text("没记住") }
                    Button(onClick = {
                        vm.markReviewed(word, true)
                        loadNext()
                    }) { Text("记住了") }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (!finished && current != null) {
                TextButton(onClick = onClose) { Text("退出") }
            }
        }
    )
}

/** 单词详情：全字段 + 点例句朗读 + 与 AI 多轮问答 */
@Composable
fun WordDetailDialog(
    vm: WordBookViewModel,
    word: Word,
    onSpeak: (String) -> Unit,
    onEdit: () -> Unit,
    onClose: () -> Unit
) {
    val msgs = remember(word.id) { mutableStateListOf<Pair<String, String>>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word.spelling)
                if (word.phonetic.isNotBlank()) {
                    Text("  ${word.phonetic}", fontSize = 13.sp)
                }
                IconButton(onClick = { onSpeak(word.spelling) }) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = "发音", tint = SpeakBlue)
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = 320.dp)
            ) {
                if (word.meaning.isNotBlank()) Text(word.meaning, fontSize = 15.sp)
                if (word.example.isNotBlank()) {
                    Text(
                        word.example,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onSpeak(word.example) },
                        textAlign = TextAlign.Left
                    )
                    Text(
                        "👆 点例句可朗读",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (word.mnemonic.isNotBlank()) Text("💡 ${word.mnemonic}", fontSize = 13.sp)
                if (word.family.isNotBlank()) {
                    Text(
                        "🔗 同根词：${word.family}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text("🤖 与 AI 交流这个词", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                if (msgs.isEmpty()) {
                    Text(
                        "试试：和 retain 区别？语气有多正式？再给一个例句？",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                msgs.forEach { (role, content) ->
                    Text(
                        (if (role == "user") "你： " else "AI： ") + content,
                        fontSize = 13.sp,
                        color = if (role == "user") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("问点什么…", fontSize = 13.sp) },
                        singleLine = true
                    )
                    TextButton(
                        onClick = {
                            val question = input.trim()
                            if (question.isNotBlank() && !busy) {
                                msgs.add("user" to question)
                                input = ""
                                busy = true
                                vm.askAbout(word, msgs.toList()) { reply ->
                                    busy = false
                                    msgs.add("assistant" to (reply ?: "(AI 请求失败，检查右上角设置里的 API Key)"))
                                }
                            }
                        },
                        enabled = !busy
                    ) { Text("发送") }
                }
            }
        },
        confirmButton = {
            Row {
                // 编辑已有单词（问题 #4）
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        }
    )
}

/**
 * 添加 / **编辑** 单词（问题 #4）。
 * 传 editing 时进入编辑模式：字段预填原值，保存走 updateWord（保留 id、复习统计与创建时间）。
 * 支持分享/剪贴板/快捷方式预填；AI 查词一键填全。
 */
@Composable
fun AddWordDialog(
    vm: WordBookViewModel,
    prefill: String = "",
    editing: Word? = null,
    onClose: () -> Unit
) {
    val isEdit = editing != null
    var spelling by remember { mutableStateOf(editing?.spelling ?: prefill) }
    var phonetic by remember { mutableStateOf(editing?.phonetic ?: "") }
    var meaning by remember { mutableStateOf(editing?.meaning ?: "") }
    var example by remember { mutableStateOf(editing?.example ?: "") }
    var mnemonic by remember { mutableStateOf(editing?.mnemonic ?: "") }
    var family by remember { mutableStateOf(editing?.family ?: "") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(
                when {
                    isEdit -> "编辑「${editing!!.spelling}」"
                    prefill.isBlank() -> "添加单词"
                    else -> "把「$prefill」存进词库"
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = spelling,
                    onValueChange = { spelling = it },
                    label = { Text("单词") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            aiBusy = true
                            aiError = null
                            vm.aiLookupOutcome(spelling.trim()) { outcome ->
                                val result = outcome.value
                                if (result != null) {
                                    phonetic = result.phonetic
                                    meaning = result.meaning
                                    example = result.example
                                    mnemonic = result.mnemonic
                                    family = WordTextUtils.familyText(result.family)
                                } else {
                                    aiError = outcome.error ?: "查询失败，稍后重试"
                                }
                                aiBusy = false
                            }
                        },
                        enabled = spelling.isNotBlank() && !aiBusy
                    ) {
                        if (aiBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text("🤖 AI 查词")
                        }
                    }
                }
                aiError?.let { msg ->
                    Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = phonetic,
                    onValueChange = { phonetic = it },
                    label = { Text("音标") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = meaning,
                    onValueChange = { meaning = it },
                    label = { Text("释义") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = example,
                    onValueChange = { example = it },
                    label = { Text("例句") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mnemonic,
                    onValueChange = { mnemonic = it },
                    label = { Text("记忆法") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = family,
                    onValueChange = { family = it },
                    label = { Text("同根词（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (spelling.isNotBlank()) {
                        val edited = Word(
                            spelling = spelling.trim(),
                            phonetic = phonetic.trim(),
                            meaning = meaning.trim(),
                            example = example.trim(),
                            mnemonic = mnemonic.trim(),
                            family = family.trim()
                        )
                        if (isEdit) {
                            // 编辑：保留原记录的身份与学习数据
                            vm.updateWord(
                                editing!!.copy(
                                    spelling = edited.spelling,
                                    phonetic = edited.phonetic,
                                    meaning = edited.meaning,
                                    example = edited.example,
                                    mnemonic = edited.mnemonic,
                                    family = edited.family
                                )
                            )
                        } else {
                            vm.addWord(edited)
                        }
                        onClose()
                    }
                },
                enabled = spelling.isNotBlank()
            ) { Text(if (isEdit) "保存修改" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        }
    )
}

/**
 * 批量 AI 查词：
 * 阶段一（输入）粘贴词表 + 实时预览识别/重复/无效 → 开始
 * 阶段二（进行/完成）进度、失败重试、取消（保留已完成）
 */
@Composable
fun BatchAddDialog(vm: WordBookViewModel, onClose: () -> Unit) {
    val state by vm.batch.collectAsState()
    var input by remember { mutableStateOf("") }
    val preview = remember(input) {
        com.example.wordbook.util.WordTextUtils.parseBatch(input, emptySet(), WordBookViewModel.BATCH_MAX)
    }
    val hasKey = remember { vm.hasApiKey() }
    val started = state.running || state.finished

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("批量 AI 查词") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)
            ) {
                if (!started) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("粘贴单词（每行一个或空格/逗号分隔）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6
                    )
                    Text(
                        "识别 ${preview.accepted.size} 个 · 重复 ${preview.duplicates.size} · 无效 ${preview.invalid.size}" +
                            if (preview.truncated > 0) " · 超出上限 ${preview.truncated} 个" else "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "中文、词性、音标会被自动忽略；不支持带空格的词组（如 give up 会被当两个词）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (preview.accepted.size > 50) {
                        Text(
                            "⚠️ 本次共 ${preview.accepted.size} 个词，按每词约 2 秒估算需 ${preview.accepted.size * 2} 秒左右，" +
                                "并消耗 ${preview.accepted.size} 次 API 调用。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!hasKey) {
                        Text(
                            "还没有配置 API Key：先去右上角 ⚙️ 填写后再来。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Text(
                        "进度 ${state.done}/${state.total} · 已入库 ${state.inserted}" +
                            if (state.skippedExisting > 0) " · 跳过重复 ${state.skippedExisting}" else "",
                        fontSize = 14.sp
                    )
                    if (state.running) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.total == 0) 0f else state.done.toFloat() / state.total
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "正在查：${state.currentWord}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "可以点「后台继续」关掉这个窗口，任务会接着跑（主页会显示进度）。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            when {
                                state.canceled -> "已取消：已完成的 ${state.inserted} 个词已入库，未跑的没有处理。"
                                state.noApiKey -> "没有配置 API Key，未执行任何查询。"
                                state.total == 0 -> "没有可处理的单词：识别到的新词为 0（可能全是重复或无效输入）。"
                                else -> "完成：成功入库 ${state.inserted} 个 · 失败 ${state.failed.size} 个"
                            },
                            fontSize = 14.sp
                        )
                        if (state.skippedInvalid.isNotEmpty()) {
                            Text(
                                "无效输入 ${state.skippedInvalid.size} 个：${state.skippedInvalid.take(10).joinToString(", ")}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.failed.isNotEmpty()) {
                            Text(
                                "失败：${state.failed.joinToString(", ")}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                !started -> TextButton(
                    onClick = { vm.startBatch(input) },
                    enabled = preview.accepted.isNotEmpty() && hasKey
                ) { Text("开始批量查词") }

                state.running -> TextButton(onClick = { vm.cancelBatch() }) { Text("取消") }

                state.failed.isNotEmpty() -> TextButton(onClick = { vm.retryFailedBatch() }) { Text("重试失败项") }

                else -> {}
            }
        },
        dismissButton = {
            if (state.running) {
                TextButton(onClick = onClose) { Text("后台继续") }
            } else {
                TextButton(onClick = {
                    vm.resetBatch()
                    onClose()
                }) { Text(if (started) "完成" else "取消") }
            }
        }
    )
}

/**
 * AI 服务设置：厂商（预设 8 家 + 自定义）、模型、baseUrl、API Key、测试连接。
 * 所有厂商都走 OpenAI 兼容的 /chat/completions 协议，因此只有这三个变量不同。
 */
@Composable
fun SettingsDialog(vm: WordBookViewModel, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var providerId by remember { mutableStateOf(vm.currentProviderId()) }
    var showPicker by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var key by remember(providerId) { mutableStateOf(vm.apiKeyFor(providerId)) }
    var model by remember(providerId) { mutableStateOf(vm.modelFor(providerId)) }
    var baseUrl by remember(providerId) { mutableStateOf(vm.baseUrlFor(providerId)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val provider = AiProviders.byId(providerId)

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择 AI 厂商") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 400.dp)
                ) {
                    vm.providers().forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    providerId = p.id
                                    showPicker = false
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = p.id == providerId,
                                onClick = {
                                    providerId = p.id
                                    showPicker = false
                                }
                            )
                            Column {
                                Text(p.displayName, fontSize = 14.sp)
                                if (p.hint.isNotBlank()) {
                                    Text(
                                        p.hint,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } }
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("AI 服务设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("厂商：${provider.displayName}") }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    placeholder = { Text(AiProviders.byId(providerId).defaultModel.ifBlank { "如 gpt-4o-mini" }) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("baseUrl（自定义时必填）") },
                    placeholder = { Text("https://api.example.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    if (provider.hint.isNotBlank()) "Key 申请：${provider.hint}" else "填你自己的 OpenAI 兼容服务地址",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            testing = true
                            testResult = null
                            vm.testAiConnection(providerId, key, model, baseUrl) { ok, msg ->
                                testing = false
                                testResult = ok to msg
                            }
                        },
                        enabled = !testing
                    ) {
                        if (testing) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        else Text("测试连接")
                    }
                    testResult?.let { (ok, msg) ->
                        Text(
                            msg,
                            fontSize = 12.sp,
                            color = if (ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    "所有 AI 功能（AI 查词、批量查词、AI 会话、详情页问答）都用这里选的厂商；" +
                        "每个厂商的 Key 分开保存，切换后无需重填。\n\n" +
                        "数据仍全部保存在手机本地。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ---- P3：主题手动覆盖 ----
                Text("主题", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                        FilterChip(
                            selected = ThemePrefs.mode == mode,
                            onClick = { ThemePrefs.set(context, mode) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                // ---- P3：TTS 音色与语速 ----
                Text("发音（TTS）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                var voicePickerOpen by remember { mutableStateOf(false) }
                var rate by remember { mutableFloatStateOf(TtsController.rate()) }
                OutlinedButton(
                    onClick = { voicePickerOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("音色：" + (TtsController.currentVoiceName() ?: "自动（Natural > Google > Premium > 本地）"))
                }
                if (voicePickerOpen) {
                    AlertDialog(
                        onDismissRequest = { voicePickerOpen = false },
                        title = { Text("选择发音音色") },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 380.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { TtsController.setVoice(context, null); voicePickerOpen = false }
                                        .padding(vertical = 4.dp)
                                ) {
                                    RadioButton(selected = false, onClick = { TtsController.setVoice(context, null); voicePickerOpen = false })
                                    Text("自动挑选（推荐）", fontSize = 13.sp)
                                }
                                if (TtsController.voiceOptions().isEmpty()) {
                                    Text("当前引擎没有可枚举的英文音色（可能未安装语音包）", fontSize = 12.sp)
                                }
                                TtsController.voiceOptions().forEach { (name, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { TtsController.setVoice(context, name); voicePickerOpen = false }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        RadioButton(
                                            selected = TtsController.currentVoiceName() == name,
                                            onClick = { TtsController.setVoice(context, name); voicePickerOpen = false }
                                        )
                                        Text(label, fontSize = 12.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { voicePickerOpen = false }) { Text("关闭") } }
                    )
                }
                Text("语速：${"%.2f".format(rate)}x", fontSize = 12.sp)
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { TtsController.setRate(context, rate) },
                    valueRange = TtsController.MIN_RATE..TtsController.MAX_RATE
                )
                TextButton(
                    onClick = { TtsController.setRate(context, rate); TtsController.speakPreview() },
                    enabled = TtsController.isReady()
                ) { Text("试听（The quick brown fox…）") }

                // ---- P3：会话单条消息收词上限 ----
                Text("AI 会话单条上限", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WordBookViewModel.CHAT_MAX_WORDS_OPTIONS.forEach { n ->
                        FilterChip(
                            selected = vm.chatMaxWords() == n,
                            onClick = { vm.setChatMaxWords(n) },
                            label = { Text("$n 个", fontSize = 12.sp) }
                        )
                    }
                }

                // 复习偏好（P1）：先回忆再判定
                var recallFirst by remember { mutableStateOf(vm.reviewRecallFirst()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = recallFirst,
                        onCheckedChange = {
                            recallFirst = it
                            vm.setReviewRecallFirst(it)
                        }
                    )
                    Text("复习先回忆再判定（默认开）", fontSize = 12.sp)
                }

                // 云同步入口（问题 #7）
                OutlinedButton(
                    onClick = { showSync = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "  云同步（WebDAV）" +
                            if (vm.syncConfigured()) "：已配置" else "：未配置"
                    )
                }
                Text(
                    "版本 v0.2（versionCode 9）\n" +
                        "桌面小组件：长按桌面空白处 → 小组件/小部件 → 找到「单词本」拖到桌面，" +
                        "显示今日待复习数与掌握进度，点一下直接开始复习。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveAiConfig(providerId, key, model, baseUrl)
                onClose()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        }
    )

    if (showSync) {
        SyncDialog(vm = vm, onClose = { showSync = false })
    }
}
/**
 * WebDAV 云同步（P0）设置区：服务器 / 路径 / 账号 / 密码 / 自动同步开关 / 定时间隔 /
 * 测试连接 / 立即同步 / 重置同步状态 / 上次同步时间与远端 rev。
 * 默认预填 Seafile（现网 http://47.121.121.215:9998，路径 /seafdav/yeyouluoVault/单词本）。
 */
@Composable
fun SyncDialog(vm: WordBookViewModel, onClose: () -> Unit) {
    var server by remember { mutableStateOf(vm.syncServer()) }
    var basePath by remember { mutableStateOf(vm.syncBasePath()) }
    var user by remember { mutableStateOf(vm.syncUser()) }
    var pass by remember { mutableStateOf(vm.syncPassword()) }
    var auto by remember { mutableStateOf(vm.syncAuto()) }
    var interval by remember { mutableIntStateOf(vm.syncIntervalMinutes()) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    val state by vm.sync.collectAsState()
    var lastAt by remember { mutableStateOf(vm.syncLastAt()) }
    var lastRev by remember { mutableStateOf(vm.syncLastRev()) }
    var lastError by remember { mutableStateOf(vm.syncLastError()) }

    // 同步结束后刷新展示区
    LaunchedEffect(state.running, state.message) {
        if (!state.running) {
            lastAt = vm.syncLastAt()
            lastRev = vm.syncLastRev()
            lastError = vm.syncLastError()
        }
    }

    if (showIntervalPicker) {
        AlertDialog(
            onDismissRequest = { showIntervalPicker = false },
            title = { Text("定时同步间隔") },
            text = {
                Column {
                    SyncScheduler.INTERVAL_OPTIONS.forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { interval = m; showIntervalPicker = false }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = interval == m, onClick = { interval = m; showIntervalPicker = false })
                            Text(if (m == 0) "关闭定时同步" else "每 $m 分钟")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showIntervalPicker = false }) { Text("关闭") } }
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("WebDAV 同步（Seafile）") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "同步内容只有词库（含全部学习进度），不含 AI Key、不含界面偏好。" +
                        "凭据只存本机，不会出现在日志与导出的 CSV 里。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("服务器") },
                    placeholder = { Text(WebDavSync.DEFAULT_SERVER) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text("同步路径") },
                    placeholder = { Text(WebDavSync.DEFAULT_BASE_PATH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("账号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = auto, onCheckedChange = { auto = it })
                    Text("自动同步（启动后 / 回到前台 / 定时）", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { showIntervalPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "定时间隔：" +
                            if (interval == 0) "关闭" else "每 $interval 分钟"
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            vm.saveSyncConfig(server, basePath, user, pass, auto, interval)
                            vm.testSync()
                        },
                        enabled = !state.running
                    ) { Text("测试连接") }
                    TextButton(
                        onClick = {
                            vm.saveSyncConfig(server, basePath, user, pass, auto, interval)
                            vm.syncNow(manual = true)
                        },
                        enabled = !state.running
                    ) { Text("立即同步") }
                }
                TextButton(
                    onClick = {
                        vm.saveSyncConfig(server, basePath, user, pass, auto, interval)
                        vm.resetSyncState()
                    },
                    enabled = !state.running
                ) { Text("重置同步状态") }

                if (state.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text("  ${state.message}", fontSize = 12.sp)
                    }
                } else if (state.message.isNotBlank()) {
                    Text(
                        state.message,
                        fontSize = 12.sp,
                        color = if (state.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }

                val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US) }
                Text(
                    "上次同步：" + if (lastAt > 0) fmt.format(java.util.Date(lastAt)) else "从未",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "远端 rev：" + lastRev.ifBlank { "（尚未同步过）" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lastError.isNotBlank()) {
                    Text(
                        "上次失败原因：$lastError",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "冲突策略：最后写入优先（按远端 exportedAt 与本机上次同步时间比较），" +
                        "败落的一侧会自动导出 CSV 备份到应用目录（保留最近 7 份）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSyncConfig(server, basePath, user, pass, auto, interval)
                onClose()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        }
    )
}
