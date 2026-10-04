package com.bt.waf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.viewmodel.IpGroupViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

/**
 * IP 分组管理。
 * 分组本身不直接生效，而是作为黑白名单 / 人机验证规则的匹配目标
 * （后台里写作 `匹配ip组-[名称]`），因此这里提供组的增删改查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpGroupScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: IpGroupViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val form = remember { mutableStateMapOf("name" to "", "ips" to "") }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            editing = null
            formError = null
            scope.launch { snackbar.showSnackbar(it) }
            vm.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    form["name"] = ""; form["ips"] = ""
                    editing = ""
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Filled.Add, contentDescription = "新建分组") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(
                        "IP 分组",
                        subtitle = "${vm.groupCount} 个分组 · ${vm.ipCount} 条 IP",
                        modifier = Modifier.weight(1f)
                    )
                    SmallButton("刷新") { vm.load() }
                }
            }

            item {
                WafCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Info, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "分组可用于黑白名单与人机验证规则，在那些规则里以「匹配ip组-分组名」引用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                LoadingBar(vm.loading)
                ErrorBanner(vm.error) { vm.load() }
            }

            if (vm.groups.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.Filled.FolderOpen,
                        title = "还没有 IP 分组",
                        description = "创建一个分组，把常用的 IP 归到一起，方便在规则里复用",
                        actionText = "新建分组",
                        onAction = { form["name"] = ""; form["ips"] = ""; editing = "" }
                    )
                }
            }

            items(vm.sortedNames, key = { it }) { name ->
                val ips = vm.groups[name].orEmpty()
                val isOpen = expanded == name
                WafCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(
                                icon = Icons.Filled.Folder,
                                tint = MaterialTheme.colorScheme.primary,

                                size = 36.dp,
                                corner = 10.dp
                            )
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${ips.size} 个 IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = { expanded = if (isOpen) null else name }) {
                            Icon(
                                if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = "展开",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    if (isOpen) {
                        Spacer(Modifier.height(Space.sm))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(Space.sm))
                        ips.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth()) {
                                pair.forEach { ip ->
                                    Text(
                                        ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(Space.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton(
                            text = "编辑",
                            tone = ChipTone.INFO,
                            modifier = Modifier.weight(1f)
                        ) {
                            form["name"] = name
                            form["ips"] = ips.joinToString("\n")
                            editing = name
                        }
                        SmallButton(
                            text = "删除",
                            tone = ChipTone.DANGER,
                            modifier = Modifier.weight(1f)
                        ) { deleteTarget = name }
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }

    // 新建 / 编辑
    if (editing != null) {
        val isNew = editing!!.isEmpty()
        FormDialog(
            title = if (isNew) "新建 IP 分组" else "编辑分组",
            icon = if (isNew) Icons.Filled.CreateNewFolder else Icons.Filled.Edit,
            busy = vm.saving,
            error = formError,
            onDismiss = { editing = null; formError = null },
            onConfirm = {
                // 兼容三种分隔：换行、逗号、空格/分号/tab
                val ips = form["ips"].orEmpty()
                    .split('\n', ',', ' ', ';', '\t')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val n = form["name"].orEmpty().trim()
                val fail: (String?) -> Unit = { e -> formError = e }
                if (isNew) vm.create(n, ips, fail) else vm.update(n, ips, fail)
                null
            }
        ) {
            LabeledField(
                value = form["name"].orEmpty(),
                onValueChange = { form["name"] = it; formError = null },
                label = "分组名称",
                hint = "如 机房A",
                enabled = isNew
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = form["ips"].orEmpty(),
                onValueChange = { form["ips"] = it; formError = null },
                label = "IP 列表",
                hint = "多个 IP 用换行、逗号或空格分隔，支持 CIDR 网段",
                singleLine = false
            )
            Spacer(Modifier.height(Space.sm))
            HintText("保存后可在黑白名单 / 人机验证规则里以「匹配ip组-分组名」引用该分组。")
        }
    }

    // 删除确认
    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除 IP 分组") },
            text = {
                Text(
                    "确认删除「$name」吗？若该分组已被黑白名单或人机验证规则引用，删除会被服务端拒绝。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = deleteTarget
                    deleteTarget = null
                    expanded = null
                    vm.delete(n!!) { e ->
                        scope.launch { snackbar.showSnackbar(e ?: "分组已删除") }
                    }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
