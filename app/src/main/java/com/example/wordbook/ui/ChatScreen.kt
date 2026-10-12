package com.example.wordbook.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wordbook.ChatDraft
import com.example.wordbook.ChatUiMessage
import com.example.wordbook.WordBookViewModel

/** 写入系统剪贴板 + 轻提示（问题 #1：会话内容要能复制） */
private fun copyToClipboard(context: Context, text: String, tip: String = "已复制到剪贴板") {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("wordbook", text))
    Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
}

/**
 * AI 会话窗口（全屏 Dialog）：
 * - 贴一批单词 → 一次调用返回「文字回复 + 词卡」，勾选即可入库
 * - 提问用法 → 只回文字，不产生词卡
 * 会话历史仅在内存，关闭即清（隐私 + 简单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: WordBookViewModel, onClose: () -> Unit) {
    val messages by vm.chat.collectAsState()
    val sending by vm.chatSending.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 每条 assistant 消息内词卡的勾选状态：messageIndex -> 选中的下标集合
    val selections = remember { mutableStateMapOf<Int, Set<Int>>() }
    fun selectedOf(index: Int, size: Int): Set<Int> = selections[index] ?: (0 until size).toSet()
    fun toggle(index: Int, position: Int, size: Int) {
        val current = selectedOf(index, size).toMutableSet()
        if (!current.add(position)) current.remove(position)
        selections[index] = current
    }

    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("AI 助手") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        // 一键复制整段会话（问题 #1）
                        IconButton(
                            onClick = { copyToClipboard(context, vm.chatToPlainText(), "已复制整段会话") },
                            enabled = messages.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制整段会话")
                        }
                        TextButton(onClick = { vm.clearChat() }) { Text("清空") }
                    }
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(top = 16.dp)) {
                                Text(
                                    "把要背的单词贴进来，一次 AI 调用就能生成音标、释义、例句、记忆法和同根词，" +
                                        "勾选后一键入库。\n\n也可以直接问用法，比如「resign 和 quit 有什么区别？」。",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    itemsIndexed(messages) { index, message ->
                        MessageBubble(
                            index = index,
                            message = message,
                            selected = selectedOf(index, message.drafts.size),
                            plainText = vm.chatMessageToPlainText(message),
                            onCopy = { text, tip -> copyToClipboard(context, text, tip) },
                            onToggle = { pos -> toggle(index, pos, message.drafts.size) },
                            onAddSelected = { vm.addDrafts(index, selectedOf(index, message.drafts.size)) },
                            onRetry = { vm.retryLastChat() }
                        )
                    }
                    if (sending) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text("  AI 正在思考…", fontSize = 13.sp)
                            }
                        }
                    }
                }

                // 快捷提示
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    SuggestionChip(
                        onClick = { input = "resign\ncompel\nretain\n" },
                        label = { Text("贴单词模板", fontSize = 12.sp) }
                    )
                    SuggestionChip(
                        onClick = { input = "resign 和 quit 有什么区别？" },
                        label = { Text("问辨析", fontSize = 12.sp) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { if (it.length <= 4000) input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("贴一批单词，或问点什么…", fontSize = 13.sp) },
                        maxLines = 6
                    )
                    // 手动粘贴剪贴板内容（配合问题 #5：剪贴板内容要能方便地送进来）
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (text.isBlank()) {
                            Toast.makeText(context, "剪贴板是空的", Toast.LENGTH_SHORT).show()
                        } else {
                            input = (input + text).take(4000)
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴剪贴板")
                    }
                    IconButton(
                        onClick = {
                            val text = input
                            input = ""
                            vm.sendChat(text)
                        },
                        enabled = input.isNotBlank() && !sending
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    index: Int,
    message: ChatUiMessage,
    selected: Set<Int>,
    plainText: String,
    onCopy: (String, String) -> Unit,
    onToggle: (Int) -> Unit,
    onAddSelected: () -> Unit,
    onRetry: () -> Unit
) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (message.text.isNotBlank()) {
                    // 长按可自由选中文字（问题 #1）
                    SelectionContainer {
                        Text(
                            message.text,
                            fontSize = 14.sp,
                            color = if (message.error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.text.isNotBlank()) {
                        TextButton(onClick = { onCopy(plainText, "已复制这条消息") }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(" 复制", fontSize = 12.sp)
                        }
                    }
                    if (message.error) {
                        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
                    }
                }

                // 词卡区
                if (message.drafts.isNotEmpty()) {
                    val addable = message.drafts.indices.filter { i ->
                        !message.drafts[i].existed && !message.drafts[i].added && i in selected
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        message.drafts.forEachIndexed { pos, draft ->
                            DraftCard(
                                draft = draft,
                                checked = pos in selected,
                                onToggle = { onToggle(pos) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            "共 ${message.drafts.size} 个词条",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onAddSelected, enabled = addable.isNotEmpty()) {
                            Text("入库选中 (${addable.size})")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftCard(draft: ChatDraft, checked: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(8.dp)
        ) {
            Checkbox(
                checked = checked && !draft.existed && !draft.added,
                onCheckedChange = { onToggle() },
                enabled = !draft.existed && !draft.added
            )
            Column(modifier = Modifier.padding(top = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(draft.word.spelling, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (draft.word.phonetic.isNotBlank()) {
                        Text(
                            "  ${draft.word.phonetic}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        draft.existed -> Text(
                            "  已存在",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        draft.added -> Text(
                            "  ✓ 已入库",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (draft.word.meaning.isNotBlank()) {
                    Text(draft.word.meaning, fontSize = 13.sp)
                }
                if (draft.word.example.isNotBlank()) {
                    Text(
                        draft.word.example,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (draft.word.mnemonic.isNotBlank()) {
                    Text("💡 ${draft.word.mnemonic}", fontSize = 12.sp)
                }
                if (draft.word.family.isNotBlank()) {
                    Text(
                        "🔗 ${draft.word.family}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
