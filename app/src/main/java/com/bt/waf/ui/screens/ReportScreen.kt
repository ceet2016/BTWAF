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
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.l
import com.bt.waf.util.s
import com.bt.waf.viewmodel.ReportViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private val TABS = listOf("概览", "攻击 IP", "攻击 URI", "规则命中", "命中开关")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: ReportViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) }
    var unblockTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var confirmUnblockAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── 时间范围 ──
            Column(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    divider = {}
                ) {
                    TABS.forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(t, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
            LoadingBar(vm.loading)
            ErrorBanner(vm.error) { vm.load() }

            when (tab) {
                0 -> ReportOverview(
                    vm,
                    onUnblockAll = { confirmUnblockAll = true },
                    onUnblock = { unblockTarget = it }
                )
                1 -> ReportIpList(vm) { unblockTarget = it }
                2 -> ReportUriList(vm)
                3 -> ReportRuleHit(vm)
                else -> ReportHitTypes(vm, snackbar)
            }
        }
    }

    // ── 解封单个 IP ──
    unblockTarget?.let { row ->
        val id = row.l("id").toDouble()
        val ip = row.s("ip")
        AlertDialog(
            onDismissRequest = { unblockTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    unblockTarget = null
                    vm.unsetIp(id, ip) { e ->
                        if (e != null) vm.error = e else scope.launch { snackbar.showSnackbar("已解封 $ip") }
                    }
                }) { Text("解封", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { unblockTarget = null }) { Text("取消") } },
            title = { Text("解除 IP 封禁") },
            text = {
                Text(
                    "确认解封 $ip 吗？解封后该 IP 将不再被拦截。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            icon = { Icon(Icons.Filled.LockOpen, null, tint = MaterialTheme.colorScheme.primary) }
        )
    }

    // ── 解封全部 ──
    if (confirmUnblockAll) {
        AlertDialog(
            onDismissRequest = { confirmUnblockAll = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnblockAll = false
                    vm.unsetAllIp { e ->
                        if (e != null) vm.error = e else scope.launch { snackbar.showSnackbar("已解封全部 IP") }
                    }
                }) { Text("全部解封", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnblockAll = false }) { Text("取消") } },
            title = { Text("解封全部 IP") },
            text = { Text("将清除所有被封禁的 IP 记录，此操作不可恢复。", style = MaterialTheme.typography.bodyMedium) },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) }
        )
    }
}

