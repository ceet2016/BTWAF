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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asString
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.SitesViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private enum class SiteTab(val label: String) { INFO("基本信息"), LOG("访问日志"), SSL("证书") }

/**
 * 防护网站管理。
 * 列表用 WafCard + 状态标签 + 菜单操作；详情改为 ModalBottomSheet，
 * 比 AlertDialog 塞三个 Tab 更符合移动端交互习惯。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: SitesViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showCreate by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var deleteTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var detailTab by remember { mutableStateOf(SiteTab.INFO) }
    var siteLogs by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var siteSsl by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var tabLoading by remember { mutableStateOf(false) }
    var createErr by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val cf = remember {
        mutableStateMapOf("site_name" to "", "port" to "80", "server_ip" to "")
    }

    // 站点列表带防护开关，开关状态随时可能被人改动，页面上看到的
    // 开/关必须是真的当前状态。
    //
    // `enabled = !showCreate` 是关键：新增站点对话框打开时暂停轮询。
    // 否则用户正往「站点名/端口/源站 IP」里填字，列表那边一次自动
    // 刷新就把整页重排，正在点的行可能跳位——虽然表单状态在 cf 里
    // 不会被冲掉，但交互上很莫名其妙。
    AutoRefresh(
        intervalMillis = 8_000L,
        isLoading = vm.loading,
        enabled = !showCreate
    ) { vm.load() }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    cf["site_name"] = ""; cf["port"] = "80"; cf["server_ip"] = ""
                    showCreate = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Filled.Add, contentDescription = "新增站点") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LoadingBar(vm.loading)
            ErrorBanner(vm.error) { vm.load() }

            when {
                vm.loading && vm.sites.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }

                vm.sites.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Language,
                    title = "还没有防护网站",
                    description = "添加一个域名并指定源站地址，WAF 就会开始拦截攻击",
                    actionText = "新增站点",
                    onAction = { showCreate = true }
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = Space.lg, end = Space.lg,
                        top = Space.md, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    item {
                        SectionHeader(
                            "防护网站",
                            subtitle = "${vm.sites.size} 个站点已接入 WAF"
                        )
                    }
                    items(vm.sites) { site ->
                        SiteCard(
                            site = site,
                            onToggle = { open ->
                                vm.setStatus(site, open) { e ->
                                    if (e != null) vm.error = e else toast(if (open) "已启用防护" else "已停用防护")
                                }
                            },
                            onDetail = {
                                detail = site
                                detailTab = SiteTab.INFO
                                siteLogs = emptyList()
                                siteSsl = emptyList()
                            },
                            onDelete = { deleteTarget = site }
                        )
                    }
                }
            }
        }
    }

    // ── 新增站点 ──
    if (showCreate) {
        FormDialog(
            title = "新增防护网站",
            icon = Icons.Filled.AddLink,
            confirmText = "创建",
            busy = creating,
            error = createErr,
            onDismiss = { showCreate = false; createErr = null },
            onConfirm = {
                val name = cf["site_name"].orEmpty()
                if (name.isBlank()) {
                    createErr = "请填写站点域名"
                } else {
                    creating = true
                    createErr = null
                    vm.create(name, cf["port"].orEmpty(), cf["server_ip"].orEmpty()) { err ->
                        creating = false
                        if (err == null) { showCreate = false; toast("站点已创建") } else createErr = err
                    }
                }
                null
            }
        ) {
            HintText("填写域名与源站地址，WAF 会在流量回源前先做一次安全过滤。")
            Spacer(Modifier.height(Space.md))
            LabeledField(
                value = cf["site_name"].orEmpty(),
                onValueChange = { cf["site_name"] = it; createErr = null },
                label = "站点域名",
                hint = "example.com"
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = cf["port"].orEmpty(),
                onValueChange = { cf["port"] = it },
                label = "源站端口",
                hint = "80",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = cf["server_ip"].orEmpty(),
                onValueChange = { cf["server_ip"] = it },
                label = "源站地址",
                hint = "127.0.0.1 或 10.0.0.5:8080"
            )
        }
    }

    // ── 删除确认 ──
    deleteTarget?.let { site ->
        val name = pickString(site, "site_name", "name", "domain", "id").ifBlank { "该站点" }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除防护网站") },
            text = {
                Text(
                    "确认删除「$name」吗？该站点的 WAF 防护配置与访问日志将一并移除，且无法恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteTarget
                    deleteTarget = null
                    detail = null
                    vm.delete(id!!) { e ->
                        if (e != null) vm.error = e else toast("站点已删除")
                    }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // ── 站点详情（底部抽屉）──
    detail?.let { site ->
        val siteId = vm.siteId(site)
        ModalBottomSheet(
            onDismissRequest = { detail = null },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
                Text(
                    pickString(site, "site_name", "name", "domain", "id").ifBlank { "站点详情" },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (siteId.isNotBlank()) {
                    Text(
                        "site_id: $siteId",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(Space.md))

                // 统一走 WafTabRow：edgePadding 与卡片内容左边距对齐，
                // 选中项自动滚入视野（4 个标签时最后一个原本会被裁掉一半）。
                // 懒加载逻辑保留在 onSelect 里。
                WafTabRow(
                    labels = SiteTab.entries.map { it.label },
                    selectedIndex = SiteTab.entries.indexOf(detailTab),
                    onSelect = { idx ->
                        val t = SiteTab.entries[idx]
                        detailTab = t
                        if (t == SiteTab.LOG && siteLogs.isEmpty()) {
                            tabLoading = true
                            vm.getLog(siteId) { l, e ->
                                siteLogs = l ?: emptyList()
                                tabLoading = false
                                if (e != null) vm.error = e
                            }
                        }
                        if (t == SiteTab.SSL && siteSsl.isEmpty()) {
                            tabLoading = true
                            vm.getSsl { s, e ->
                                siteSsl = s ?: emptyList()
                                tabLoading = false
                                if (e != null) vm.error = e
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                )

                Spacer(Modifier.height(Space.md))
                LoadingBar(tabLoading)

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (detailTab) {
                        SiteTab.INFO -> {
                            WafCard {
                                SectionHeader("站点信息", subtitle = "来自 wafmastersite/get_site_list")
                                Spacer(Modifier.height(Space.sm))
                                site.forEach { (k, v) ->
                                    DataRowDivider(humanKey(k), renderValue(v))
                                }
                            }
                        }

                        SiteTab.LOG -> {
                            if (siteLogs.isEmpty() && !tabLoading) {
                                EmptyState(
                                    icon = Icons.Filled.ReceiptLong,
                                    title = "暂无访问日志",
                                    description = "该站点还没有产生请求记录"
                                )
                            } else {
                                WafCard {
                                    SectionHeader(
                                        "访问日志",
                                        subtitle = "${siteLogs.size} 条",
                                        action = {
                                            if (siteLogs.isNotEmpty()) {
                                                SmallButton("清空", tone = ChipTone.DANGER) {
                                                    vm.clearLog(siteId) { e ->
                                                        if (e == null) { siteLogs = emptyList(); toast("日志已清空") }
                                                        else vm.error = e
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    Spacer(Modifier.height(Space.sm))
                                    siteLogs.forEach { log ->
                                        DataRowDivider(
                                            pickString(log, "time", "create_time", "date").ifBlank { "-" },
                                            pickString(log, "uri", "url", "request_uri", "rule", "content")
                                        )
                                    }
                                }
                            }
                        }

                        SiteTab.SSL -> {
                            if (siteSsl.isEmpty() && !tabLoading) {
                                EmptyState(
                                    icon = Icons.Filled.LockOpen,
                                    title = "证书库为空",
                                    description = "还没有任何 SSL 证书"
                                )
                            } else {
                                WafCard {
                                    SectionHeader("证书库", subtitle = "${siteSsl.size} 张证书")
                                    Spacer(Modifier.height(Space.sm))
                                    siteSsl.forEach { s ->
                                        DataRowDivider(
                                            pickString(s, "ssl_name", "name", "id"),
                                            listOf(
                                                pickString(s, "brand"),
                                                pickString(s, "not_after")
                                            ).filter { it.isNotBlank() }.joinToString(" · ")
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.lg))
                }
            }
        }
    }
}

/**
 * 站点卡片。
 *
 * 之前把「域名 + 状态标签 + site_id」三样东西硬塞在同一行，左边文字越长右边
 * 越挤，看起来就是「左重右轻」。现在改成统一骨架：
 *   左：图标底板（颜色即状态）
 *   中：域名做主标题，状态与端口降级成一行副信息
 *   右：开关 + 更多菜单
 * site_id 属于技术字段，移到详情里展示，列表不再出现。
 */
