package com.alcohol.englishstudying.ui.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alcohol.englishstudying.data.remote.DeckDto
import com.alcohol.englishstudying.data.remote.WordBriefDto
import com.alcohol.englishstudying.di.AppContainer

/**
 * 云端词表的调色板。刻意不直接用 MaterialTheme：
 * 设计稿是一套固定的蓝灰 + 白卡片，跟主题里的薄荷绿 secondary 不一样；
 * 深色模式给一套对应的暗色，避免固定浅色在夜里刺眼。
 */
private data class CloudPalette(
    val primary: Color,
    val primarySoft: Color,
    val teal: Color,
    val tealSoft: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val line: Color,
    val green: Color,
    val greenSoft: Color,
    val danger: Color
)

private val LightPalette = CloudPalette(
    primary = Color(0xFF3B5B7A),
    primarySoft = Color(0xFFE3EAF1),
    teal = Color(0xFF3A6B63),
    tealSoft = Color(0xFFDCEBE7),
    bg = Color(0xFFF7F7FA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEEF0F4),
    text = Color(0xFF1A1C1E),
    text2 = Color(0xFF5B6067),
    text3 = Color(0xFF8B9099),
    line = Color(0xFFE4E6EB),
    green = Color(0xFF2E6B4F),
    greenSoft = Color(0xFFDFEFE6),
    danger = Color(0xFF8C3B3B)
)

private val DarkPalette = CloudPalette(
    primary = Color(0xFFA8C6E0),
    primarySoft = Color(0xFF26333F),
    teal = Color(0xFF9FD3C9),
    tealSoft = Color(0xFF1F3530),
    bg = Color(0xFF121417),
    surface = Color(0xFF1D2024),
    surface2 = Color(0xFF272B30),
    text = Color(0xFFE6E7EA),
    text2 = Color(0xFFB6BBC2),
    text3 = Color(0xFF8B9099),
    line = Color(0xFF33383F),
    green = Color(0xFF9FD3C9),
    greenSoft = Color(0xFF21352C),
    danger = Color(0xFFE8A0A0)
)

private val LocalCloudPalette = compositionLocalOf { LightPalette }

@Composable
private fun CloudTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalCloudPalette provides palette, content = content)
}

private val cloud: CloudPalette
    @Composable get() = LocalCloudPalette.current

