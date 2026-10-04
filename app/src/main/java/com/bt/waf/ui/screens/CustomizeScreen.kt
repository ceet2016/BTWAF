package com.bt.waf.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.*
import com.bt.waf.viewmodel.CustomizeViewModel
import com.bt.waf.viewmodel.NginxViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 本文件所有顶层私有辅助函数统一用 cw 前缀，避免与同包其他 Screen 的
// humanKey / ngHumanKey / wmXxx 在 JVM 签名上冲突。

// ──────────────────────────────────────────────────────────────────────────
// 一、Tab 与常量
// ──────────────────────────────────────────────────────────────────────────

private enum class CwTab(val label: String) {
    LIST("规则列表"), BLOCK_LOG("日志转规则"), TRANSFER("导入导出")
}

/** 规则模式。与后端 Entry.Src 严格对应，编辑时不可改。 */
private enum class CwMode(val src: Int, val label: String, val desc: String) {
    WAF(1, "普通模式", "放行 / 拦截 / 人机验证 / 仅记录"),
    CC(2, "CC 防护", "按阈值与时间窗限流"),
    REDIRECT(3, "重定向", "命中后跳转指定 URL")
}

private fun cwModeOf(src: Int): CwMode = CwMode.entries.firstOrNull { it.src == src } ?: CwMode.WAF

/** 执行阶段后端只认这一个值，界面只读展示，不提供下拉。 */
private const val EXEC_PHASE_ACCESS = "access"

/** 解析访问日志的最大行数，避免一次读太多撑爆渲染。 */
private const val LOG_PARSE_LIMIT = 300

/** nginx access log 里 $request 段：METHOD URI PROTO。 */
private val CW_REQUEST_REGEX = Regex("\"([A-Z]+)\\s+(\\S+)\\s+HTTP/[\\d.]+\"")

/** 日志行首的时间段：[10/Oct/2023:13:55:36 +0800] */
private val CW_TIME_REGEX = Regex("^\\S+\\s+\\S+\\s+\\[([^\\]]+)]")

/** 一条解析出来的访问日志记录。 */
private data class CwLogEntry(
    val method: String,
    val uri: String,
    val time: String,
    val picked: Boolean = false
)

/** 批量操作前的二次确认。用类承载是为了能把 lambda 存进 state。 */
private class CwConfirm(
    val title: String,
    val message: String,
    val confirmText: String = "确认",
    val danger: Boolean = true,
    val warning: String? = null,
    val onConfirm: () -> Unit
)

/** 已导出的文件信息，供分享。 */
private class CwExportInfo(val file: File, val fileName: String, val size: Long)