@Composable
private fun SiteCard(
    site: Map<String, Any?>,
    onToggle: (Boolean) -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val name = pickString(site, "site_name", "name", "domain", "id").ifBlank { "(未命名站点)" }
    val status = site["status"].asString()
    val on = status == "1" || status.equals("true", true)
    val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val port = site["port"].asString()
    val sub = listOf(if (on) "防护中" else "已停用", if (port.isNotBlank()) "端口 $port" else "")
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    var menu by remember { mutableStateOf(false) }

    WafCard(contentPadding = rowCardPadding) {
        ListRow(
            icon = if (on) Icons.Filled.Shield else Icons.Filled.ShieldMoon,
            title = name,
            subtitle = sub,
            iconTint = tint,
            subtitleColor = if (on) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onDetail,
            trailing = {
                Switch(checked = on, onCheckedChange = onToggle)
                Spacer(Modifier.width(Space.xs))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            "更多",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("查看详情") },
                            leadingIcon = { Icon(Icons.Filled.Info, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onDetail() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (on) "停用防护" else "启用防护") },
                            leadingIcon = {
                                Icon(
                                    if (on) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                                    null, Modifier.size(18.dp)
                                )
                            },
                            onClick = { menu = false; onToggle(!on) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete, null,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        )
    }
}

/** 把后端 snake_case 字段名转成可读标签。 */
private fun humanKey(k: String): String = when (k) {
    "site_name" -> "站点名称"
    "site_id" -> "站点 ID"
    "server_name" -> "Server Name"
    "is_cdn" -> "CDN 加速"
    "run_mode" -> "运行模式"
    "status" -> "状态"
    "port" -> "端口"
    "domain_list" -> "域名列表"
    "create_time" -> "创建时间"
    "is_https" -> "强制 HTTPS"
    else -> k
}

/** 列表/对象值统一渲染。 */
private fun renderValue(v: Any?): String = when (v) {
    null -> "-"
    is List<*> -> v.joinToString(", ") { it.toString() }.ifBlank { "-" }
    is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "-" }
    is Boolean -> if (v) "是" else "否"
    else -> v.toString()
}