/**
 * 云端词表。点词库页的云图标进来 —— 不再是弹窗。
 *
 * 一条脊柱：**卡片就地展开**，改名 / 简介 / 可见性 / 分享码 / 导入 / 删除都在展开区里完成；
 * 新建和「从其他词表导入」用底部面板，和设计稿一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDecksScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenDeckDetail: (Long) -> Unit,
    viewModel: CloudDecksViewModel = viewModel(factory = CloudDecksViewModel.factory(container))
) {
    CloudTheme {
        val p = cloud
        val state by viewModel.state.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current

        var showCreate by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf<DeckDto?>(null) }
        var deleting by remember { mutableStateOf<DeckDto?>(null) }
        var editingCode by remember { mutableStateOf<DeckDto?>(null) }

        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it)
                viewModel.consumeMessage()
            }
        }

        Scaffold(
            containerColor = p.bg,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "云端词表",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.text
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = p.text2
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = p.text2)
                        }
                        IconButton(
                            onClick = { showCreate = true },
                            enabled = state.loggedIn && !state.busy
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "新建词表", tint = p.text2)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            if (!state.loggedIn) {
                LoginPrompt(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onLogin = onLogin
                )
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AccountCard(
                    userName = state.userName,
                    serverUrl = state.serverUrl,
                    total = state.decks.size,
                    onSwitch = onLogin
                )
                SearchBar(
                    query = state.query,
                    onChange = viewModel::onQueryChange,
                    onClear = viewModel::clearQuery,
                    onSubmit = viewModel::refresh
                )
                ScopePills(
                    scope = state.scope,
                    official = state.officialCount,
                    public = state.publicCount,
                    mine = state.mineCount,
                    onSelect = viewModel::setScope
                )

                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.codeHit?.let { hit ->
                            item("code-hit") {
                                InfoBanner(
                                    text = "分享码直达 · 已定位到「${hit.name}」",
                                    icon = Icons.Filled.Link
                                )
                            }
                        }

                        items(state.visibleDecks, key = { it.id }) { deck ->
                            DeckCard(
                                deck = deck,
                                mine = state.isMine(deck),
                                local = state.isLocal(deck),
                                expanded = state.expandedId == deck.id,
                                busy = state.busy,
                                downloading = state.downloadingId == deck.id,
                                preview = state.previews[deck.id].orEmpty(),
                                hasMyDecks = state.mineCount > 0,
                                onToggle = { viewModel.toggle(deck) },
                                onDownload = { viewModel.download(deck) },
                                onRename = { name -> viewModel.updateDeck(deck.id, name = name) },
                                onDescribe = { text -> viewModel.updateDeck(deck.id, description = text) },
                                onVisibility = { visibility -> viewModel.updateDeck(deck.id, visibility = visibility) },
                                onCopyCode = {
                                    val code = deck.prettyShareCode
                                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    manager.setPrimaryClip(ClipData.newPlainText("分享码", code))
                                    viewModel.note("分享码 $code 已复制")
                                },
                                onEditCode = { editingCode = deck },
                                onResetCode = { viewModel.resetShareCode(deck.id) },
                                onOpenDetail = { onOpenDeckDetail(deck.id) },
                                onImportHere = { importing = deck },
                                onDelete = { deleting = deck }
                            )
                        }

                        if (state.codeMiss) {
                            item("code-miss") {
                                HintCard(
                                    "没搜到",
                                    "分享码是 4–32 位字母数字（默认 8 位、显示成 4-4），" +
                                        "也可能是对方把词表改回「私有」了。"
                                )
                            }
                        }

                        if (!state.loading && state.visibleDecks.isEmpty() && state.codeHit == null) {
                            item("empty") {
                                HintCard(
                                    "这里还没有词表",
                                    if (state.scope == CloudScope.MINE) {
                                        "点右上角 ＋ 建一张，可以从现有词表挂载词过来。"
                                    } else {
                                        "换个页签，或者清空搜索词再看看。"
                                    }
                                )
                            }
                        }
                    }

                    if (state.loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = p.primary,
                            trackColor = p.surface2
                        )
                    }
                }
            }
        }

        if (showCreate) {
            CreateDeckSheet(
                decks = state.decks,
                busy = state.busy,
                onDismiss = { showCreate = false },
                onCreate = { name, desc, visibility, seedId ->
                    showCreate = false
                    viewModel.createDeck(name, desc, visibility, seedId)
                }
            )
        }

        importing?.let { target ->
            ImportFromSheet(
                target = target,
                candidates = state.decks.filter { it.id != target.id && it.wordCount > 0 },
                busy = state.busy,
                onDismiss = { importing = null },
                onImport = { source, mode ->
                    importing = null
                    viewModel.importFrom(target, source, mode)
                }
            )
        }

        deleting?.let { deck ->
            ConfirmDeleteDialog(
                deck = deck,
                onDismiss = { deleting = null },
                onConfirm = {
                    deleting = null
                    viewModel.deleteDeck(deck)
                }
            )
        }

        editingCode?.let { deck ->
            ShareCodeDialog(
                deck = deck,
                busy = state.busy,
                onDismiss = { editingCode = null },
                onSave = { code ->
                    editingCode = null
                    viewModel.updateDeck(deck.id, shareCode = code)
                }
            )
        }
    }
}

// ---------------------------------------------------------------- 顶部三块

@Composable
private fun LoginPrompt(modifier: Modifier = Modifier, onLogin: () -> Unit) {
    val p = cloud
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = p.primary
        )
        Text("还没有登录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text)
        Text(
            "云端词表要登录后才能看：官方词表、别人公开的词表、以及你自己的。",
            fontSize = 12.5.sp,
            color = p.text3,
            lineHeight = 20.sp
        )
        Button(
            onClick = onLogin,
            colors = ButtonDefaults.buttonColors(containerColor = p.primary),
            shape = RoundedCornerShape(11.dp)
        ) { Text("去登录") }
    }
}

@Composable
private fun AccountCard(
    userName: String,
    serverUrl: String,
    total: Int,
    onSwitch: () -> Unit
) {
    val p = cloud
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = p.surface,
        border = BorderStroke(1.dp, p.line)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(p.green, CircleShape)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    userName.ifBlank { "未登录" },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text
                )
                Text(
                    "$serverUrl · $total 张词表",
                    fontSize = 11.5.sp,
                    color = p.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = p.primarySoft,
                modifier = Modifier.clickable(onClick = onSwitch)
            ) {
                Text(
                    "切换",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 12.5.sp,
                    color = p.primary
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    val p = cloud
    val focus = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(46.dp)
            .background(p.surface2, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = p.text3
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "搜词表名，或粘贴分享码 K7Q2-9F3M",
                    fontSize = 13.5.sp,
                    color = p.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = p.text),
                cursorBrush = SolidColor(p.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    onSubmit()
                }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(Color(0xFFC9CED8), CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "清空",
                    modifier = Modifier.size(13.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ScopePills(
    scope: String,
    official: Int,
    public: Int,
    mine: Int,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ScopePill("全部", null, scope == CloudScope.ALL) { onSelect(CloudScope.ALL) }
        ScopePill("官方", official, scope == CloudScope.OFFICIAL) { onSelect(CloudScope.OFFICIAL) }
        ScopePill("公开", public, scope == CloudScope.PUBLIC) { onSelect(CloudScope.PUBLIC) }
        ScopePill("我的", mine, scope == CloudScope.MINE) { onSelect(CloudScope.MINE) }
    }
}

@Composable
private fun ScopePill(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val p = cloud
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (selected) p.primary else p.surface,
        border = BorderStroke(1.dp, if (selected) p.primary else p.line),
        modifier = Modifier
            .height(30.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 12.5.sp,
                color = if (selected) Color.White else p.text2
            )
            if (count != null && count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    count.toString(),
                    fontSize = 11.sp,
                    color = if (selected) Color.White.copy(alpha = 0.75f) else p.text3
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 卡片

@Composable
private fun DeckCard(
    deck: DeckDto,
    mine: Boolean,
    local: Boolean,
    expanded: Boolean,
    busy: Boolean,
    downloading: Boolean,
    preview: List<WordBriefDto>,
    hasMyDecks: Boolean,
    onToggle: () -> Unit,
    onDownload: () -> Unit,
    onRename: (String) -> Unit,
    onDescribe: (String) -> Unit,
    onVisibility: (String) -> Unit,
    onCopyCode: () -> Unit,
    onEditCode: () -> Unit,
    onResetCode: () -> Unit,
    onOpenDetail: () -> Unit,
    onImportHere: () -> Unit,
    onDelete: () -> Unit
) {
    val p = cloud
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = p.surface,
        border = if (expanded) BorderStroke(1.dp, p.line) else null
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            deck.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(6.dp))
                        DeckBadge(deck)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildMeta(deck),
                            fontSize = 12.sp,
                            color = p.text3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (local) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(5.dp), color = p.tealSoft) {
                                Text(
                                    "已在本机",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    fontSize = 11.sp,
                                    color = p.teal
                                )
                            }
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .padding(start = 8.dp, top = 2.dp)
                        .size(20.dp),
                    tint = p.text3
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = p.line)
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (mine) {
                            InlineEditRow(
                                label = "名称",
                                value = deck.name,
                                placeholder = "词表名",
                                onSave = onRename
                            )
                            InlineEditRow(
                                label = "简介",
                                value = deck.description.orEmpty(),
                                placeholder = "加一句说明，别人搜到时能看到",
                                onSave = onDescribe
                            )
                            VisibilityRow(visibility = deck.visibility, onSelect = onVisibility)
                            if (deck.visibility == "public" && !deck.shareCode.isNullOrBlank()) {
                                ShareCodeCard(
                                    code = deck.prettyShareCode,
                                    onCopy = onCopyCode,
                                    onEdit = onEditCode,
                                    onReset = onResetCode
                                )
                            }
                        } else {
                            Text(
                                deck.description ?: "这张词表还没有写简介。",
                                fontSize = 12.5.sp,
                                color = p.text2,
                                lineHeight = 20.sp
                            )
                        }

                        if (preview.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("前 ${preview.size} 个词", fontSize = 11.5.sp, color = p.text3)
                            Spacer(Modifier.height(6.dp))
                            PreviewChips(preview = preview, total = deck.wordCount)
                        }

                        Spacer(Modifier.height(14.dp))

                        if (mine) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("内容", fontSize = 11.5.sp, color = p.text3)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "查看全部 ${deck.wordCount} 词 ›",
                                    fontSize = 12.sp,
                                    color = p.primary,
                                    modifier = Modifier.clickable(onClick = onOpenDetail)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            SoftButton(
                                text = "从其他词表导入",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onImportHere,
                                enabled = !busy
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                modifier = Modifier
                                    .clickable(enabled = !busy, onClick = onDelete)
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = p.danger
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("删除这张词表", fontSize = 12.5.sp, color = p.danger)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onDownload,
                                    modifier = Modifier.weight(1f),
                                    enabled = !downloading && deck.wordCount > 0,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = p.primary,
                                        contentColor = Color.White,
                                        disabledContainerColor = p.primary.copy(alpha = 0.4f),
                                        disabledContentColor = Color.White
                                    )
                                ) {
                                    if (downloading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(15.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (downloading) "下载中…" else "下载到本机", fontSize = 13.sp)
                                }
                                SoftButton(
                                    text = "导入到我的词表",
                                    onClick = onImportHere,
                                    enabled = !busy && hasMyDecks
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildMeta(deck: DeckDto): String {
    val parts = mutableListOf("${deck.wordCount} 词")
    val date = deck.updatedAt?.take(10)
    if (date != null && date.length == 10) parts += "更新 ${date.substring(5)}"
    if (!deck.official && !deck.ownerName.isNullOrBlank()) parts += "by ${deck.ownerName}"
    return parts.joinToString(" · ")
}

@Composable
private fun DeckBadge(deck: DeckDto) {
    val p = cloud
    val (text, bg, fg) = when {
        deck.official -> Triple("官方", p.primarySoft, p.primary)
        deck.visibility == "public" -> Triple("公开", p.greenSoft, p.green)
        else -> Triple("私有", p.surface2, p.text3)
    }
    Surface(shape = RoundedCornerShape(5.dp), color = bg) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            fontSize = 10.5.sp,
            color = fg
        )
    }
}

@Composable
private fun PreviewChips(preview: List<WordBriefDto>, total: Int) {
    val p = cloud
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        preview.forEach { word ->
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = p.surface,
                border = BorderStroke(1.dp, p.line)
            ) {
                Text(
                    word.headword,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    fontSize = 11.5.sp,
                    color = p.text2
                )
            }
        }
        if (total > preview.size) {
            Text("+${total - preview.size}", fontSize = 11.5.sp, color = p.text3)
        }
    }
}

@Composable
private fun InlineEditRow(
    label: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit
) {
    val p = cloud
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(46.dp), fontSize = 12.5.sp, color = p.text3)
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.5.sp, color = p.text),
                cursorBrush = SolidColor(p.primary),
                modifier = Modifier
                    .weight(1f)
                    .background(p.surface2, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
            IconButton(onClick = {
                editing = false
                onSave(text)
            }) {
                Icon(Icons.Filled.Check, contentDescription = "保存", tint = p.primary)
            }
        } else {
            Text(
                value.ifBlank { placeholder },
                modifier = Modifier.weight(1f),
                fontSize = 13.5.sp,
                color = if (value.isBlank()) p.text3 else p.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "改",
                modifier = Modifier
                    .clickable {
                        text = value
                        editing = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = 12.5.sp,
                color = p.primary
            )
        }
    }
}

@Composable
private fun VisibilityRow(visibility: String, onSelect: (String) -> Unit) {
    val p = cloud
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("可见性", modifier = Modifier.width(46.dp), fontSize = 12.5.sp, color = p.text3)
        Row(
            modifier = Modifier
                .weight(1f)
                .background(p.surface2, RoundedCornerShape(11.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            SegmentOption(
                text = "私有",
                selected = visibility == "private",
                modifier = Modifier.weight(1f)
            ) { onSelect("private") }
            SegmentOption(
                text = "公开",
                selected = visibility == "public",
                modifier = Modifier.weight(1f)
            ) { onSelect("public") }
        }
    }
}

@Composable
private fun SegmentOption(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val p = cloud
    Surface(
        modifier = modifier
            .height(28.dp)
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) p.surface else Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 12.sp,
                color = if (selected) p.primary else p.text2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ShareCodeCard(
    code: String,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit
) {
    val p = cloud
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = p.surface,
        border = BorderStroke(1.dp, p.line)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分享码", fontSize = 11.sp, color = p.text3)
                Spacer(Modifier.width(8.dp))
                Text(
                    code,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.primary
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = p.primarySoft,
                    modifier = Modifier.clickable(onClick = onCopy)
                ) {
                    Text(
                        "复制",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        color = p.primary
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "别人在搜索框粘贴这串码就能打开；改回私有后码会保留但失效。",
                fontSize = 11.sp,
                color = p.text3,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniOutline("改码", onEdit)
                MiniOutline("重置", onReset)
            }
        }
    }
}

@Composable
private fun MiniOutline(text: String, onClick: () -> Unit) {
    val p = cloud
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = p.surface,
        border = BorderStroke(1.dp, p.line),
        modifier = Modifier
            .height(30.dp)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.sp, color = p.text2)
        }
    }
}

@Composable
private fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val p = cloud
    Surface(
        modifier = modifier
            .height(38.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) p.primarySoft else p.surface2
    ) {
        Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 13.sp,
                color = if (enabled) p.primary else p.text3
            )
        }
    }
}

// ---------------------------------------------------------------- 底部面板

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateDeckSheet(
    decks: List<DeckDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String?, visibility: String, seedFromDeckId: Long?) -> Unit
) {
    val p = cloud
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("private") }
    var seedMode by remember { mutableStateOf("blank") }
    var seed by remember { mutableStateOf<DeckDto?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = p.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
            Text("新建词表", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "建好后可以随时改名、改可见性，或者继续往里加词。",
                fontSize = 12.sp,
                color = p.text3
            )
            Spacer(Modifier.height(14.dp))

            FilledField(value = name, placeholder = "词表名", onValueChange = { name = it })
            Spacer(Modifier.height(8.dp))
            FilledField(
                value = description,
                placeholder = "简介（可选，别人搜到时能看到）",
                onValueChange = { description = it },
                maxLines = 2
            )

            Spacer(Modifier.height(14.dp))
            LabelRow("可见性")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(p.surface2, RoundedCornerShape(11.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SegmentOption("私有", visibility == "private", Modifier.weight(1f)) { visibility = "private" }
                SegmentOption("公开", visibility == "public", Modifier.weight(1f)) { visibility = "public" }
            }

            Spacer(Modifier.height(14.dp))
            LabelRow("初始内容")
            Text(
                "可以建一张空表，也可以把下面这张表的词挂过来（只挂载，不复制词条）。",
                fontSize = 11.sp,
                color = p.text3,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                PickRow(
                    title = "空白",
                    subtitle = "之后自己搜词加进去",
                    selected = seedMode == "blank"
                ) {
                    seedMode = "blank"
                    seed = null
                }
                decks.filter { it.wordCount > 0 }.take(30).forEach { deck ->
                    PickRow(
                        title = deck.name,
                        subtitle = "${deck.wordCount} 词 · " + when {
                            deck.official -> "官方"
                            deck.visibility == "public" -> "公开"
                            else -> "我的"
                        },
                        selected = seed?.id == deck.id
                    ) {
                        seedMode = "seed"
                        seed = deck
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("取消", onDismiss, Modifier.weight(1f))
                Button(
                    onClick = {
                        val seedId = if (seedMode == "seed") seed?.id else null
                        onCreate(name, description.ifBlank { null }, visibility, seedId)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    enabled = !busy && name.isNotBlank(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.primary,
                        contentColor = Color.White,
                        disabledContainerColor = p.surface2,
                        disabledContentColor = p.text3
                    )
                ) { Text("创建", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportFromSheet(
    target: DeckDto,
    candidates: List<DeckDto>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (DeckDto, String) -> Unit
) {
    val p = cloud
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var source by remember { mutableStateOf<DeckDto?>(null) }
    var mode by remember { mutableStateOf("append") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = p.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
            Text(
                "导入到「${target.name}」",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "只把对方的词挂过来，不复制词条：原表更新这边跟着变。",
                fontSize = 12.sp,
                color = p.text3
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (candidates.isEmpty()) {
                    Text("没有可导入的词表", fontSize = 13.sp, color = p.text3)
                }
                candidates.forEach { deck ->
                    PickRow(
                        title = deck.name,
                        subtitle = "${deck.wordCount} 词 · " + when {
                            deck.official -> "官方"
                            deck.visibility == "public" -> "公开"
                            else -> "我的"
                        },
                        selected = source?.id == deck.id
                    ) { source = deck }
                }
            }

            Spacer(Modifier.height(12.dp))
            LabelRow("方式")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(p.surface2, RoundedCornerShape(11.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SegmentOption("追加合并", mode == "append", Modifier.weight(1f)) { mode = "append" }
                SegmentOption("替换现有", mode == "replace", Modifier.weight(1f)) { mode = "replace" }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "追加时按词头去重，已有的词不会重复，也不改学习进度。",
                fontSize = 11.sp,
                color = p.text3
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("取消", onDismiss, Modifier.weight(1f))
                Button(
                    onClick = { source?.let { onImport(it, mode) } },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    enabled = !busy && source != null,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = p.primary,
                        contentColor = Color.White,
                        disabledContainerColor = p.surface2,
                        disabledContentColor = p.text3
                    )
                ) { Text("导入", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 38.dp, height = 4.dp)
                .background(Color(0xFFD7DBE3), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun LabelRow(text: String) {
    val p = cloud
    Text(text, fontSize = 12.sp, color = p.text3, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun FilledField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    maxLines: Int = 1
) {
    val p = cloud
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.surface2, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 13.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 13.5.sp, color = p.text3)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 14.sp, color = p.text),
            cursorBrush = SolidColor(p.primary),
            maxLines = maxLines,
            singleLine = maxLines == 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val p = cloud
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 1.5.dp,
                    color = if (selected) p.primary else Color(0xFFC3C9D3),
                    shape = CircleShape
                )
                .background(if (selected) p.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.5.sp,
                color = p.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, fontSize = 11.5.sp, color = p.text3)
        }
    }
}

// ---------------------------------------------------------------- 对话框

@Composable
private fun ConfirmDeleteDialog(
    deck: DeckDto,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val p = cloud
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text("删除「${deck.name}」？", color = p.text) },
        text = {
            Text(
                "服务器上的这张词表会消失（${deck.wordCount} 个词）。如果本机已经下载过，本机那份不受影响。",
                fontSize = 13.sp,
                color = p.text2,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = p.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.text2) } }
    )
}

@Composable
private fun ShareCodeDialog(
    deck: DeckDto,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val p = cloud
    var code by remember { mutableStateOf(deck.shareCode.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.surface,
        title = { Text("自定义分享码", color = p.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledField(
                    value = code,
                    placeholder = "4–32 位字母或数字",
                    onValueChange = { code = it.uppercase() }
                )
                Text(
                    "只校验唯一性；横线和空格会被忽略（存成大写）。",
                    fontSize = 11.sp,
                    color = p.text3
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(code) }, enabled = !busy && code.length >= 4) {
                Text("保存", color = p.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = p.text2) } }
    )
}

// ---------------------------------------------------------------- 小提示

@Composable
private fun InfoBanner(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val p = cloud
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = p.primarySoft
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = p.primary)
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 12.5.sp, color = p.primary)
        }
    }
}

@Composable
private fun HintCard(title: String, body: String) {
    val p = cloud
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = p.surface,
        border = BorderStroke(1.dp, p.line)
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Spacer(Modifier.height(3.dp))
            Text(body, fontSize = 11.5.sp, color = p.text3, lineHeight = 17.sp)
        }
    }
}
