package com.bt.waf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.i
import com.bt.waf.util.l
import com.bt.waf.util.s
import com.bt.waf.viewmodel.AttackLogsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private data class LogEntry(
    val route: String,
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val tint: Color
)

private val LOG_ENTRIES = listOf(
    LogEntry("list/attack_logs_full", "拦截日志", "每一条被 WAF 拦下的请求", Icons.Filled.GppBad, Color(0xFFDC2626)),
    LogEntry("list/ip_block_log", "IP 封禁记录", "自动封禁与解封历史", Icons.Filled.Block, Color(0xFFF97316)),
    LogEntry("list/cc_events", "CC 攻击事件", "高频访问触发的挑战记录", Icons.Filled.Bolt, Color(0xFF7C3AED)),
    LogEntry("list/rule_hit", "规则命中日志", "哪条规则被触发过", Icons.AutoMirrored.Filled.Rule, Color(0xFF2563EB)),
    LogEntry("list/opt_logs", "操作日志", "管理员的所有变更操作", Icons.Filled.History, Color(0xFF0891B2))
)

/**
 * 日志中心：5 类日志的统一入口 + 实时拦截流预览。
 * 点击卡片进入对应的通用列表页（带搜索/时间筛选/清空）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogCenterScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: AttackLogsViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var showClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LoadingBar(vm.loading)
        LazyColumn(
            contentPadding = cardPadding,
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item {
                GridRow {
                    MetricCard(
                        Icons.Filled.GppBad, "今日拦截", formatNumber(vm.todayCount.toLong()),
                        accent = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        Icons.Filled.FolderOpen, "日志总量", formatNumber(vm.total.toLong()),
                        hint = "条记录", accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                SectionHeader("日志分类", subtitle = "点击进入对应日志")
            }

            // 同组入口收进一张卡片：组内条目间距 2dp，组间 12dp，
            // 视觉上是「一块分类区」而不是「一摞散卡」。
            item {
                WafCard(contentPadding = rowCardPadding) {
                    LOG_ENTRIES.forEachIndexed { i, e ->
                        if (i > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        ListRow(
                            icon = e.icon,
                            title = e.title,
                            subtitle = e.desc,
                            iconTint = e.tint,
                            onClick = { onOpen(e.route) },
                            trailing = {
                                Icon(
                                    Icons.Filled.ChevronRight, null,
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "实时拦截流",
                        subtitle = "最新 ${minOf(vm.logs.size, 10)} 条",
                        action = {
                            Row {
                                SmallButton("刷新") { vm.load() }
                                Spacer(Modifier.width(Space.xs))
                                SmallButton("清空", tone = ChipTone.DANGER) { showClear = true }
                            }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    ErrorBanner(vm.error) { vm.load() }
                    if (vm.loading && vm.logs.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(Space.xl), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    } else if (vm.logs.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.VerifiedUser,
                            title = "暂无拦截记录",
                            description = "有攻击命中规则时会实时出现在这里"
                        )
                    } else {
                        vm.logs.take(10).forEach { log ->
                            LogRow(
                                ip = log.s("ip"),
                                location = listOf(log.s("ip_country"), log.s("ip_city"))
                                    .filter { it.isNotBlank() }.joinToString(" "),
                                uri = log.s("request_uri").ifBlank { log.s("uri") },
                                site = log.s("server_name"),
                                risk = log.s("risk_type"),
                                rule = log.s("filter_rule"),
                                ts = log.l("time"),
                                action = log.i("action"),
                                onClick = {
                                    vm.info(log["id"]) { d, _ -> detail = d }
                                }
                            )
                        }
                        if (vm.hasMore) {
                            Spacer(Modifier.height(Space.sm))
                            SmallButton("查看全部") { onOpen("list/attack_logs_full") }
                        }
                    }
                }
            }
        }
    }

    // 详情
    detail?.let { d ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            title = { Text("拦截详情") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    d.forEach { (k, v) ->
                        DataRowDivider(
                            k.toString(),
                            when (v) {
                                is List<*> -> v.joinToString(", ") { it.toString() }
                                null -> "-"
                                else -> v.toString()
                            }
                        )
                    }
                }
            }
        )
    }

    // 清空确认
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    vm.clear { e ->
                        scope.launch {
                            snackbar.showSnackbar(e ?: "拦截日志已清空")
                        }
                    }
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
            title = { Text("清空拦截日志") },
            text = { Text("将删除全部拦截日志记录，此操作不可恢复。", style = MaterialTheme.typography.bodyMedium) },
            icon = { Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) }
        )
    }
}

@Composable
private fun LogRow(
    ip: String,
    location: String,
    uri: String,
    site: String,
    risk: String,
    rule: String,
    ts: Long,
    action: Int,
    onClick: () -> Unit
) {
    val (tone, actionText) = when (action) {
        2, 3 -> ChipTone.DANGER to "拦截"
        1 -> ChipTone.WARNING to "观察"
        else -> ChipTone.NEUTRAL to "记录"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = Space.sm)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    ip.ifBlank { "未知 IP" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(location, site).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(Space.sm))
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(min = 68.dp)
            ) {
                StatusChip(actionText, tone)
                if (ts > 0) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        formatTime(ts * 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            listOf(uri, risk, rule).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "—" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
