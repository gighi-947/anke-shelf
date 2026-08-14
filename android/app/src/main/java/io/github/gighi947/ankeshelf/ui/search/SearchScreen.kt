package io.github.gighi947.ankeshelf.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.gighi947.ankeshelf.data.SearchChapterGroup
import io.github.gighi947.ankeshelf.data.SearchIndex
import io.github.gighi947.ankeshelf.data.SearchResponse
import io.github.gighi947.ankeshelf.service.AppContainer
import io.github.gighi947.ankeshelf.service.BookUi
import io.github.gighi947.ankeshelf.service.RepoResult
import io.github.gighi947.ankeshelf.ui.theme.PageHeaderTitle
import io.github.gighi947.ankeshelf.ui.theme.AnkeSpacing
import io.github.gighi947.ankeshelf.ui.theme.AnkeRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PER_CHAPTER = 50

/** 全文检索页：桌面 fullsearch.js 语义 + M3 Search/FilterChip/Card。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    books: List<BookUi>,
    container: AppContainer,
    onOpenHit: (bookId: String, chapter: Int, offset: Int) -> Unit,
    onBack: () -> Unit,
) {
    var bookId by rememberSaveable { mutableStateOf(books.firstOrNull()?.record?.id) }
    var query by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var response by remember { mutableStateOf<SearchResponse?>(null) }
    var status by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loadingMore by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showHistory by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(books) {
        if (bookId == null || books.none { it.record.id == bookId }) {
            bookId = books.firstOrNull()?.record?.id
        }
    }

    // 当前书的会话与索引（DisposableEffect 保证退出页面时释放文件句柄）。
    val sessionState = remember(bookId) {
        val ui = books.firstOrNull { it.record.id == bookId } ?: return@remember null
        container.repository.openSession(ui.record)
    }
    val index = remember(sessionState) {
        (sessionState as? RepoResult.Ok)?.value?.let { SearchIndex(it) }
    }
    val openError = (sessionState as? RepoResult.Err)?.error?.message
    DisposableEffect(bookId) {
        onDispose {
            // session 由 SearchIndex 持有，页面销毁时关闭。
            index?.close()
        }
    }
    LaunchedEffect(bookId) {
        query = ""
        response = null
        status = ""
        expanded = emptySet()
        showHistory = false
        index?.ensureBuilt(scope)
    }

    // 防抖查询 + 索引未就绪时 600ms 重试（与桌面一致）。
    LaunchedEffect(query, caseSensitive, wholeWord, bookId) {
        val q = query.trim()
        if (q.isEmpty()) {
            response = null
            status = ""
            return@LaunchedEffect
        }
        delay(300)
        while (true) {
            val idx = index ?: run {
                status = openError?.let { "书籍打开失败：$it" } ?: "请先选择一本书"
                return@LaunchedEffect
            }
            val resp = idx.search(q, caseSensitive, wholeWord, PER_CHAPTER)
            if (!resp.ready) {
                if (resp.error.isNotEmpty()) {
                    status = "索引建立失败：${resp.error}"
                    return@LaunchedEffect
                }
                status = "正在建立索引…"
                delay(600)
                continue
            }
            status = ""
            response = resp
            expanded = resp.results.take(5).map { it.chapter_index }.toSet()
            container.searchHistory.add(bookId.orEmpty(), q)
            showHistory = false
            return@LaunchedEffect
        }
    }

    val history = remember(bookId, query) {
        val q = query.trim()
        container.searchHistory.list(bookId.orEmpty()).filter { q.isEmpty() || it.contains(q) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { PageHeaderTitle("全文检索") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (response != null && response?.results?.isNotEmpty() == true) {
                        val allOpen = response!!.results.all { it.chapter_index in expanded }
                        TextButton(onClick = {
                            expanded = if (allOpen) emptySet()
                            else response!!.results.map { it.chapter_index }.toSet()
                        }) {
                            Text(if (allOpen) "收起全部" else "展开全部")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AnkeSpacing.lg),
        ) {
            if (books.isEmpty()) {
                EmptyHint("书架为空，请先在书架页导入书籍", Icons.Filled.SearchOff)
                return@Scaffold
            }

            // 书籍选择（桌面为书内检索，安卓补一个书选择器）。
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                OutlinedTextField(
                    value = books.firstOrNull { it.record.id == bookId }?.record?.title ?: "",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("搜索书籍") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    shape = MaterialTheme.shapes.medium,
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    books.forEach { ui ->
                        DropdownMenuItem(
                            text = { Text(ui.record.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                bookId = ui.record.id
                                menuExpanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 搜索框（M3 胶囊 Search）。
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    showHistory = true
                },
                singleLine = true,
                placeholder = { Text("输入关键词搜索全书（Enter 立即搜索）…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; response = null; status = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // 立即查询由 query 变化触发；Enter 时直接执行一次。
                    scope.launch {
                        val idx = index ?: return@launch
                        val q = query.trim()
                        if (q.isEmpty()) return@launch
                        val resp = idx.search(q, caseSensitive, wholeWord, PER_CHAPTER)
                        if (resp.ready) {
                            response = resp
                            expanded = resp.results.take(5).map { it.chapter_index }.toSet()
                            container.searchHistory.add(bookId.orEmpty(), q)
                            showHistory = false
                        }
                    }
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
                shape = MaterialTheme.shapes.medium,
            )

            // 选项：大小写敏感 / 全词匹配。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AnkeSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                FilterChip(
                    selected = caseSensitive,
                    onClick = { caseSensitive = !caseSensitive },
                    label = { Text("大小写敏感") },
                )
                FilterChip(
                    selected = wholeWord,
                    onClick = { wholeWord = !wholeWord },
                    label = { Text("全词匹配") },
                )
            }

            // 历史 chips。
            AnimatedVisibility(
                visible = showHistory && history.isNotEmpty(),
                enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.96f, animationSpec = tween(160)),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
            ) {
                FlowRow(
                    modifier = Modifier.padding(top = AnkeSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
                ) {
                    history.forEach { item ->
                        Surface(
                        shape = AnkeRadius.pill,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .clickable {
                                    query = item
                                    showHistory = false
                                }
                                .padding(horizontal = AnkeSpacing.md, vertical = AnkeSpacing.sm),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(AnkeSpacing.sm))
                                Text(item, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // 状态 / 汇总。
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AnkeSpacing.sm),
                )
            } else if (response != null && response!!.ready) {
                val r = response!!
                if (r.results.isEmpty()) {
                    EmptyHint("没有匹配结果", Icons.Filled.SearchOff)
                } else {
                    Text(
                        "共 ${r.total_hits} 处命中 · ${r.hit_chapters}/${r.total_chapters} 章有结果" +
                            "（每章最多显示 $PER_CHAPTER 条，可展开更多）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AnkeSpacing.sm, bottom = AnkeSpacing.xs),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AnkeSpacing.sm),
            ) {
                items(response?.results ?: emptyList(), key = { it.chapter_index }) { group ->
                    SearchGroupCard(
                        group = group,
                        query = query.trim(),
                        isExpanded = group.chapter_index in expanded,
                        onToggle = {
                            expanded = if (group.chapter_index in expanded) {
                                expanded - group.chapter_index
                            } else {
                                expanded + group.chapter_index
                            }
                        },
                        moreLoading = group.chapter_index in loadingMore,
                        onMore = {
                            scope.launch {
                                loadingMore = loadingMore + group.chapter_index
                                try {
                                    val idx = index ?: return@launch
                                    val q = query.trim()
                                    if (q.isEmpty()) return@launch
                                    val last = group.hits.lastOrNull()
                                    val (hits, more) = idx.searchMore(
                                        q, group.chapter_index,
                                        last?.offset ?: -1, caseSensitive, wholeWord, PER_CHAPTER,
                                    )
                                    response = response?.copy(
                                        results = response!!.results.map {
                                            if (it.chapter_index == group.chapter_index) {
                                                it.copy(hits = it.hits + hits, more = more)
                                            } else {
                                                it
                                            }
                                        },
                                    )
                                } finally {
                                    loadingMore = loadingMore - group.chapter_index
                                }
                            }
                        },
                        onHit = { hit ->
                            onOpenHit(bookId.orEmpty(), group.chapter_index, hit.offset)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchGroupCard(
    group: SearchChapterGroup,
    query: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    moreLoading: Boolean,
    onMore: () -> Unit,
    onHit: (io.github.gighi947.ankeshelf.data.SearchHit) -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(200),
        label = "chevron",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(200)),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = AnkeSpacing.md, vertical = AnkeSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    group.chapter_title.ifBlank { "第 ${group.chapter_index + 1} 章" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                    .padding(start = AnkeSpacing.sm),
                )
                Surface(
                    shape = AnkeRadius.pill,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        "${group.chapter_hits} 处",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = AnkeSpacing.sm, vertical = AnkeSpacing.xxs),
                    )
                }
            }
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(start = AnkeSpacing.md, end = AnkeSpacing.md, bottom = AnkeSpacing.sm)) {
                    group.hits.forEach { hit ->
                        Text(
                            text = highlightSnippet(hit.snippet, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHit(hit) }
                                .padding(vertical = AnkeSpacing.sm, horizontal = AnkeSpacing.sm)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.shapes.small,
                                ),
                        )
                    }
                    val remain = maxOf(0, group.chapter_hits - group.hits.size)
                    if (group.more || remain > 0) {
                        TextButton(
                            onClick = onMore,
                            enabled = !moreLoading,
                            modifier = Modifier.padding(top = AnkeSpacing.xxs),
                        ) {
                            Text(
                                when {
                                    moreLoading -> "加载中…"
                                    group.more -> "显示本章更多结果（还剩 $remain 条）"
                                    else -> "显示剩余 $remain 条"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun highlightSnippet(snippet: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(snippet)
    val rx = Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
    val matches = rx.findAll(snippet).toList()
    if (matches.isEmpty()) return AnnotatedString(snippet)
    return buildAnnotatedString {
        var cursor = 0
        for (m in matches) {
            append(snippet.substring(cursor, m.range.first))
            withStyle(
                SpanStyle(
                    background = androidx.compose.ui.graphics.Color(
                        0x66E5B76B,
                    ),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            ) {
                append(snippet.substring(m.range.first, m.range.last + 1))
            }
            cursor = m.range.last + 1
        }
        append(snippet.substring(cursor))
    }
}

@Composable
private fun EmptyHint(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AnkeSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AnkeSpacing.sm),
        )
    }
}
