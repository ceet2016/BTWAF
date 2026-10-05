package com.bt.waf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.model.ListConfig
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.ManagedListViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 列表项右下角的操作区：启停开关 + 更多菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowActions(
    config: ListConfig,
    enabled: Boolean,
    onToggle: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDetail: (() -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onToggle != null) {
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
        if (onDelete != null || onDetail != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onDetail != null) {
                        DropdownMenuItem(
                            text = { Text("查看详情") },
                            leadingIcon = { Icon(Icons.Filled.Visibility, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onDetail() }
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagedListScreen(config: ListConfig) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: ManagedListViewModel = viewModel(
        key = "list_${config.key}",
        factory = WafViewModelFactory(repo, config)
    )
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var showCreate by remember { mutableStateOf(false) }
    var deleteIndex by remember { mutableStateOf(-1) }
    var detailItem by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var createErr by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(config.key) { vm.load() }

    // ── 日志类列表自动轮询 ──────────────────────────────────────────
    // 只有 config.liveRefreshMillis != null 的列表才开（日志类，
    // 见 ListConfig 里的说明），配置类列表保持一次性加载。
    //
    // 三个必须挡住的场景，否则轮询会变成 bug：
    //  1. page != 1 —— 用户往下翻了页。load() 内部会 page=1，
    //     一次轮询就把人家从第 5 页拽回第 1 页。
    //  2. keyword 非空 —— 用户正在搜。轮询会用当前 keyword 重拉，
    //     虽然结果对，但列表整段跳动，正在点的那一行会跑位。
    //  3. 任何弹窗打开 —— 新增/删除确认/详情，操作中刷新最让人恼火。
    val busy = showCreate || deleteIndex >= 0 || detailItem != null || clearConfirm
    AutoRefresh(
        // liveRefreshMillis 为 null 时传 0，AutoRefresh 会直接不注册协程
        intervalMillis = config.liveRefreshMillis ?: 0L,
        isLoading = vm.loading,
        enabled = vm.page == 1 && vm.keyword.isBlank() && !busy
    ) { vm.load() }

    // 触底自动加载下一页
    LaunchedEffect(listState, vm.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (last >= vm.items.size - 3 && vm.items.isNotEmpty()) vm.loadMore()
            }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // SubPageScaffold 而不是 Scaffold：内层 Scaffold 会再消费一遍
    // windowInsets，顶部多出约 250px 空白（详见 SubPageScaffold 注释）。
    SubPageScaffold(
        snackbarHost = snackbar,
        floatingActionButton = {
            if (config.createPath != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        fieldValues.clear()
                        config.createFields.forEach { fieldValues[it.key] = it.defaultValue }
                        createErr = null
                        showCreate = true
                    },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("新增") }
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 顶部工具条 ──
            Column(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                // 统一走 SearchToolbar：原来给 TextField 写死 52dp，
                // 而 M3 TextField 最小高度是 56dp —— 压下去会把占位文字
                // 「搜索…」上沿切掉一截（实机可见），旁边按钮也跟着不齐。
                SearchToolbar(
                    keyword = vm.keyword,
                    onKeywordChange = { vm.keyword = it },
                    onSearch = { keyboard?.hide(); vm.load() },
                    onClear = if (vm.keyword.isNotEmpty()) {
                        { vm.keyword = ""; vm.load() }
                    } else null,
                    onClearLogs = if (config.clearPath != null) {
                        { clearConfirm = true }
                    } else null,
                )
                if (config.dateFilter) {
                    Spacer(Modifier.height(Space.sm))
                    DateRangeChips(
                        selected = vm.dateRange,
                        onSelect = { vm.dateRange = it; vm.load() }
                    )
                }
            }

            // 日志类列表：明确告诉用户「这条在自动刷新」。
            // 否则用户盯着屏幕看新攻击不出现，以为功能坏了 ——
            // 而实际上只是他不知道该等下一次轮询。
            // 非日志类（配置类列表）不显示，别给静态数据加假标记。
            if (config.liveRefreshMillis != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.lg, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveBadge(
                        active = vm.page == 1 && vm.keyword.isBlank() && !busy,
                        label = if (vm.page == 1 && vm.keyword.isBlank() && !busy)
                            "实时 · 每 ${config.liveRefreshMillis / 1000} 秒自动刷新"
                        else "已暂停（翻页或搜索中）"
                    )
                }
            }

            LoadingBar(vm.loading)

            ErrorBanner(vm.error) { vm.load() }

            Box(Modifier.fillMaxSize()) {
                when {
                    vm.loading && vm.items.isEmpty() -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center)
                    )

                    vm.items.isEmpty() -> EmptyState(
                        icon = Icons.Filled.Inbox,
                        title = "暂无数据",
                        description = if (vm.error != null) "接口返回异常，请点击重试"
                        else "该模块当前没有记录",
                        actionText = if (vm.error != null) "重试" else null,
                        onAction = { vm.load() }
                    )

                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = Space.lg, end = Space.lg,
                            top = Space.md, bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(Space.sm)
                    ) {
                        item {
                            WafCard(
                                contentPadding = rowCardPadding
                            ) {
                                vm.items.forEachIndexed { idx, item ->
                                    if (idx > 0) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    val title = pickString(item, *config.titleKeys.toTypedArray())
                                    val sub = pickString(item, *config.subtitleKeys.toTypedArray())
                                    val on = item[config.toggleStateKey].toBoolState()
                                    ListRow(
                                        icon = config.icon,
                                        title = title.ifBlank { "(无标题)" },
                                        subtitle = sub.ifBlank {
                                            if (config.togglePath != null) {
                                                if (on) "已启用" else "已停用"
                                            } else null
                                        },
                                        iconTint = if (on) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        iconSize = 34.dp,
                                        trailing = {
                                            RowActions(
                                                config = config,
                                                enabled = on,
                                                onToggle = if (config.togglePath != null) {
                                                    { vm.toggle(idx) { e -> if (e != null) vm.error = e } }
                                                } else null,
                                                onDelete = if (config.deletePath != null) {
                                                    { deleteIndex = idx }
                                                } else null,
                                                onDetail = { detailItem = item }
                                            )
                                        },
                                        onClick = { detailItem = item }
                                    )
                                }
                            }
                        }
                        if (vm.loadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(Space.md), Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        } else if (!vm.hasMore && vm.items.isNotEmpty()) {
                            item {
                                Text(
                                    "— 共 ${vm.total} 条，已全部加载 —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.lg),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /* ── 新增弹窗 ── */
    if (showCreate) {
        FormDialog(
            title = "新增 · ${config.title}",
            icon = Icons.Filled.Add,
            confirmText = "确定",
            busy = creating,
            error = createErr,
            onDismiss = { if (!creating) { showCreate = false; createErr = null } },
            onConfirm = {
                creating = true
                createErr = null
                vm.create(fieldValues.toMap()) { err ->
                    creating = false
                    if (err == null) showCreate = false else createErr = err
                }
                null
            }
        ) {
            config.createFields.forEach { f ->
                Spacer(Modifier.height(Space.sm))
                if (f.options != null) {
                    DropdownField(
                        fieldValues[f.key] ?: f.defaultValue,
                        { fieldValues[f.key] = it },
                        f.label, f.options
                    )
                } else {
                    LabeledField(
                        fieldValues[f.key] ?: f.defaultValue,
                        { fieldValues[f.key] = it; createErr = null },
                        f.label, f.hint,
                        singleLine = !f.multiline
                    )
                }
            }
        }
    }

    /* ── 删除确认 ── */
    if (deleteIndex >= 0 && deleteIndex < vm.items.size) {
        val item = vm.items[deleteIndex]
        AlertDialog(
            onDismissRequest = { deleteIndex = -1 },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(deleteIndex) { e -> deleteIndex = -1; if (e != null) vm.error = e }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteIndex = -1 }) { Text("取消") } },
            title = { Text("确认删除") },
            text = {
                Text(
                    pickString(item, *config.titleKeys.toTypedArray()).ifBlank { "该条目" },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) }
        )
    }

    /* ── 清空确认 ── */
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    vm.clear { e -> vm.error = e ?: "已清空" }
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } },
            title = { Text("确认清空${config.title}") },
            text = { Text("该操作不可恢复，确定要清空全部记录吗？", style = MaterialTheme.typography.bodyMedium) },
            icon = { Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) }
        )
    }

    /* ── 详情弹窗 ── */
    detailItem?.let { item ->
        AlertDialog(
            onDismissRequest = { detailItem = null },
            confirmButton = { TextButton(onClick = { detailItem = null }) { Text("关闭") } },
            title = {
                Text(
                    pickString(item, *config.titleKeys.toTypedArray()).ifBlank { "详情" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items(item.entries.size) { i ->
                        val e = item.entries.toList()[i]
                        DataRowDivider(e.key, e.value.toString())
                    }
                }
            }
        )
    }
}

/** 日期范围快捷筛选 chips。 */
@Composable
private fun DateRangeChips(selected: String, onSelect: (String) -> Unit) {
    val today = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
    }
    val yesterday = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() - 86400_000L))
    }
    val options = listOf("" to "全部", today to "今天", yesterday to "昨天")
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = selected == v,
                onClick = { onSelect(v) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}
