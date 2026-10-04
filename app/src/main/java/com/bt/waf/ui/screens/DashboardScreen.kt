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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.bt.waf.viewmodel.DashboardViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

@Composable
fun DashboardScreen(onOpenLogs: () -> Unit = {}, onOpenReport: () -> Unit = {}) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: DashboardViewModel = viewModel(factory = WafViewModelFactory(repo))

    val refreshTick = remember { mutableStateOf(0) }
    LaunchedEffect(refreshTick.value) { if (refreshTick.value > 0) vm.load() }

    val count = vm.countInfo["count"].asMap()
    val nginx = vm.nginxCount
    // 实时值优先取 nginx_count（QPS/流量），回退到 count_info
    fun pickLong(vararg pairs: Pair<Map<String, Any?>, String>): Long {
        for ((m, k) in pairs) {
            val v = m.l(k)
            if (v > 0) return v
        }
        return 0L
    }

    fun pickDouble(vararg pairs: Pair<Map<String, Any?>, String>): Double {
        for ((m, k) in pairs) {
            val v = m.s(k).toDoubleOrNull()
            if (v != null && v > 0.0) return v
        }
        return 0.0
    }

    val requestTotal = pickLong(count to "request_total", nginx to "request_total")
    val malicious = pickLong(count to "malicious_total")
    val qps = pickDouble(nginx to "qps", count to "qps")
    val recvBytes = pickLong(nginx to "recv_bytes", count to "recv_bytes")
    val sendBytes = pickLong(nginx to "send_bytes", count to "send_bytes")
    val proxyTime = pickDouble(nginx to "proxy_time", count to "proxy_time")
    // 防护网站数：优先取实时统计的 server_total，回退到拦截日志里出现过的站点数
    val siteTotal = count.i("server_total").takeIf { it > 0 }
        ?: vm.infos["intercept"].asList()
            .mapNotNull { it["server_name"]?.toString() }
            .filter { it.isNotBlank() }
            .distinct()
            .size

    val sys = if (vm.sysInfo.isNotEmpty()) vm.sysInfo else vm.countInfo["sys"].asMap()
    val host = sys["host"].asMap()
    val cpu = sys["cpu"].asMap()
    val mem = sys["mem"].asMap()
    val disk = sys["disk_list"].asList()
    val load = sys["loadavg"].asMap()

    // 请求趋势：overview/infos.request 是分钟级，按 data_m 排序后取最近 60 点
    val trendAll = remember(vm.infos) { vm.infos["request"].asList() }
    val trend = remember(trendAll) {
        trendAll.asReversed().takeLast(60).map { it.l("request").toFloat() }
    }
    val trendLabels = remember(trendAll) {
        val pts = trendAll.asReversed().takeLast(60)
        if (pts.isEmpty()) emptyList() else listOf(
            "${pts.first().i("hour")}:${pts.first().i("minute").toString().padStart(2, '0')}",
            "${pts.last().i("hour")}:${pts.last().i("minute").toString().padStart(2, '0')}"
        )
    }

    // 攻击来源 Top（overview/attack_map.map 按国家聚合）
    val countryTop = remember(vm.attackMap) {
        vm.attackMap["map"].asList()
            .sortedByDescending { it.l("visits") }
            .take(6)
            .map { (it.s("country").ifBlank { "未知" }) to it.l("visits").toFloat() }
    }

    // 攻击类型 Top（report/attack_report_count.type）
    val typeTop = remember(vm.attackReport) {
        vm.attackReport["type"].asList()
            .sortedByDescending { it.l("type_total") }
            .take(6)
            .map { (it.s("risk_type").ifBlank { "未知" }) to it.l("type_total").toFloat() }
    }

    // 攻击 URI Top
    val uriTop = remember(vm.attackReport) {
        vm.attackReport["uri"].asList()
            .sortedByDescending { it.l("uri_total") }
            .take(6)
            .map { (it.s("uri").ifBlank { "/" }) to it.l("uri_total").toFloat() }
    }

    val intercept = remember(vm.infos) { vm.infos["intercept"].asList() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LoadingBar(vm.loading)
        LazyColumn(
            contentPadding = PaddingValues(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item {
                ErrorBanner(vm.error) { refreshTick.value++ }
            }

            // ── 顶部欢迎条 ──
            item {
                WafCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "实时态势",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "共防护 $siteTotal 个网站 · 回源 ${"%.0f".format(proxyTime)} ms",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Shield,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // ── KPI 2x2 ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        MetricCard(
                            icon = Icons.Filled.Visibility,
                            label = "今日请求",
                            value = formatNumber(requestTotal),
                            hint = "QPS ${"%.1f".format(qps)}",
                            accent = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            icon = Icons.Filled.GppBad,
                            label = "恶意请求",
                            value = formatNumber(malicious),
                            hint = if (requestTotal > 0) {
                                "占比 ${"%.2f".format(malicious * 100.0 / requestTotal)}%"
                            } else null,
                            accent = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        MetricCard(
                            icon = Icons.Filled.Download,
                            label = "接收流量",
                            value = formatBytes(recvBytes),
                            hint = "实时",
                            accent = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            icon = Icons.Filled.Upload,
                            label = "发送流量",
                            value = formatBytes(sendBytes),
                            hint = "回源 ${"%.0f".format(proxyTime)}ms",
                            accent = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── 请求趋势 ──
            item {
                WafCard {
                    SectionHeader(
                        "请求趋势",
                        subtitle = "最近 60 分钟 · 来自 Nginx 实时采样",
                        action = {
                            SmallButton("刷新") { refreshTick.value++ }
                        }
                    )
                    Spacer(Modifier.height(Space.md))
                    if (trend.size >= 2) {
                        LineChart(
                            values = trend,
                            modifier = Modifier.fillMaxWidth().height(140.dp)
                        )
                        if (trendLabels.isNotEmpty()) {
                            Spacer(Modifier.height(Space.xs))
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    trendLabels.first(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "峰值 ${formatNumber(trend.max().toLong())}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    trendLabels.last(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        EmptyState(
                            icon = Icons.Filled.ShowChart,
                            title = "暂无趋势数据",
                            description = "开启防护后 Nginx 会持续上报采样点"
                        )
                    }
                }
            }

            // ── 攻击类型 / 来源 ──
            item {
                WafCard {
                    SectionHeader("攻击类型 Top", subtitle = "按风险类型聚合")
                    Spacer(Modifier.height(Space.md))
                    BarChart(typeTop, Modifier.fillMaxWidth())
                }
            }

            item {
                WafCard {
                    SectionHeader("攻击来源地区", subtitle = "近 30 日 · 按国家/地区")
                    Spacer(Modifier.height(Space.md))
                    BarChart(
                        countryTop,
                        Modifier.fillMaxWidth(),
                        barColor = MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                WafCard {
                    SectionHeader("高频攻击 URI", subtitle = "被攻击最多的路径")
                    Spacer(Modifier.height(Space.md))
                    BarChart(
                        uriTop,
                        Modifier.fillMaxWidth(),
                        barColor = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // ── 最近拦截 ──
            item {
                WafCard(onClick = onOpenLogs) {
                    SectionHeader(
                        "最近拦截",
                        subtitle = "最新 ${minOf(intercept.size, 8)} 条",
                        action = {
                            SmallButton("全部") { onOpenLogs() }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    if (intercept.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.VerifiedUser,
                            title = "暂无拦截记录",
                            description = "有攻击命中规则时会实时出现在这里"
                        )
                    } else {
                        intercept.take(8).forEach { row ->
                            InterceptRow(
                                ip = row.s("ip"),
                                location = listOf(row.s("ip_country"), row.s("ip_city"))
                                    .filter { it.isNotBlank() }.joinToString(" "),
                                uri = row.s("request_uri").ifBlank { row.s("uri") },
                                risk = row.s("risk_type"),
                                ts = row.l("time"),
                                action = row.i("action")
                            )
                        }
                    }
                }
            }

            // ── 资源占用 ──
            item {
                WafCard {
                    SectionHeader("资源占用", subtitle = host.s("platform").ifBlank { "服务器状态" })
                    Spacer(Modifier.height(Space.md))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        DonutChart(
                            percent = cpu.s("percent").toFloatOrNull() ?: 0f,
                            modifier = Modifier.size(96.dp),
                            color = MaterialTheme.colorScheme.primary,
                            centerText = "${(cpu.s("percent").toFloatOrNull() ?: 0f).toInt()}%",
                            subText = "CPU"
                        )
                        DonutChart(
                            percent = mem.s("used_percent").toFloatOrNull() ?: 0f,
                            modifier = Modifier.size(96.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            centerText = "${(mem.s("used_percent").toFloatOrNull() ?: 0f).toInt()}%",
                            subText = "内存"
                        )
                        DonutChart(
                            percent = disk.firstOrNull()?.s("used_percent")?.toFloatOrNull() ?: 0f,
                            modifier = Modifier.size(96.dp),
                            color = MaterialTheme.colorScheme.error,
                            centerText = "${(disk.firstOrNull()?.s("used_percent")?.toFloatOrNull() ?: 0f).toInt()}%",
                            subText = "磁盘"
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    DataRowDivider("主机", "${host.s("host_name")} · ${host.s("platform_version")}")
                    DataRowDivider("内核", host.s("kernel_version"))
                    DataRowDivider("运行时间", formatUpTime(host.l("up_time")))
                    DataRowDivider(
                        "内存",
                        "${formatBytes(mem.l("used"))} / ${formatBytes(mem.l("total"))}"
                    )
                    DataRowDivider(
                        "负载",
                        "%.2f  %.2f  %.2f".format(
                            load.s("last1min").toDoubleOrNull() ?: 0.0,
                            load.s("last5min").toDoubleOrNull() ?: 0.0,
                            load.s("last15min").toDoubleOrNull() ?: 0.0
                        )
                    )
                    if (disk.isNotEmpty()) {
                        DataRowDivider(
                            "磁盘",
                            disk.joinToString("  ") {
                                "${it.s("mountpoint")} ${(it.s("used_percent").toDoubleOrNull() ?: 0.0).toInt()}%"
                            }
                        )
                    }
                    DataRow("CPU 型号", cpu.s("model_name").ifBlank { "${cpu.i("logical_cores")} 核" })
                }
            }

            item {
                WafCard(onClick = onOpenReport) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Assessment, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text("查看完整攻击报表", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "按 IP / URI / 时间维度深入分析",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/** 拦截记录行：IP + 位置 + URI + 风险类型 + 处置动作。 */
@Composable
private fun InterceptRow(
    ip: String,
    location: String,
    uri: String,
    risk: String,
    ts: Long,
    action: Int
) {
    val tone = when (action) {
        2, 3 -> ChipTone.DANGER
        1 -> ChipTone.WARNING
        else -> ChipTone.NEUTRAL
    }
    val actionText = when (action) {
        2, 3 -> "拦截"
        1 -> "观察"
        else -> "记录"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Block, null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                ip.ifBlank { "未知 IP" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(location, uri).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(horizontalAlignment = Alignment.End) {
            StatusChip(actionText, tone)
            if (risk.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    risk,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(72.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}