@Composable
private fun ReportOverview(
    vm: ReportViewModel,
    onUnblockAll: () -> Unit,
    onUnblock: (Map<String, Any?>) -> Unit
) {
    val total = vm.ipList.sumOf { it.l("visits") }
    val blocked = vm.ipList.count { it.l("block_status") != 0L }

    LazyColumn(
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            GridRow {
                MetricCard(
                    Icons.Filled.Public, "攻击来源", formatNumber(vm.ipTop.size.toLong()),
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    Icons.Filled.Link, "被攻击 URI", formatNumber(vm.uriTop.size.toLong()),
                    accent = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            GridRow {
                MetricCard(
                    Icons.Filled.GppBad, "攻击总量", formatNumber(total),
                    hint = "近 30 日", accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    Icons.Filled.Block, "当前封禁", blocked.toString(),
                    hint = "个 IP", accent = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            WafCard {
                SectionHeader("攻击类型分布", subtitle = "按 WAF 风险类型聚合")
                Spacer(Modifier.height(Space.md))
                BarChart(
                    vm.typeTop.map { it.s("risk_type") to it.l("type_total").toFloat() },
                    Modifier.fillMaxWidth()
                )
            }
        }
        item {
            WafCard {
                SectionHeader("攻击来源 Top", subtitle = "按 IP 聚合")
                Spacer(Modifier.height(Space.md))
                BarChart(
                    vm.ipTop.take(8).map { it.s("ip") to it.l("visits").toFloat() },
                    Modifier.fillMaxWidth(),
                    barColor = MaterialTheme.colorScheme.error
                )
            }
        }
        item {
            WafCard {
                SectionHeader("攻击 URI Top", subtitle = "按访问路径聚合")
                Spacer(Modifier.height(Space.md))
                BarChart(
                    vm.uriTop.take(8).map { it.s("uri") to it.l("uri_total").toFloat() },
                    Modifier.fillMaxWidth(),
                    barColor = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        if (vm.blockedList.isNotEmpty()) {
            item {
                WafCard {
                    SectionHeader(
                        "正在封禁的 IP",
                        subtitle = "${vm.blockedList.size} 条",
                        action = {
                            SmallButton("全部解封", tone = ChipTone.DANGER) { onUnblockAll() }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    vm.blockedList.take(6).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = Space.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.s("ip"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOf(row.s("ip_country"), row.s("ip_city"), row.s("server_name"))
                                        .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            StatusChip(
                                if (row.l("blocking_time") > 0) "封锁中" else "已解封",
                                if (row.l("blocking_time") > 0) ChipTone.DANGER else ChipTone.SUCCESS
                            )
                            Spacer(Modifier.width(Space.xs))
                            SmallButton("解封") { onUnblock(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportIpList(vm: ReportViewModel, onUnblock: (Map<String, Any?>) -> Unit) {
    if (vm.ipList.isEmpty()) {
        EmptyState(Icons.Filled.Public, "暂无攻击 IP 记录", description = "近 30 日内没有拦截到 IP")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            WafCard(contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
                vm.ipList.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(
                            icon = Icons.Filled.Public,
                            tint = MaterialTheme.colorScheme.primary,
                            size = 34.dp,
                            corner = 9.dp
                        )
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.s("ip"),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                listOf(row.s("ip_country"), row.s("ip_province"), row.s("ip_city"))
                                    .filter { it.isNotBlank() }.joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(Space.xs))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                StatusChip("${row.l("visits")} 次", ChipTone.DANGER)
                                if (row.s("risk_type").isNotBlank()) {
                                    StatusChip(row.s("risk_type"), ChipTone.WARNING)
                                }
                            }
                        }
                        Spacer(Modifier.width(Space.sm))
                        SmallButton("解封", tone = ChipTone.WARNING) { onUnblock(row) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportUriList(vm: ReportViewModel) {
    if (vm.uriTop.isEmpty()) {
        EmptyState(Icons.Filled.Link, "暂无攻击 URI", description = "近 30 日没有被攻击的路径")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            WafCard(contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
                vm.uriTop.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ListRow(
                        icon = Icons.Filled.Link,
                        title = row.s("uri").ifBlank { "/" },
                        subtitle = "命中 ${row.l("uri_total")} 次",
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        iconSize = 34.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportRuleHit(vm: ReportViewModel) {
    if (vm.ruleHit.isEmpty()) {
        EmptyState(
            Icons.Filled.Bolt, "暂无规则命中记录",
            description = "规则被触发时会在此记录"
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            WafCard(contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs)) {
                vm.ruleHit.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Space.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(
                            icon = Icons.Filled.Bolt,
                            tint = if (row.s("status") == "命中") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            size = 34.dp,
                            corner = 9.dp
                        )
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.s("rule_name").ifBlank { "(未命名规则)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${row.s("server_name")} ${row.s("uri")}".trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "IP ${row.s("ip")} ${row.s("ip_country")} · ${row.s("rule_type")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(Space.sm))
                        StatusChip(
                            row.s("status").ifBlank { "记录" },
                            if (row.s("status") == "命中") ChipTone.DANGER else ChipTone.INFO
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportHitTypes(vm: ReportViewModel, snackbar: SnackbarHostState) {
    if (vm.hitTypes.isEmpty()) {
        EmptyState(Icons.Filled.Tune, "未能加载命中开关", description = "请下拉刷新重试")
        return
    }
    val labels = mapOf(
        "IPw" to "IP 白名单命中", "IPb" to "IP 黑名单命中",
        "URIw" to "URL 白名单命中", "URIb" to "URL 黑名单命中",
        "UAw" to "UA 白名单命中", "UAb" to "UA 黑名单命中",
        "Customize" to "自定义规则命中", "CustomizeCC" to "自定义 CC 命中",
        "Area" to "区域拦截命中", "CloudIP" to "云 IP 库命中",
        "Man" to "人机验证命中", "Replace" to "内容替换命中"
    )
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.lg)
    ) {
        WafCard {
            SectionHeader(
                "命中日志开关",
                subtitle = "关闭后该类型命中不再写入命中日志（不影响拦截）"
            )
            Spacer(Modifier.height(Space.sm))
            labels.forEach { (key, label) ->
                SwitchRow(
                    title = label,
                    checked = vm.hitTypes[key].toBoolState(),
                    onChange = { vm.setHitType(key, it) }
                )
            }
        }
        Spacer(Modifier.height(Space.md))
        WafCard {
            SectionHeader("规则命中日志", subtitle = "共 ${vm.ruleHit.size} 条")
            Spacer(Modifier.height(Space.sm))
            if (vm.ruleHit.isEmpty()) {
                EmptyState(Icons.Filled.Bolt, "暂无记录")
            } else {
                SmallButton("清空命中日志", tone = ChipTone.DANGER) { vm.clearRuleHit() }
            }
        }
    }
}
