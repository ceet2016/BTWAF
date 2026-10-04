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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import com.bt.waf.util.l
import com.bt.waf.util.s
import com.bt.waf.viewmodel.ProfileViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

/**
 * 账号信息与运维操作。
 *  - user/profile { id, username, create_time, version, admin_path, title, language }
 *  - sys/get_system_info { host, cpu, mem, disk_list, net_io_list, loadavg }
 *  - user/restart 重启控制台 / user/update 升级版本
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WafApplication
    val repo = app.repository
    val vm: ProfileViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPwd by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    var showRestart by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val pwd = remember { mutableStateOf("") }
    val name = remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.message = null
        }
    }

    fun toast(s: String) {
        scope.launch { snackbar.showSnackbar(s) }
    }

    val host = vm.sysInfo["host"].asMap()
    val cpu = vm.sysInfo["cpu"].asMap()
    val mem = vm.sysInfo["mem"].asMap()
    val load = vm.sysInfo["loadavg"].asMap()
    val username = vm.profile.s("username").ifBlank { "—" }

    val actions = listOf(
        ProfileAction("修改账号", "更改登录用户名", Icons.Filled.Badge, Color(0xFF2563EB), false) {
            name.value = vm.profile.s("username"); showName = true
        },
        ProfileAction("修改密码", "更改登录密码", Icons.Filled.Lock, Color(0xFF7C3AED), false) {
            pwd.value = ""; showPwd = true
        },
        ProfileAction("检查并升级版本", "从官方源拉取最新版本", Icons.Filled.SystemUpdate, Color(0xFF16A34A), false) {
            busy = true
            vm.updateVersion { e -> busy = false; toast(e ?: "已是最新版本") }
        },
        ProfileAction("重启 WAF 控制台", "重启期间后台会短暂不可用", Icons.Filled.RestartAlt, Color(0xFFDC2626), true) {
            showRestart = true
        }
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            // 账号名片
            item {
                WafCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                username.take(1).uppercase().ifBlank { "?" },
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            )
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(username, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "WAF 版本 ${vm.profile.s("version").ifBlank { "-" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        StatusChip(
                            if (host.isNotEmpty()) "在线" else "已连接",
                            ChipTone.SUCCESS
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(Space.sm))
                    DataRow("账号 ID", vm.profile.s("id").ifBlank { "-" })
                    DataRow("安全入口", vm.profile.s("admin_path").ifBlank { "-" })
                    DataRow("后台标题", vm.profile.s("title").ifBlank { "-" })
                    DataRow("语言", vm.profile.s("language").ifBlank { "-" })
                    val ct = vm.profile.l("create_time")
                    if (ct > 0) DataRow("创建时间", formatTime(ct * 1000))
                }
            }

            // 运行环境
            if (host.isNotEmpty()) {
                item {
                    WafCard {
                        SectionHeader("运行环境", subtitle = "sys/get_system_info")
                        Spacer(Modifier.height(Space.sm))
                        DataRowDivider("主机名", host.s("host_name").ifBlank { "-" })
                        DataRowDivider(
                            "系统",
                            "${host.s("platform")} ${host.s("platform_version")}".trim().ifBlank { "-" }
                        )
                        DataRowDivider("内核", host.s("kernel_version").ifBlank { "-" })
                        DataRowDivider("架构", host.s("kernel_arch").ifBlank { "-" })
                        DataRowDivider("运行时间", formatUpTime(host.l("up_time")))
                        DataRowDivider("进程数", host.s("procs").ifBlank { "-" })
                        DataRowDivider(
                            "CPU",
                            "${cpu.s("model_name")} × ${cpu.s("logical_cores")}".trim().ifBlank { "-" }
                        )
                        DataRowDivider("CPU 占用", "${cpu.s("percent")}%".ifBlank { "-" })
                        DataRowDivider(
                            "内存",
                            "${formatBytes(mem.l("used"))} / ${formatBytes(mem.l("total"))}"
                        )
                        DataRowDivider(
                            "系统负载",
                            listOf(load["last1min"], load["last5min"], load["last15min"])
                                .mapNotNull { it?.asString() }
                                .joinToString(" / ")
                                .ifBlank { "-" }
                        )
                    }
                }
            }

            // 连接信息
            item {
                WafCard {
                    SectionHeader("客户端连接", subtitle = "当前 App 的连接参数")
                    Spacer(Modifier.height(Space.sm))
                    DataRowDivider("服务器地址", app.authStore.webUrl.ifBlank { "-" })
                    DataRowDivider("API 基址", app.authStore.apiBase)
                    DataRowDivider("登录账号", app.authStore.username.ifBlank { "-" })
                    DataRowDivider(
                        "信任自签证书",
                        if (app.authStore.trustSelfSigned) "已开启" else "已关闭"
                    )
                }
            }

            // 运维操作
            item { SectionHeader("运维操作", subtitle = "${actions.size} 项") }

            items2(actions) { a ->
                WafCard(
                    onClick = { if (!busy) a.action() },
                    contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.md)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconBadge(icon = a.icon, tint = a.tint, size = 36.dp, corner = 10.dp)
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.title, style = MaterialTheme.typography.titleSmall)
                                if (a.dangerous) {
                                    Spacer(Modifier.width(Space.sm))
                                    StatusChip("高危", ChipTone.DANGER)
                                }
                            }
                            Text(
                                a.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Filled.ChevronRight, null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }

    // 修改账号
    if (showName) {
        FormDialog(
            title = "修改账号",
            icon = Icons.Filled.Badge,
            error = formError,
            onDismiss = { showName = false; formError = null },
            onConfirm = {
                val n = name.value
                vm.updateUsername(n) { e ->
                    if (e == null) {
                        showName = false
                        formError = null
                        toast("账号已更新为 $n")
                    } else {
                        formError = e
                    }
                }
                null
            }
        ) {
            LabeledField(name.value, { name.value = it; formError = null }, "新账号名")
            Spacer(Modifier.height(Space.sm))
            HintText("修改后当前登录会话可能失效，需要用新账号重新登录。")
        }
    }

    // 修改密码
    if (showPwd) {
        FormDialog(
            title = "修改密码",
            icon = Icons.Filled.Lock,
            error = formError,
            onDismiss = { showPwd = false; formError = null },
            onConfirm = {
                vm.updatePassword(pwd.value) { e ->
                    if (e == null) {
                        showPwd = false
                        pwd.value = ""
                        formError = null
                        toast("密码已修改")
                    } else {
                        formError = e
                    }
                }
                null
            }
        ) {
            LabeledField(pwd.value, { pwd.value = it; formError = null }, "新密码", isPassword = true)
            Spacer(Modifier.height(Space.sm))
            HintText("密码需满足后台复杂度要求，修改后立即生效。")
        }
    }

    // 重启确认
    if (showRestart) {
        AlertDialog(
            onDismissRequest = { showRestart = false },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("重启 WAF 控制台") },
            text = {
                Text(
                    "重启期间后台将短暂不可用，Web 防护规则不受影响。确定继续吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestart = false
                    busy = true
                    vm.restart { e ->
                        busy = false
                        toast(e ?: "重启指令已下发")
                    }
                }) { Text("确认重启", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRestart = false }) { Text("取消") } }
        )
    }
}

private data class ProfileAction(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val tint: Color,
    val dangerous: Boolean,
    val action: () -> Unit
)

/** LazyListScope 里按列表渲染（避免额外 import items 的重载歧义）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.items2(
    list: List<ProfileAction>,
    content: @Composable (ProfileAction) -> Unit
) {
    items(list.size) { i -> content(list[i]) }
}