// ──────────────────────────────────────────────────────────────────────────
// 二、入口
// ──────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: CustomizeViewModel = viewModel(factory = WafViewModelFactory(repo))
    val nvm: NginxViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(CwTab.LIST) }
    var confirm by remember { mutableStateOf<CwConfirm?>(null) }
    var editor by remember { mutableStateOf<CwEditorState?>(null) }
    var exportInfo by remember { mutableStateOf<CwExportInfo?>(null) }
    var transferResult by remember { mutableStateOf<String?>(null) }

    // 导入用的文件选择器。服务端硬校验 .json 后缀，拿到 uri 后要先落地成本地文件。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 先把 uri 复制进缓存目录：OkHttp 的 multipart 只认 File，不认 content uri。
            val msg = withContext(Dispatchers.IO) { cwCopyToTemp(ctx, uri) }
            if (msg != null) {
                transferResult = msg
                return@launch
            }
            val f = cwImportFile(ctx)
            if (f == null) {
                transferResult = "读取所选文件失败"
            } else {
                vm.importRule(f) { err -> transferResult = err ?: "导入完成" }
            }
        }
    }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == CwTab.LIST) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vm.loadConfigHelp(CwMode.WAF.src)
                        editor = CwEditorState.newRule(vm.help)
                    },
                    icon = { Icon(Icons.Filled.Add, null, modifier = Modifier.size(19.dp)) },
                    text = { Text("新建规则") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = CwTab.entries.indexOf(tab),
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                CwTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            when (tab) {
                CwTab.LIST -> CwListTab(
                    vm = vm,
                    onCreate = {
                        vm.loadConfigHelp(CwMode.WAF.src)
                        editor = CwEditorState.newRule(vm.help)
                    },
                    onEdit = { item ->
                        val draft = cwDraftOf(item)
                        vm.loadConfigHelp(draft.src) { loaded ->
                            editor = CwEditorState.editRule(draft, loaded)
                        }
                    },
                    onDelete = { item ->
                        val name = item.s("name").ifBlank { "未命名" }
                        confirm = CwConfirm(
                            title = "删除规则",
                            message = "确认删除「$name」吗？",
                            confirmText = "确认删除",
                            warning = "删除后会立即热加载配置，已命中的流量将不再被此规则处理。",
                            onConfirm = {
                                vm.remove(listOf(item.i("id"))) { err ->
                                    if (err != null) toast(err) else toast("已提交，列表已重新拉取")
                                }
                            }
                        )
                    }
                )

                CwTab.BLOCK_LOG -> CwBlockLogTab(
                    vm = vm,
                    nvm = nvm,
                    askConfirm = { confirm = it }
                )

                CwTab.TRANSFER -> CwTransferTab(
                    vm = vm,
                    onPickFile = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onExported = { f ->
                        exportInfo = CwExportInfo(f.file, f.fileName, f.size)
                        transferResult = "已导出 ${f.fileName}（${formatBytes(f.size)}）"
                    },
                    onMessage = { transferResult = it },
                    onShare = { info ->
                        cwShareFile(ctx, info.file)?.let { ctx.startActivity(it) }
                            ?: toast("没有可用的分享应用")
                    },
                    onCloseResult = { transferResult = null }
                )
            }
        }
    }

    // 规则编辑器：全屏 Dialog。内容比普通表单长得多，AlertDialog 的高度限制放不下。
    editor?.let { st ->
        CwRuleEditor(
            state = st,
            vm = vm,
            onClose = { editor = null },
            onSaved = { msg ->
                editor = null
                toast(msg)
            }
        )
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = {
                Icon(
                    if (c.danger) Icons.Filled.Warning else Icons.Filled.Info,
                    null,
                    tint = if (c.danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(c.title) },
            text = {
                Column {
                    Text(c.message, style = MaterialTheme.typography.bodyMedium)
                    if (!c.warning.isNullOrBlank()) {
                        Spacer(Modifier.height(Space.md))
                        HintText(c.warning, danger = c.danger)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    c.onConfirm()
                }) {
                    Text(
                        c.confirmText,
                        color = if (c.danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────
// 三、Tab 1 · 规则列表
// ──────────────────────────────────────────────────────────────────────────

private val CW_FILTER_OPTIONS = listOf(
    "全部模式" to 0,
    "CC 防护" to 2,
    "重定向" to 3
)

@Composable
private fun CwListTab(
    vm: CustomizeViewModel,
    onCreate: () -> Unit,
    onEdit: (Map<String, Any?>) -> Unit,
    onDelete: (Map<String, Any?>) -> Unit
) {
    var keyword by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 92.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Rule,
                            label = "规则总数",
                            value = formatNumber(vm.total.toLong()),
                            hint = "当前筛选",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.PlaylistAddCheck,
                            label = "已加载",
                            value = formatNumber(vm.rules.size.toLong()),
                            hint = "启用中的按优先级倒序",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader("筛选与搜索", subtitle = "服务端按优先级倒序，忽略分页参数")
                    Spacer(Modifier.height(Space.sm))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        items(CW_FILTER_OPTIONS) { (label, value) ->
                            CwFilterChip(
                                label = label,
                                selected = vm.filterType == value,
                                onClick = { vm.load(value) }
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.md))
                    LabeledField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = "规则名称",
                        hint = "模糊匹配服务端 name 字段"
                    )
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("搜索") { vm.load(vm.filterType, keyword) }
                        SmallButton("重置") {
                            keyword = ""
                            vm.load(vm.filterType, "")
                        }
                    }
                }
            }

            item {
                WafCard(contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
                    if (vm.rules.isEmpty() && !vm.listLoading) {
                        EmptyState(
                            icon = Icons.Filled.Rule,
                            title = "还没有自定义规则",
                            description = "自定义规则可以按 IP、URI、请求头等条件精细匹配，命中后执行放行、拦截或重定向",
                            actionText = "新建第一条",
                            onAction = onCreate
                        )
                    } else {
                        vm.rules.forEachIndexed { idx, item ->
                            if (idx > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            CwRuleRow(
                                item = item,
                                onClick = { onEdit(item) },
                                onDelete = { onDelete(item) }
                            )
                        }
                    }
                }
            }
        }

        LoadingBar(vm.listLoading, Modifier.align(Alignment.TopCenter))
    }

    ErrorBanner(
        vm.listError,
        Modifier.padding(Space.lg),
        onRetry = { vm.load() }
    )
}

@Composable
private fun CwFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Space.md, vertical = 7.dp)
        )
    }
}

