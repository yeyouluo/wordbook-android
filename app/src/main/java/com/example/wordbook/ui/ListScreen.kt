package com.example.wordbook.ui

import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wordbook.Stats
import com.example.wordbook.WordBookViewModel
import com.example.wordbook.data.Word
import com.example.wordbook.util.CsvUtil
import com.example.wordbook.util.DailyJobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主界面：列表 + 搜索/筛选/排序 + 统计卡片 + 所有弹窗的编排 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordBookApp(
    vm: WordBookViewModel = viewModel(),
    onSpeak: (String) -> Unit,
    pendingWord: String?,
    pendingAdd: Boolean,
    pendingReview: Boolean,
    onPendingConsumed: () -> Unit
) {
    val words by vm.words.collectAsState()
    val query by vm.query.collectAsState()
    val filter by vm.filter.collectAsState()
    val sortAlpha by vm.sortAlpha.collectAsState()
    val stats by vm.stats.collectAsState()
    val context = LocalContext.current
    val ioScope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var clipboardHint by remember { mutableStateOf<String?>(null) }
    var showBatch by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    val batchState by vm.batch.collectAsState()

    var showReview by remember { mutableStateOf(false) }
    var showQuiz by remember { mutableStateOf<Word?>(null) }
    var detailWord by remember { mutableStateOf<Word?>(null) }
    var editingWord by remember { mutableStateOf<Word?>(null) }

    // ---- 定向入口：分享接收 / 桌面快捷方式 ----
    LaunchedEffect(pendingWord) {
        if (!pendingWord.isNullOrBlank()) {
            addPrefill = pendingWord
            showAddDialog = true
            onPendingConsumed()
        }
    }
    LaunchedEffect(pendingAdd) {
        if (pendingAdd) {
            showAddDialog = true
            onPendingConsumed()
        }
    }
    LaunchedEffect(pendingReview) {
        if (pendingReview) {
            showReview = true
            onPendingConsumed()
        }
    }

    // ---- 通知权限（Android 13+，只请求一次） ----
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !DailyJobs.hasNotificationPermission(context)) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- 剪贴板捕捉（问题 #5）：每次回到前台都检查一次 ----
    // 之前只在进程启动时读一次，导致「复制生词 → 切回 App」不触发；现在挂到 ON_RESUME，
    // 同一段内容只处理一次（shouldHandleClipboard 去重），不会反复弹窗。
    val prefs: SharedPreferences = remember {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    }
    fun checkClipboard() {
        try {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            val match = Regex("^[A-Za-z][A-Za-z\\-'’]{1,40}$").find(text) ?: return
            if (prefs.getString("last_clipboard", "") == match.value) return
            if (!vm.shouldHandleClipboard(match.value)) return
            clipboardHint = match.value
        } catch (e: Exception) {
            // 某些系统限制读剪贴板（或未授权），静默降级
        }
    }

    LaunchedEffect(Unit) {
        delay(600)
        checkClipboard()
    }

    // 云同步提示（冲突等）弹一次 toast 后清空
    val syncToast by vm.syncToast.collectAsState()
    LaunchedEffect(syncToast) {
        syncToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.syncToast.value = null
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) checkClipboard()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ---- 导出 CSV ----
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            ioScope.launch(Dispatchers.IO) {
                try {
                    val all = vm.getAllWords()
                    val csv = CsvUtil.buildCsv(all)
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(csv.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已导出 ${all.size} 条", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ---- 导入 CSV ----
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            ioScope.launch(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (text.isBlank()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "文件为空", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val result = vm.importCsvText(text)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "导入 ${result.added} 条，跳过 ${result.skipped} 条（含重复）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("单词本") },
                actions = {
                    IconButton(onClick = { showBatch = true }) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = "批量 AI 查词")
                    }
                    IconButton(onClick = { showChat = true }) {
                        Icon(Icons.Filled.Chat, contentDescription = "AI 会话窗口")
                    }
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
                    }) { Icon(Icons.Filled.FileUpload, contentDescription = "导入") }
                    IconButton(onClick = {
                        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        exportLauncher.launch("单词本-$today.csv")
                    }) { Icon(Icons.Filled.FileDownload, contentDescription = "导出 Excel") }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                addPrefill = ""
                showAddDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "添加单词")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text("搜索单词或释义") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true
            )

            // 筛选 + 排序
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            ) {
                FilterChip(
                    selected = filter == "all",
                    onClick = { vm.filter.value = "all" },
                    label = { Text("全部") }
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = filter == "learning",
                    onClick = { vm.filter.value = "learning" },
                    label = { Text("学习中") }
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = filter == "mastered",
                    onClick = { vm.filter.value = "mastered" },
                    label = { Text("已掌握") }
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.sortAlpha.value = !sortAlpha }) {
                    Icon(
                        Icons.Filled.Sort,
                        contentDescription = if (sortAlpha) "按时间排序" else "按字母排序",
                        tint = if (sortAlpha) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (words.isEmpty() && query.isEmpty() && filter == "all" && stats.total == 0) {
                Text(
                    text = "还没有单词，点右下角 + 添加\n\n" +
                        "顶栏 🎵 可以一次粘贴一批单词自动 AI 查词\n" +
                        "顶栏 💬 开个 AI 会话：边聊边把词收进词库\n" +
                        "点蓝色小喇叭可以听发音，⬆️⬇️ 支持 Excel/CSV 导入导出",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // 复习统计卡片
                    StatsBanner(
                        stats = stats,
                        onReview = { showReview = true },
                        onQuiz = { showQuiz = Word(id = -1, spelling = "") }
                    )
                    // 批量任务进行中：给一个回到进度页的入口（弹窗关掉后也能找回）
                    if (batchState.running) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clickable { showBatch = true }
                        ) {
                            Text(
                                "🤖 批量查词中 ${batchState.done}/${batchState.total}（点此查看进度）",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    words.forEach { word ->
                        WordCard(
                            word = word,
                            onSpeak = { onSpeak(word.spelling) },
                            onSpeakExample = { if (word.example.isNotBlank()) onSpeak(word.example) },
                            onToggleMastered = { vm.toggleMastered(word) },
                            onEdit = { editingWord = word },
                            onDelete = { vm.deleteWord(word) },
                            onOpenDetail = { detailWord = word }
                        )
                    }
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddWordDialog(vm = vm, prefill = addPrefill, onClose = { showAddDialog = false })
    }
    // 编辑已有单词（问题 #4）
    editingWord?.let { word ->
        AddWordDialog(vm = vm, editing = word, onClose = { editingWord = null })
    }
    if (showSettings) {
        SettingsDialog(vm = vm, onClose = { showSettings = false })
    }
    if (showBatch) {
        BatchAddDialog(vm = vm, onClose = { showBatch = false })
    }
    if (showChat) {
        ChatScreen(vm = vm, onClose = { showChat = false })
    }
    if (showReview) {
        ReviewDialog(vm = vm, onSpeak = onSpeak, onClose = { showReview = false })
    }
    if (showQuiz != null) {
        QuizDialog(vm = vm, onSpeak = onSpeak, onClose = { showQuiz = null })
    }
    detailWord?.let { word ->
        WordDetailDialog(
            vm = vm,
            word = word,
            onSpeak = onSpeak,
            onEdit = {
                editingWord = word
                detailWord = null
            },
            onClose = { detailWord = null }
        )
    }

    // 剪贴板提示
    clipboardHint?.let { word ->
        AlertDialog(
            onDismissRequest = {
                // 点空白关闭也算“已处理”，写进 prefs 免得旋转屏幕后又弹一次
                prefs.edit().putString("last_clipboard", word).apply()
                clipboardHint = null
            },
            title = { Text("剪贴板里发现了生词？") },
            text = { Text("检测到剪贴板内容「$word」\n要加入单词本吗？") },
            confirmButton = {
                TextButton(onClick = {
                    addPrefill = word
                    showAddDialog = true
                    prefs.edit().putString("last_clipboard", word).apply()
                    clipboardHint = null
                }) { Text("加入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.edit().putString("last_clipboard", word).apply()
                    clipboardHint = null
                }) { Text("不用了") }
            }
        )
    }
}

@Composable
fun StatsBanner(stats: Stats, onReview: () -> Unit, onQuiz: () -> Unit) {
    if (stats.total == 0) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "📚 已掌握 ${stats.mastered} / 共 ${stats.total} · 学习中 ${stats.learning}",
                fontSize = 14.sp
            )
            Text(
                if (stats.dueWords.isEmpty()) "🔔 今日没有待复习的单词，继续保持！"
                else "🔔 今日待复习 ${stats.dueWords.size} 个（记住 5 次即标记掌握）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            // 复习战绩：正确率 / 连击 / 本周
            if (stats.accuracyPercent >= 0) {
                Text(
                    "📈 正确率 ${stats.accuracyPercent}%（对 ${stats.totalCorrect} · 错 ${stats.totalWrong}）" +
                        " · 🔥 连续 ${stats.streakDays} 天 · 本周 ${stats.weekReviewDays} 天",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                Text(
                    if (stats.totalReviews > 0)
                        "📈 正确率从 0.1 版起重新累计（历史复习只存了连对次数）；再复习一次即可看到数据"
                    else
                        "📈 还没有复习记录：做一轮复习后这里会显示正确率与连击天数",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onReview, enabled = stats.dueWords.isNotEmpty()) {
                    Text("开始复习")
                }
                TextButton(onClick = onQuiz, enabled = stats.learning > 0) {
                    Text("随机抽测")
                }
            }
        }
    }
}

/** 单词卡片：长按打开详情+AI 问答；右列四个按钮：发音/编辑/掌握切换/删除 */
@Composable
fun WordCard(
    word: Word,
    onSpeak: () -> Unit,
    onSpeakExample: () -> Unit,
    onToggleMastered: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenDetail: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.pointerInput(word.id) {
            detectTapGestures(onLongPress = { onOpenDetail() })
        }) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = word.spelling,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onSpeak() }
                        )
                        if (word.phonetic.isNotBlank()) {
                            Text(
                                text = "  ${word.phonetic}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (word.meaning.isNotBlank()) {
                        Text(
                            text = word.meaning,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (word.example.isNotBlank()) {
                        Text(
                            text = word.example,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp).clickable { onSpeakExample() }
                        )
                    }
                    if (word.mnemonic.isNotBlank()) {
                        Text(
                            text = "💡 ${word.mnemonic}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (word.family.isNotBlank()) {
                        Text(
                            text = "🔗 ${word.family}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = onSpeak) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = "发音",
                            tint = SpeakBlue
                        )
                    }
                    IconButton(onClick = onToggleMastered) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = if (word.mastered) "标记为学习中" else "标记为已掌握",
                            tint = if (word.mastered) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 编辑（问题 #4）
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "编辑",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
