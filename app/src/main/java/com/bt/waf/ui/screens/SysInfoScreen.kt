package com.bt.waf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.l
import com.bt.waf.util.s
import com.bt.waf.viewmodel.SysInfoViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

/**
 * 系统信息页（sys/get_system_info → types.SystemInfo）：
 * host / cpu / mem / disk_list / net_io_list / loadavg 全部可视化。
 */
@Composable
fun SysInfoScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: SysInfoViewModel = viewModel(factory = WafViewModelFactory(repo))

    LaunchedEffect(Unit) { vm.load() }

    val sys = vm.info
    val host = sys["host"].asMap()
    val cpu = sys["cpu"].asMap()
    val mem = sys["mem"].asMap()
    val disk = sys["disk_list"].asList()
    val net = sys["net_io_list"].asList()
    val load = sys["loadavg"].asMap()

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
            item { ErrorBanner(vm.error) { vm.load() } }

            item {
                WafCard {
                    SectionHeader(
                        "主机概览",
                        subtitle = listOf(host.s("platform"), host.s("platform_version"))
                            .filter { it.isNotBlank() }.joinToString(" "),
                        action = { SmallButton("刷新") { vm.load() } }
                    )
                    Spacer(Modifier.height(Space.md))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        DonutChart(
                            percent = cpu.s("percent").toFloatOrNull() ?: 0f,
                            modifier = Modifier.size(100.dp),
                            centerText = "${(cpu.s("percent").toFloatOrNull() ?: 0f).toInt()}%",
                            subText = "CPU"
                        )
                        DonutChart(
                            percent = mem.s("used_percent").toFloatOrNull() ?: 0f,
                            modifier = Modifier.size(100.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            centerText = "${(mem.s("used_percent").toFloatOrNull() ?: 0f).toInt()}%",
                            subText = "内存"
                        )
                        DonutChart(
                            percent = (mem.s("swap_used_percent").toFloatOrNull() ?: 0f),
                            modifier = Modifier.size(100.dp),
                            color = MaterialTheme.colorScheme.error,
                            centerText = "${(mem.s("swap_used_percent").toFloatOrNull() ?: 0f).toInt()}%",
                            subText = "Swap"
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    DataRowDivider("主机名", host.s("host_name"))
                    DataRowDivider("系统", "${host.s("os")} ${host.s("platform_version")}")
                    DataRowDivider("内核", host.s("kernel_version").ifBlank { host.s("kernel_arch") })
                    DataRowDivider("运行时间", formatUpTime(host.l("up_time")))
                    DataRowDivider("进程数", host.l("procs").toString())
                    DataRowDivider("启动于", formatTime(host.l("boot_time") * 1000))
                    DataRowDivider(
                        "负载",
                        "%.2f / %.2f / %.2f".format(
                            load.s("last1min").toDoubleOrNull() ?: 0.0,
                            load.s("last5min").toDoubleOrNull() ?: 0.0,
                            load.s("last15min").toDoubleOrNull() ?: 0.0
                        )
                    )
                    DataRow(
                        "CPU 型号",
                        cpu.s("model_name").ifBlank { "${cpu.l("logical_cores")} 逻辑核" }
                    )
                }
            }

            item {
                WafCard {
                    SectionHeader("内存", subtitle = "物理内存与交换分区")
                    Spacer(Modifier.height(Space.sm))
                    DataRowDivider("已用", formatBytes(mem.l("used")))
                    DataRowDivider("可用", formatBytes(mem.l("free")))
                    DataRowDivider("缓存", "${formatBytes(mem.l("buffers"))} / ${formatBytes(mem.l("cached"))}")
                    DataRowDivider("总量", formatBytes(mem.l("total")))
                    DataRowDivider(
                        "Swap",
                        "${formatBytes(mem.l("swap_used"))} / ${formatBytes(mem.l("swap_total"))}"
                    )
                }
            }

            if (disk.isNotEmpty()) {
                item {
                    WafCard {
                        SectionHeader("磁盘", subtitle = "${disk.size} 个挂载点")
                        Spacer(Modifier.height(Space.md))
                        disk.forEach { d ->
                            Column(Modifier.padding(vertical = Space.sm)) {
                                Row {
                                    Text(
                                        d.s("mountpoint").ifBlank { d.s("name") },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${formatBytes(d.l("used"))} / ${formatBytes(d.l("total"))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                    StatusChip(
                                        "${(d.s("used_percent").toDoubleOrNull() ?: 0.0).toInt()}%",
                                        tone = when {
                                            (d.s("used_percent").toDoubleOrNull() ?: 0.0) > 90 -> ChipTone.DANGER
                                            (d.s("used_percent").toDoubleOrNull() ?: 0.0) > 75 -> ChipTone.WARNING
                                            else -> ChipTone.SUCCESS
                                        }
                                    )
                                }
                                Spacer(Modifier.height(Space.xs))
                                BarChart(
                                    listOf(
                                        "已用" to (d.s("used_percent").toFloatOrNull() ?: 0f),
                                        "空闲" to (100f - (d.s("used_percent").toFloatOrNull() ?: 0f))
                                    ),
                                    barColor = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            if (net.isNotEmpty()) {
                item {
                    WafCard {
                        SectionHeader("网络", subtitle = "各网卡实时速率")
                        Spacer(Modifier.height(Space.sm))
                        net.forEach { n ->
                            DataRowDivider(
                                n.s("name"),
                                "↓ ${formatBytes(n.l("recv_per_second"))}/s   ↑ ${formatBytes(n.l("sent_per_second"))}/s"
                            )
                            DataRow(
                                "累计流量",
                                "收 ${formatBytes(n.l("recv"))}   发 ${formatBytes(n.l("sent"))}"
                            )
                        }
                    }
                }
            }

            if (vm.version.isNotBlank()) {
                item {
                    WafCard {
                        SectionHeader("版本信息")
                        Spacer(Modifier.height(Space.sm))
                        DataRow("当前版本", vm.version)
                        vm.latest.takeIf { it.isNotBlank() && it != vm.version }?.let {
                            DataRow("最新版本", it, valueColor = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }
}