@Composable
private fun CwRuleRow(
    item: Map<String, Any?>,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val action = item["action"].asMap()
    val actionType = action.s("type")
    val enabled = item.i("status") == 1
    val isGlobal = item.i("is_global") == 1
    val servers = cwStringList(item["servers"])
    val mode = cwModeOf(item.i("src"))
    val hit = item.i("hit")

    val actionLabel = cwActionLabel(actionType)
    val accent = when (actionType) {
        "allow" -> Color(0xFF2E9E5B)
        "deny" -> MaterialTheme.colorScheme.error
        "validate" -> Color(0xFFB8860B)
        "redirect" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val icon = when (actionType) {
        "allow" -> Icons.Filled.CheckCircle
        "deny" -> Icons.Filled.Block
        "validate" -> Icons.Filled.SmartToy
        "redirect" -> Icons.Filled.CallSplit
        else -> Icons.Filled.Visibility
    }

    val scopeText = if (isGlobal) "全局生效" else {
        if (servers.isEmpty()) "未指定站点（不生效）" else servers.joinToString("、")
    }

    ListRow(
        icon = icon,
        title = item.s("name").ifBlank { "(未命名规则)" },
        subtitle = "$actionLabel · $mode.label · $scopeText",
        iconTint = if (enabled) accent else MaterialTheme.colorScheme.outline,
        iconSize = 36.dp,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!enabled) StatusChip("停用", ChipTone.NEUTRAL)
                if (hit > 0) {
                    Spacer(Modifier.width(Space.xs))
                    StatusChip("命中 $hit", ChipTone.INFO)
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Filled.MoreVert, "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑规则") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(17.dp)) },
                            onClick = { menu = false; onClick() }
                        )
                        DropdownMenuItem(
                            text = { Text("查看表达式") },
                            leadingIcon = { Icon(Icons.Filled.Code, null, Modifier.size(17.dp)) },
                            onClick = { menu = false; onClick() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete, null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(17.dp)
                                )
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────
// 四、Tab 2 · 访问日志转放行规则
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun CwBlockLogTab(
    vm: CustomizeViewModel,
    nvm: NginxViewModel,
    askConfirm: (CwConfirm) -> Unit
) {
    var siteId by remember { mutableStateOf("") }
    var siteName by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<CwLogEntry>>(emptyList()) }
    var picked by remember { mutableStateOf(0) }
    var parseNote by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (nvm.sites.isEmpty()) nvm.getSites() }

    val siteOptions = remember(nvm.sites) {
        nvm.sites.map {
            val sid = it.s("site_id").ifBlank { it.s("id") }
            val label = it.s("site_name").ifBlank { it.s("name") }.ifBlank { sid }
            label to sid
        }.filter { it.second.isNotBlank() }
    }

    fun reload() {
        if (siteId.isBlank()) return
        nvm.getSiteLog(siteId, "access")
    }

    // 日志文本变化时重新解析。只取尾部若干行，避免一次渲染上千条。
    LaunchedEffect(nvm.siteLog) {
        if (nvm.siteLog.isBlank()) {
            entries = emptyList(); parseNote = "日志为空"; picked = 0
            return@LaunchedEffect
        }
        val parsed = cwParseAccessLog(nvm.siteLog)
        entries = parsed.first
        parseNote = parsed.second
        picked = parsed.first.count { it.picked }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = Space.lg
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                WafCard {
                    SectionHeader("选择来源站点", subtitle = "日志与域名都取自该站点")
                    Spacer(Modifier.height(Space.sm))
                    if (siteOptions.isEmpty()) {
                        Text(
                            "没有可用站点，请先在「防护」里添加网站",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DropdownField(
                            value = siteName,
                            onValueChange = { label ->
                                siteName = label
                                siteId = siteOptions.firstOrNull { it.first == label }?.second.orEmpty()
                                reload()
                            },
                            label = "防护网站",
                            options = siteOptions
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("读取访问日志", enabled = siteId.isNotBlank()) { reload() }
                        SmallButton("站点日志页", enabled = siteId.isNotBlank()) {
                            nvm.getSiteLog(siteId, "access")
                        }
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader("解析结果", subtitle = "已解析 ${entries.size} 条 · 已选 $picked 条")
                    Spacer(Modifier.height(Space.sm))
                    if (!parseNote.isBlank()) {
                        HintText(parseNote)
                        Spacer(Modifier.height(Space.sm))
                    }
                    if (entries.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SmallButton("全选前 10 条") {
                                entries = entries.mapIndexed { i, e ->
                                    if (i < 10) e.copy(picked = true) else e.copy(picked = false)
                                }
                                picked = entries.count { it.picked }
                            }
                            SmallButton("清空选择") {
                                entries = entries.map { it.copy(picked = false) }
                                picked = 0
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    WafCard {
                        EmptyState(
                            icon = Icons.Filled.ListAlt,
                            title = "还没有可转换的日志",
                            description = "读取站点访问日志后，这里会列出可勾选的请求"
                        )
                    }
                }
            } else {
                item {
                    WafCard(contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
                        entries.forEachIndexed { idx, e ->
                            if (idx > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        entries = entries.mapIndexed { i, x ->
                                            if (i == idx) x.copy(picked = !x.picked) else x
                                        }
                                        picked = entries.count { it.picked }
                                    }
                                    .padding(vertical = Space.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = e.picked,
                                    onCheckedChange = { on ->
                                        entries = entries.mapIndexed { i, x ->
                                            if (i == idx) x.copy(picked = on) else x
                                        }
                                        picked = entries.count { it.picked }
                                    }
                                )
                                Spacer(Modifier.width(Space.xs))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.uri,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${e.method} · ${e.time}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                WafCard {
                    HintText(
                        "服务端只取 URI 的 path 部分，query 会被丢弃并单独转成一条参数名条件。" +
                            "每条记录都会单独写库并热加载一次配置，勾选过多会让站点短暂不可用。",
                        danger = picked > 10
                    )
                    Spacer(Modifier.height(Space.md))
                    PrimaryButton(
                        text = if (picked == 0) "请先勾选记录" else "把 $picked 条转成放行规则",
                        enabled = picked > 0,
                        loading = vm.saving,
                        onClick = {
                            val data = entries.filter { it.picked }.map {
                                mapOf(
                                    "server_name" to siteName,
                                    "uri" to it.uri,
                                    "method" to it.method
                                )
                            }
                            askConfirm(
                                CwConfirm(
                                    title = "批量转换访问日志",
                                    message = vm.blockLogWarning(data.size),
                                    confirmText = "确认转换",
                                    onConfirm = {
                                        vm.createWithBlockLog(data) { msg, err ->
                                            if (err != null) {
                                                vm.formError = err
                                            } else {
                                                entries = entries.map { it.copy(picked = false) }
                                                vm.batchLogMessage = msg
                                            }
                                        }
                                    }
                                )
                            )
                        }
                    )
                    if (!vm.batchLogMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(Space.sm))
                        HintText(vm.batchLogMessage!!)
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// 五、Tab 3 · 导入导出
// ──────────────────────────────────────────────────────────────────────────

@Composable
private fun CwTransferTab(
    vm: CustomizeViewModel,
    onPickFile: () -> Unit,
    onExported: (WafRepositoryAlias) -> Unit,
    onMessage: (String?) -> Unit,
    onShare: (CwExportInfo) -> Unit,
    onCloseResult: () -> Unit
) {
    var exportInfo by remember { mutableStateOf<CwExportInfo?>(null) }
    var exampleInput by remember { mutableStateOf<List<CwExampleOption>>(emptyList()) }
    var exampleOut by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.lg, end = Space.lg,
            top = Space.md, bottom = Space.lg
        ),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            WafCard {
                SectionHeader("导出规则", subtitle = "导出为 JSON 文件，可分享或备份")
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "导出的是服务端当前保存的全部规则。文件名由服务端下发的 Content-Disposition 决定，" +
                        "导入时会自动追加 __FROM_IMPORT 后缀，不会覆盖已有规则。"
                )
                Spacer(Modifier.height(Space.md))
                PrimaryButton(
                    text = "导出到本地",
                    loading = vm.transferring,
                    onClick = { vm.export { f, err -> if (f != null) onExported(f) else onMessage(err) } }
                )
                if (exportInfo != null) {
                    Spacer(Modifier.height(Space.sm))
                    WafCard(contentPadding = PaddingValues(Space.md)) {
                        DataRow("文件名", exportInfo!!.fileName)
                        DataRow("大小", formatBytes(exportInfo!!.size))
                        DataRowDivider(
                            "保存位置",
                            "应用缓存目录，无需存储权限；清理缓存会一并删除"
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("分享文件") { onShare(exportInfo!!) }
                        SmallButton("关闭") {
                            exportInfo = null
                            onCloseResult()
                        }
                    }
                }
            }
        }

        item {
            WafCard {
                SectionHeader("导入规则", subtitle = "选择 .json 文件批量创建")
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "服务端会校验文件名后缀必须是 .json，且每条规则的 id 必须非 0，" +
                        "否则整批回滚。导入不会覆盖现有规则。"
                )
                Spacer(Modifier.height(Space.md))
                PrimaryButton(
                    text = "选择 JSON 文件",
                    enabled = !vm.transferring,
                    onClick = onPickFile
                )
            }
        }

        item {
            WafCard {
                SectionHeader("生成示例规则", subtitle = "按条件生成可导入的规则模板")
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "服务端在内存里按你给的条件造规则，不读写数据库。" +
                        "生成结果可以保存成文件后再导入，方便快速铺开同类规则。"
                )
                Spacer(Modifier.height(Space.md))
                CwExampleBuilder(
                    options = exampleInput,
                    onOptionsChange = { exampleInput = it },
                    onGenerate = { opts ->
                        vm.exportExample(opts) { res, err ->
                            exampleOut = if (res != null) cwPrettyEntryJson(res) else (err ?: "")
                        }
                    }
                )
                if (exampleOut.isNotBlank()) {
                    Spacer(Modifier.height(Space.md))
                    SectionHeader("生成结果", subtitle = "保存为 .json 后可在上面导入")
                    Spacer(Modifier.height(Space.sm))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(Space.sm)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            exampleOut,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            if (!vm.transferError.isNullOrBlank()) {
                HintText(vm.transferError!!, danger = true)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// 六、规则编辑器
// ──────────────────────────────────────────────────────────────────────────

/** 编辑器状态：区分新建与编辑，编辑时锁定模式。 */
private class CwEditorState(
    val draft: CustomizeViewModel.RuleDraft,
    val help: CustomizeViewModel.ConfigHelp,
    val isNew: Boolean
) {
    companion object {
        fun newRule(help: CustomizeViewModel.ConfigHelp) = CwEditorState(
            draft = CustomizeViewModel.RuleDraft(),
            help = help,
            isNew = true
        )

        fun editRule(
            draft: CustomizeViewModel.RuleDraft,
            help: CustomizeViewModel.ConfigHelp
        ) = CwEditorState(draft, help, false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CwRuleEditor(
    state: CwEditorState,
    vm: CustomizeViewModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit
) {
    var draft by remember { mutableStateOf(state.draft) }
    var help by remember { mutableStateOf(state.help) }
    var exprText by remember { mutableStateOf("") }
    var showExpr by remember { mutableStateOf(false) }
    var siteSheet by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val mode = cwModeOf(draft.src)
    val actionMeta = remember(help.actions, draft.actionType) {
        help.actions.firstOrNull { it.s("type") == draft.actionType }
    }
    val responses = cwActionResponses(actionMeta)
    val hasResponse = actionMeta?.get("has_response") == true

    Dialog(
        onDismissRequest = { if (!vm.saving) onClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    if (state.isNew) "新建自定义规则" else "编辑自定义规则",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    mode.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { if (!vm.saving) onClose() }, enabled = !vm.saving) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                            }
                        },
                        actions = {
                            TextButton(
                                enabled = !vm.saving,
                                onClick = {
                                    val err = draft.validate()
                                    if (err != null) {
                                        localError = err
                                    } else {
                                        localError = null
                                        val done: (String?) -> Unit = { e ->
                                            if (e == null) onSaved("规则已保存") else localError = e
                                        }
                                        if (state.isNew) vm.create(draft, done)
                                        else vm.update(draft, done)
                                    }
                                }
                            ) {
                                if (vm.saving) {
                                    CircularProgressIndicator(
                                        Modifier.size(15.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("保存")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = Space.lg, end = Space.lg,
                        top = Space.md, bottom = Space.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    // ── 基础信息 ──
                    item {
                        WafCard {
                            SectionHeader("基础信息", subtitle = "名称必填，模式决定可选动作")
                            Spacer(Modifier.height(Space.sm))
                            if (state.isNew) {
                                CwSegmentedMode(
                                    current = mode,
                                    onSelect = { m ->
                                        draft = draft.copy(src = m.src)
                                        vm.loadConfigHelp(m.src) { loaded -> help = loaded }
                                    }
                                )
                                Spacer(Modifier.height(Space.sm))
                                Text(
                                    mode.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                HintText("模式在创建后不可更改：服务端编辑时会丢弃 src 字段。")
                                Spacer(Modifier.height(Space.sm))
                                Text(
                                    "${mode.label} · ${mode.desc}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(Modifier.height(Space.md))
                            LabeledField(
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                                label = "规则名称",
                                hint = "必填"
                            )
                            Spacer(Modifier.height(Space.sm))
                            LabeledField(
                                value = draft.priority.toString(),
                                onValueChange = {
                                    draft = draft.copy(
                                        priority = it.toIntOrNull() ?: draft.priority
                                    )
                                },
                                label = "优先级",
                                hint = "数值越大越先匹配",
                                keyboardType = KeyboardType.Number
                            )
                            Spacer(Modifier.height(Space.xs))
                            SwitchRow(
                                title = "启用规则",
                                description = "停用后规则保留但不参与匹配",
                                checked = draft.status == 1,
                                onChange = { draft = draft.copy(status = if (it) 1 else 0) }
                            )
                        }
                    }

                    // ── 生效范围 ──
                    item {
                        WafCard {
                            SectionHeader("生效范围", subtitle = "全局规则优先于站点规则")
                            Spacer(Modifier.height(Space.xs))
                            SwitchRow(
                                title = "对全部网站生效",
                                description = "开启后下方站点选择不再生效",
                                checked = draft.isGlobal == 1,
                                onChange = {
                                    draft = draft.copy(
                                        isGlobal = if (it) 1 else 0,
                                        servers = if (it) emptyList() else draft.servers
                                    )
                                }
                            )
                            if (draft.isGlobal == 0) {
                                Spacer(Modifier.height(Space.sm))
                                CwSitePicker(
                                    sitemap = help.sitemap,
                                    selected = draft.servers,
                                    onToggle = { name ->
                                        draft = draft.copy(
                                            servers = if (name in draft.servers) {
                                                draft.servers - name
                                            } else {
                                                draft.servers + name
                                            }
                                        )
                                    }
                                )
                                if (draft.servers.isEmpty()) {
                                    Spacer(Modifier.height(Space.sm))
                                    HintText("未选择站点时规则不会生效，请至少选择一个。", danger = true)
                                }
                            }
                            Spacer(Modifier.height(Space.sm))
                            DataRowDivider("执行阶段", "固定为 access（访问阶段），后端只认这一个值")
                        }
                    }

                    // ── 命中动作 ──
                    item {
                        WafCard {
                            SectionHeader(
                                "命中动作",
                                subtitle = "选项由服务端下发，不同模式可选项不同"
                            )
                            Spacer(Modifier.height(Space.sm))
                            if (vm.helpLoading) {
                                LoadingBar(true)
                            }
                            val actionOptions = help.actions.map {
                                it.s("text").ifBlank { it.s("type") } to it.s("type")
                            }
                            if (actionOptions.isEmpty()) {
                                HintText("没能取到动作选项，请检查模式或稍后重试。", danger = true)
                            } else {
                                DropdownField(
                                    value = draft.actionType,
                                    onValueChange = { t ->
                                        val meta = help.actions.firstOrNull { it.s("type") == t }
                                        val respList = cwActionResponses(meta)
                                        val cc = meta?.get("cc").asMap()
                                        val blockTime = meta?.i("block_time") ?: draft.blockTime
                                        draft = draft.copy(
                                            actionType = t,
                                            responseType = respList.firstOrNull()?.first
                                                ?: draft.responseType,
                                            responseBody = if (draft.src == 3) draft.responseBody else "",
                                            blockTime = blockTime,
                                            ccInterval = cc.i("interval").takeIf { it > 0 }
                                                ?: draft.ccInterval,
                                            ccThreshold = cc.i("threshold").takeIf { it > 0 }
                                                ?: draft.ccThreshold
                                        )
                                    },
                                    label = "动作",
                                    options = actionOptions
                                )
                            }

                            if (hasResponse && responses.isNotEmpty()) {
                                Spacer(Modifier.height(Space.md))
                                // 重定向模式下 response.type 是状态码，body 才是跳转地址
                                if (draft.src == 3) {
                                    DropdownField(
                                        value = draft.responseType,
                                        onValueChange = { draft = draft.copy(responseType = it) },
                                        label = "重定向状态码",
                                        options = responses
                                    )
                                } else {
                                    DropdownField(
                                        value = draft.responseType,
                                        onValueChange = { draft = draft.copy(responseType = it) },
                                        label = "响应方式",
                                        options = responses
                                    )
                                }
                            }

                            if (draft.src == 3) {
                                Spacer(Modifier.height(Space.md))
                                LabeledField(
                                    value = draft.responseBody,
                                    onValueChange = { draft = draft.copy(responseBody = it) },
                                    label = "跳转 URL",
                                    hint = "必须以 http:// 或 https:// 开头"
                                )
                            }

                            if (actionMeta?.i("block_time") != null && actionMeta.i("block_time") > 0) {
                                Spacer(Modifier.height(Space.md))
                                LabeledField(
                                    value = draft.blockTime.toString(),
                                    onValueChange = {
                                        draft = draft.copy(
                                            blockTime = it.toIntOrNull() ?: draft.blockTime
                                        )
                                    },
                                    label = "封禁时长（秒）",
                                    hint = "服务端建议值 ${actionMeta.i("block_time")}",
                                    keyboardType = KeyboardType.Number
                                )
                            }

                            if (draft.src == 2) {
                                Spacer(Modifier.height(Space.md))
                                SectionHeader("CC 阈值", subtitle = "时间窗内超过阈值即触发")
                                Spacer(Modifier.height(Space.sm))
                                GridRow {
                                    GridCell {
                                        LabeledField(
                                            value = draft.ccInterval.toString(),
                                            onValueChange = {
                                                draft = draft.copy(
                                                    ccInterval = it.toIntOrNull() ?: draft.ccInterval
                                                )
                                            },
                                            label = "统计窗口（秒）",
                                            keyboardType = KeyboardType.Number
                                        )
                                    }
                                    GridCell {
                                        LabeledField(
                                            value = draft.ccThreshold.toString(),
                                            onValueChange = {
                                                draft = draft.copy(
                                                    ccThreshold = it.toIntOrNull() ?: draft.ccThreshold
                                                )
                                            },
                                            label = "请求阈值（次）",
                                            keyboardType = KeyboardType.Number
                                        )
                                    }
                                }
                            }

                            if (draft.actionType == "record" || draft.actionType == "allow") {
                                Spacer(Modifier.height(Space.md))
                                HintText("「仅记录」只写日志不拦截，上线前建议先用它验证匹配条件是否正确。")
                            }
                        }
                    }

                    // ── 匹配条件 ──
                    item {
                        WafCard {
                            SectionHeader(
                                "匹配条件",
                                subtitle = "${draft.conditions.size} 条 · 顶层逻辑 ${draft.logic}"
                            )
                            Spacer(Modifier.height(Space.sm))
                            CwLogicRow(
                                logic = draft.logic,
                                onChange = { draft = draft.copy(logic = it) }
                            )
                            Spacer(Modifier.height(Space.md))

                            if (help.options.isEmpty()) {
                                HintText("没能取到条件选项，请检查模式或稍后重试。", danger = true)
                            }

                            draft.conditions.forEachIndexed { idx, cond ->
                                CwConditionEditor(
                                    index = idx,
                                    condition = cond,
                                    help = help,
                                    onChange = { updated ->
                                        draft = draft.copy(
                                            conditions = draft.conditions.toMutableList()
                                                .also { it[idx] = updated }
                                        )
                                    },
                                    onDelete = {
                                        draft = draft.copy(
                                            conditions = draft.conditions.filterIndexed { i, _ -> i != idx }
                                        )
                                    }
                                )
                                if (idx < draft.conditions.lastIndex) {
                                    Spacer(Modifier.height(Space.sm))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(Modifier.height(Space.sm))
                                }
                            }

                            Spacer(Modifier.height(Space.md))
                            SmallButton(
                                "添加条件",
                                enabled = help.options.isNotEmpty()
                            ) {
                                val first = help.options.firstOrNull() ?: return@SmallButton
                                val op = cwStringList(first["operators"]).firstOrNull().orEmpty()
                                draft = draft.copy(
                                    conditions = draft.conditions + CustomizeViewModel.Condition(
                                        type = first.s("type"),
                                        operator = op,
                                        rightFactor = ""
                                    )
                                )
                            }

                            if (draft.conditions.isNotEmpty()) {
                                Spacer(Modifier.height(Space.sm))
                                Text(
                                    "条件之间用「${if (draft.logic == "and") "并且" else "或者"}」连接；" +
                                        "多个取值用英文逗号分隔，例如 1.1.1.1,2.2.2.2",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ── 表达式工具 ──
                    item {
                        WafCard {
                            SectionHeader(
                                "表达式工具",
                                subtitle = "服务端解析与反向还原，调试规则很方便"
                            )
                            Spacer(Modifier.height(Space.sm))
                            if (!state.isNew) {
                                SmallButton("把当前规则转成表达式") {
                                    vm.toExpression(draft.id) { text, err ->
                                        if (text != null) {
                                            exprText = text
                                            showExpr = true
                                        } else {
                                            localError = err
                                        }
                                    }
                                }
                                Spacer(Modifier.height(Space.sm))
                            }
                            LabeledField(
                                value = exprText,
                                onValueChange = { exprText = it },
                                label = "表达式",
                                hint = "如 user-agent like {sqlmap}",
                                singleLine = false
                            )
                            Spacer(Modifier.height(Space.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                SmallButton("解析并替换条件") {
                                    vm.parseExpression(exprText) { node ->
                                        val parsed = cwConditionsOf(node)
                                        if (parsed.isEmpty()) {
                                            localError = vm.exprError ?: "表达式没有解析出任何条件"
                                        } else {
                                            draft = draft.copy(conditions = parsed)
                                            localError = null
                                        }
                                    }
                                }
                                if (exprText.isNotBlank()) {
                                    SmallButton("清空") { exprText = "" }
                                }
                            }
                            Spacer(Modifier.height(Space.sm))
                            HintText(
                                "语法：条件类型.左因子 运算符 右值，多条用 or / and 连接，" +
                                    "集合写成 {a,b}。解析会整组替换下方条件，请先保存当前规则。"
                            )
                        }
                    }

                    item {
                        val err = localError ?: vm.formError
                        if (!err.isNullOrBlank()) {
                            HintText(err, danger = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CwSegmentedMode(
    current: CwMode,
    onSelect: (CwMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        CwMode.entries.forEach { m ->
            val on = m == current
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (on) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(m) }
            ) {
                Column(
                    Modifier.padding(vertical = Space.sm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        m.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "src=${m.src}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun CwSitePicker(
    sitemap: Map<String, String>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    if (sitemap.isEmpty()) {
        HintText("服务端没有返回站点列表，无法在这里选择生效范围。", danger = true)
        return
    }
    Column {
        Text(
            "选择站点（已选 ${selected.size} 个）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.xs))
        sitemap.entries.sortedBy { it.key }.forEach { (name, domain) ->
            val on = name in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(name) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = on, onCheckedChange = { onToggle(name) })
                Spacer(Modifier.width(Space.xs))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CwLogicRow(logic: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "条件关系",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(Space.sm))
        listOf("or" to "任一满足", "and" to "全部满足").forEach { (v, label) ->
            val on = logic == v
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (on) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .padding(start = Space.xs)
                    .clickable { onChange(v) }
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CwConditionEditor(
    index: Int,
    condition: CustomizeViewModel.Condition,
    help: CustomizeViewModel.ConfigHelp,
    onChange: (CustomizeViewModel.Condition) -> Unit,
    onDelete: () -> Unit
) {
    val meta = help.options.firstOrNull { it.s("type") == condition.type }
    val leftEnabled = meta?.get("left_factor_enabled") == true
    val operators = cwStringList(meta?.get("operators"))
    val rightWidget = meta?.get("right_widget").asMap()
    val rightIsSelect = rightWidget.s("type") == "select"
    val rightPlaceholder = rightWidget.s("placeholder")
    val rightHint = rightWidget.s("hint")
    val setLike = condition.operator == "in" || condition.operator == "not_in"

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "条件 ${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.DeleteOutline, "删除条件",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        DropdownField(
            value = condition.type,
            onValueChange = { t ->
                val m = help.options.firstOrNull { it.s("type") == t }
                val ops = cwStringList(m?.get("operators"))
                onChange(
                    condition.copy(
                        type = t,
                        operator = ops.firstOrNull().orEmpty(),
                        leftFactor = "",
                        rightFactor = ""
                    )
                )
            },
            label = "匹配对象",
            options = help.options.map { it.s("text").ifBlank { it.s("type") } to it.s("type") }
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = condition.operator,
            onValueChange = { onChange(condition.copy(operator = it)) },
            label = "运算符",
            options = operators.map { op ->
                val text = help.operators[op]?.s("text").orEmpty().ifBlank { op }
                "$text（$op）" to op
            }
        )
        if (leftEnabled) {
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = condition.leftFactor,
                onValueChange = { onChange(condition.copy(leftFactor = it)) },
                label = "左因子（名称）",
                hint = meta?.get("left_widget").asMap().s("placeholder").ifBlank { "如 username" }
            )
        }
        Spacer(Modifier.height(Space.sm))
        if (rightIsSelect) {
            val opts = cwSelectOptions(rightWidget["value"])
            DropdownField(
                value = condition.rightFactor,
                onValueChange = { onChange(condition.copy(rightFactor = it)) },
                label = rightPlaceholder.ifBlank { "选择取值" },
                options = opts
            )
        } else {
            LabeledField(
                value = condition.rightFactor,
                onValueChange = { onChange(condition.copy(rightFactor = it)) },
                label = rightPlaceholder.ifBlank { "匹配值" },
                hint = if (setLike) {
                    "多个取值用英文逗号分隔" + if (rightHint.isNotBlank()) "，${rightHint}" else ""
                } else {
                    rightHint
                }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// 七、示例规则生成器
// ──────────────────────────────────────────────────────────────────────────

/** 一条待生成的示例条件。 */
private class CwExampleOption(
    val type: String,
    val operator: String,
    val leftFactor: String,
    val rightFactor: String
) {
    fun toPayload(): Map<String, Any?> = mapOf(
        "type" to type,
        "text" to type,
        "operators" to listOf(operator),
        "examples" to mapOf(
            operator to mapOf(
                "left_factor" to leftFactor,
                "right_factor" to rightFactor
            )
        )
    )
}

@Composable
private fun CwExampleBuilder(
    options: List<CwExampleOption>,
    onOptionsChange: (List<CwExampleOption>) -> Unit,
    onGenerate: (List<Map<String, Any?>>) -> Unit
) {
    var type by remember { mutableStateOf("uri") }
    var operator by remember { mutableStateOf("eq") }
    var left by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }

    // 选项同样走服务端，避免把中英文差异写死。
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: CustomizeViewModel = viewModel(factory = WafViewModelFactory(repo))
    LaunchedEffect(Unit) { if (vm.help.options.isEmpty()) vm.loadConfigHelp(1) }

    val typeOptions = vm.help.options.map { it.s("text").ifBlank { it.s("type") } to it.s("type") }
    val opOptions = vm.help.options.firstOrNull { it.s("type") == type }
        ?.let { cwStringList(it["operators"]) }
        .orEmpty()
    val leftEnabled = vm.help.options.firstOrNull { it.s("type") == type }
        ?.get("left_factor_enabled") == true

    Column {
        DropdownField(
            value = type,
            onValueChange = {
                type = it
                val ops = cwStringList(
                    vm.help.options.firstOrNull { o -> o.s("type") == it }?.get("operators")
                )
                operator = ops.firstOrNull().orEmpty()
            },
            label = "匹配对象",
            options = typeOptions
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = operator,
            onValueChange = { operator = it },
            label = "运算符",
            options = opOptions.map {
                val t = vm.help.operators[it]?.s("text").orEmpty().ifBlank { it }
                "$t（$it）" to it
            }
        )
        if (leftEnabled) {
            Spacer(Modifier.height(Space.sm))
            LabeledField(value = left, onValueChange = { left = it }, label = "左因子")
        }
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = right,
            onValueChange = { right = it },
            label = "匹配值",
            hint = "多个取值用英文逗号分隔"
        )
        Spacer(Modifier.height(Space.sm))
        if (options.isNotEmpty()) {
            options.forEachIndexed { i, o ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${o.type} ${o.operator} ${o.rightFactor}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = {
                            onOptionsChange(options.filterIndexed { idx, _ -> idx != i })
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close, "移除",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.xs))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            SmallButton("加入清单", enabled = right.isNotBlank()) {
                onOptionsChange(
                    options + CwExampleOption(type, operator, left, right)
                )
                right = ""
            }
            SmallButton(
                "生成规则",
                tone = ChipTone.INFO,
                enabled = options.isNotEmpty()
            ) {
                onGenerate(options.map { it.toPayload() })
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// 八、取值与文件辅助
// ──────────────────────────────────────────────────────────────────────────

/** 顶层私有别名，避免在本文件里反复写全限定名。 */
private typealias WafRepositoryAlias = com.bt.waf.data.repo.WafRepository.DownloadedFile

/** 把可能是数组、逗号串或空值的字段统一成字符串列表。 */
private fun cwStringList(v: Any?): List<String> = when (v) {
    null -> emptyList()
    is List<*> -> v.mapNotNull { it?.asString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
    is String -> v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    else -> listOf(v.asString()).filter { it.isNotBlank() }
}

/** 取某个 action 的响应方式下拉项。重定向模式下 type 是状态码。 */
private fun cwActionResponses(action: Map<String, Any?>?): List<Pair<String, String>> {
    if (action == null) return emptyList()
    return cwSelectOptions(action["response"])
}

/** 把服务端下发的 key/label 数组转成下拉项。 */
private fun cwSelectOptions(v: Any?): List<Pair<String, String>> {
    val list = (v as? List<*>) ?: return emptyList()
    return list.mapNotNull { item ->
        val m = item.asMap()
        val key = m.s("key")
        if (key.isBlank()) null else (m.s("label").ifBlank { key } to key)
    }
}

private fun cwActionLabel(type: String): String = when (type) {
    "allow" -> "放行"
    "deny" -> "拦截"
    "validate" -> "人机验证"
    "record" -> "仅记录"
    "redirect" -> "重定向"
    else -> type.ifBlank { "未知动作" }
}

/**
 * 把列表接口返回的单条规则还原成编辑草稿。
 *
 * 注意 action 与 root 在库里是 JSON 字符串，列表接口已经反序列化成对象，
 * 这里只需要把嵌套的 block 树拍平成条件列表。
 */
private fun cwDraftOf(item: Map<String, Any?>): CustomizeViewModel.RuleDraft {
    val action = item["action"].asMap()
    val response = action["response"].asMap()
    val cc = action["cc"].asMap()
    val root = item["root"].asMap()
    val block = root["children"].asList().firstOrNull { it.s("type") == "block" }
    val logic = block?.s("logic")?.ifBlank { null } ?: root.s("logic").ifBlank { "or" }

    return CustomizeViewModel.RuleDraft(
        id = item.i("id"),
        name = item.s("name"),
        servers = cwStringList(item["servers"]),
        isGlobal = item.i("is_global"),
        src = item.i("src").takeIf { it > 0 } ?: 1,
        status = item.i("status"),
        priority = item.i("priority"),
        executePhase = item.s("execute_phase").ifBlank { EXEC_PHASE_ACCESS },
        actionType = action.s("type").ifBlank { "deny" },
        responseType = response.s("type"),
        responseBody = response.s("body"),
        blockTime = action.i("block_time"),
        ccInterval = cc.i("interval"),
        ccThreshold = cc.i("threshold"),
        logic = logic,
        conditions = cwConditionsOf(root)
    )
}

/**
 * 递归把 Node 树里的 option 节点拍平成条件列表。
 * parse_expression 的返回也是这个结构，所以表达式导入与列表回填共用这一份解析。
 */
private fun cwConditionsOf(node: Map<String, Any?>): List<CustomizeViewModel.Condition> {
    val out = mutableListOf<CustomizeViewModel.Condition>()
    fun walk(n: Map<String, Any?>) {
        when (n.s("type")) {
            "option" -> {
                val o = n["option"].asMap()
                out += CustomizeViewModel.Condition(
                    type = o.s("type"),
                    operator = o.s("operator"),
                    rightFactor = o.s("right_factor"),
                    leftFactor = o.s("left_factor")
                )
            }

            "block" -> n["children"].asList().forEach { walk(it) }
        }
    }
    //顶层自身可能就是 option（单条件规则）
    if (node.s("type") == "option") walk(node)
    node["children"].asList().forEach { walk(it) }
    return out.filter { it.type.isNotBlank() }
}

/**
 * 解析 nginx access log 尾部文本。
 * 只取能识别出请求行的前 N 条，返回结果与一句说明。
 */
private fun cwParseAccessLog(text: String): Pair<List<CwLogEntry>, String> {
    val out = mutableListOf<CwLogEntry>()
    var skipped = 0
    text.lineSequence().forEach { line ->
        if (out.size >= LOG_PARSE_LIMIT) return@forEach
        val m = CW_REQUEST_REGEX.find(line)
        if (m == null) {
            if (line.isNotBlank()) skipped++
            return@forEach
        }
        val time = CW_TIME_REGEX.find(line)?.groupValues?.get(1).orEmpty()
        out += CwLogEntry(
            method = m.groupValues[1],
            uri = m.groupValues[2],
            time = time
        )
    }
    val note = buildString {
        append("已解析 ${out.size} 条请求")
        if (skipped > 0) append("，跳过 $skipped 行无法识别的内容")
        if (out.size >= LOG_PARSE_LIMIT) append("；仅保留前 $LOG_PARSE_LIMIT 条")
    }
    return out to note
}

/** export_example 的返回是 rules 字典 + allsite 列表，这里转成可导入的 JSON 数组文本。 */
private fun cwPrettyEntryJson(res: Map<String, Any?>): String {
    val rules = res["rules"].asMap()
    val arr = rules.entries
        .sortedBy { it.key.toIntOrNull() ?: 0 }
        .map { it.value.asMap() }
    return cwToJson(arr)
}

/**
 * 轻量 JSON 序列化。
 * 服务端返回结构固定（都是标量、字符串数组、嵌套对象），
 * 这里手写序列化避免为一个示例预览引入额外依赖。
 */
private fun cwToJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Number, is Boolean -> value.toString()
    is List<*> -> value.joinToString(",", "[", "]") { cwToJson(it) }
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"" + k.toString().replace("\"", "\\\"") + "\":" + cwToJson(v)
    }

    else -> "\"" + value.toString().replace("\"", "\\\"") + "\""
}

/** 把用户选中的 uri 复制到缓存目录，文件名必须以 .json 结尾才能通过服务端校验。 */
private fun cwCopyToTemp(ctx: android.content.Context, uri: android.net.Uri): String? {
    return try {
        val dir = File(ctx.cacheDir, "waf_transfer").apply { mkdirs() }
        val target = File(dir, "import_customize.json")
        val input = ctx.contentResolver.openInputStream(uri)
            ?: return "无法读取所选文件"
        input.use { target.writeBytes(it.readBytes()) }
        null
    } catch (e: Exception) {
        e.message ?: "复制文件失败"
    }
}

private fun cwImportFile(ctx: android.content.Context): File? =
    File(File(ctx.cacheDir, "waf_transfer"), "import_customize.json").takeIf { it.exists() }

/** 用 FileProvider 把导出的文件分享出去。cache 目录没有 provider 是分享不了的。 */
private fun cwShareFile(ctx: android.content.Context, file: File): Intent? {
    if (!file.exists()) return null
    val uri = runCatching {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }.getOrNull() ?: return null
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "分享规则文件")
}
