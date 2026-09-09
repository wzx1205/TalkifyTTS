package com.github.lonepheasantwarrior.talkify.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.book.epub.EpubParser
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.scan.CharacterScanner
import com.github.lonepheasantwarrior.talkify.book.store.CharacterBook
import com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore
import com.github.lonepheasantwarrior.talkify.book.store.VoiceAutoAssign
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalVoiceCatalog
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 听书角色册：导入 EPUB → 全书扫描 → 角色 → 音色绑定
 *
 * 绑定结果由 [CharacterBookStore.activeVoiceFor] 在合成路由热路径消费。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookCharactersScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf(CharacterBookStore.list()) }
    var activeBookId by remember { mutableStateOf(CharacterBookStore.activeBookId()) }
    var scanning by remember { mutableStateOf(false) }
    var expandedVoiceFor by remember { mutableStateOf<String?>(null) }

    val bundledVoices = remember {
        // 本地 ZipVoice 内置音色 + MiMo 预置音色 + Edge 免费音色，供跨供应商绑定
        // （运行时按当前供应商的音色表校验，不属于该表的绑定自动回退槽位）
        val local = LocalVoiceCatalog.getVoices().map {
            it.voiceId to "本地·${it.displayName}"
        }
        val mimo = runCatching {
            com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlParser.parse(
                context, com.github.lonepheasantwarrior.talkify.R.xml.xiaomi_mimo_voices_v2p5
            ).map { it.id to "MiMo·${it.displayName}" }
        }.getOrDefault(emptyList())
        val edge = listOf(
            "zh-CN-YunxiNeural" to "Edge·云希(青年男)",
            "zh-CN-YunjianNeural" to "Edge·云健(浑厚男)",
            "zh-CN-YunyangNeural" to "Edge·云野(播音男)",
            "zh-CN-YunxiaNeural" to "Edge·云夏(少年)",
            "zh-CN-XiaoxiaoNeural" to "Edge·晓晓(女)",
            "zh-CN-XiaoyiNeural" to "Edge·晓伊(温柔女)",
            "zh-CN-XiaoshuangNeural" to "Edge·晓双(童声)"
        )
        local + mimo + edge
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scanning = true
        scope.launch {
            try {
                val book = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val epub = EpubParser.parse(stream)
                        val profiles = CharacterScanner.scan(epub.chapters)
                        CharacterBook(
                            bookId = epub.bookId,
                            title = epub.title,
                            characters = VoiceAutoAssign.assign(profiles)
                        )
                    } ?: error("无法读取所选文件")
                }
                CharacterBookStore.save(book)
                CharacterBookStore.setActiveBook(book.bookId)
                books = CharacterBookStore.list()
                activeBookId = book.bookId
            } catch (e: Exception) {
                TtsLogger.e("EPUB import failed: ${e.message}", tag = "BookCharacters")
            } finally {
                scanning = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.book_characters_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("application/epub+zip", "application/zip", "application/octet-stream")) },
                icon = {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.FileUpload, contentDescription = null)
                    }
                },
                text = {
                    Text(
                        if (scanning) stringResource(R.string.book_characters_scanning)
                        else stringResource(R.string.book_characters_import)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (books.isNotEmpty()) {
                // 已导入的书：chip 横滑选择生效书
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    books.forEach { book ->
                        FilterChip(
                            selected = book.bookId == activeBookId,
                            onClick = {
                                CharacterBookStore.setActiveBook(book.bookId)
                                activeBookId = book.bookId
                            },
                            label = { Text(book.title) },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        CharacterBookStore.delete(book.bookId)
                                        books = CharacterBookStore.list()
                                        activeBookId = CharacterBookStore.activeBookId()
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.book_characters_delete),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            val current = books.firstOrNull { it.bookId == activeBookId }
            if (current == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.book_characters_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                CharacterList(
                    book = current,
                    bundledVoices = bundledVoices,
                    expandedVoiceFor = expandedVoiceFor,
                    onExpandToggle = { expandedVoiceFor = if (expandedVoiceFor == it) null else it },
                    onVoicePicked = { name, voiceId ->
                        CharacterBookStore.updateVoice(current.bookId, name, voiceId)
                        books = CharacterBookStore.list()
                    },
                    onNarratorVoicePicked = { voiceId ->
                        CharacterBookStore.updateNarratorVoice(current.bookId, voiceId)
                        books = CharacterBookStore.list()
                    },
                    onAutoAssign = {
                        val re = VoiceAutoAssign.assign(
                            current.characters.map {
                                com.github.lonepheasantwarrior.talkify.book.scan.CharacterProfile(
                                    name = it.name,
                                    gender = it.gender,
                                    dialogueCount = it.dialogueCount,
                                    sampleQuote = it.sampleQuote
                                )
                            }
                        )
                        CharacterBookStore.save(current.copy(characters = re))
                        books = CharacterBookStore.list()
                    }
                )
            }
        }
    }
}

@Composable
private fun CharacterList(
    book: CharacterBook,
    bundledVoices: List<Pair<String, String>>,
    expandedVoiceFor: String?,
    onExpandToggle: (String) -> Unit,
    onVoicePicked: (String, String) -> Unit,
    onNarratorVoicePicked: (String) -> Unit,
    onAutoAssign: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onAutoAssign) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = stringResource(R.string.book_characters_auto_assign))
                }
            }
        }
        // 旁白置顶：角色再多也能一眼找到
        item(key = "__narrator__") {
            val narratorVoice = book.narratorVoiceId.ifBlank {
                bundledVoices.firstOrNull { it.first == "narrator" }?.second ?: ""
            }
            val narratorDisplay = bundledVoices.firstOrNull { it.first == book.narratorVoiceId }?.second
                ?: narratorVoice
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExpandToggle("__narrator__") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Campaign,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.book_characters_narrator),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.book_characters_narrator_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = narratorDisplay,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box {
                        DropdownMenu(
                            expanded = expandedVoiceFor == "__narrator__",
                            onDismissRequest = { onExpandToggle("__narrator__") }
                        ) {
                            bundledVoices.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onNarratorVoicePicked(id)
                                        onExpandToggle("__narrator__")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        items(book.characters, key = { it.name }) { c ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onExpandToggle(c.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (c.gender) {
                            Gender.FEMALE -> Icons.Filled.Female
                            Gender.MALE -> Icons.Filled.Male
                            Gender.UNKNOWN -> Icons.Filled.HelpOutline
                        },
                        contentDescription = null,
                        tint = when (c.gender) {
                            Gender.FEMALE -> MaterialTheme.colorScheme.secondary
                            Gender.MALE -> MaterialTheme.colorScheme.primary
                            Gender.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = c.name,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = stringResource(R.string.book_characters_dialogue_format, c.dialogueCount) +
                                "  " + c.sampleQuote.take(18),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    val voiceName = bundledVoices.firstOrNull { it.first == c.voiceId }?.second ?: c.voiceId
                    Text(
                        text = voiceName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box {
                        DropdownMenu(
                            expanded = expandedVoiceFor == c.name,
                            onDismissRequest = { onExpandToggle(c.name) }
                        ) {
                            bundledVoices.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onVoicePicked(c.name, id)
                                        onExpandToggle(c.name)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
