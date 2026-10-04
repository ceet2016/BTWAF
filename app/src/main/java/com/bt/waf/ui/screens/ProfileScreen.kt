package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.DataRow
import com.bt.waf.ui.components.ErrorText
import com.bt.waf.ui.components.LabeledField
import com.bt.waf.ui.components.SectionTitle
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import com.bt.waf.util.l
import com.bt.waf.util.s
import com.bt.waf.viewmodel.ProfileViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

private fun fmtBytes(value: String): String {
    val b = value.toDoubleOrNull() ?: return value.ifBlank { "-" }
    return when {
        b >= 1024 * 1024 * 1024 -> "%.2f GB".format(b / 1024 / 1024 / 1024)
        b >= 1024 * 1024 -> "%.2f MB".format(b / 1024 / 1024)
        b >= 1024 -> "%.2f KB".format(b / 1024)
        else -> "%.0f B".format(b)
    }
}

private fun uptime(seconds: Long): String {
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return buildString {
        if (d > 0) append("${d}天")
        append("${h}小时${m}分")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WafApplication
    val repo = app.repository
    val vm: ProfileViewModel = viewModel(factory = WafViewModelFactory(repo))

    var showPwd by remember { mutableStateOf(false) }
    var showName by remember { mutableStateOf(false) }
    var showRestart by remember { mutableStateOf(false) }
    val pwd = remember { mutableStateOf("") }
    val name = remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(vm.error)
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("账号信息")
                DataRow("账号", vm.profile.s("username"))
                DataRow("ID", vm.profile.s("id"))
                DataRow("WAF 版本", vm.profile.s("version"))
                DataRow("安全入口", vm.profile.s("admin_path"))
                DataRow("后台标题", vm.profile.s("title"))
                DataRow("语言", vm.profile.s("language"))
                val ct = vm.profile.l("create_time")
                if (ct > 0) {
                    DataRow(
                        "创建时间",
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(ct * 1000))
                    )
                }
            }
        }

        val host = vm.sysInfo["host"].asMap()
        val cpu = vm.sysInfo["cpu"].asMap()
        val mem = vm.sysInfo["mem"].asMap()
        val load = vm.sysInfo["loadavg"].asMap()
        if (host.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    SectionTitle("运行环境")
                    DataRow("主机名", host.s("host_name"))
                    DataRow("系统", "${host.s("platform")} ${host.s("platform_version")}")
                    DataRow("内核", host.s("kernel_version"))
                    DataRow("架构", host.s("kernel_arch"))
                    DataRow("运行时间", uptime(host.l("up_time")))
                    DataRow("进程数", host.s("procs"))
                    DataRow("CPU", "${cpu.s("model_name")} × ${cpu.s("logical_cores")}")
                    DataRow("CPU 占用", "${cpu.s("percent")}%")
                    DataRow("内存", "${fmtBytes(mem.s("used"))} / ${fmtBytes(mem.s("total"))}")
                    DataRow("负载", load.entries.joinToString(" / ") { it.value.asString() }.ifBlank { "-" })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { name.value = vm.profile.s("username"); showName = true }, Modifier.fillMaxWidth()) {
            Text("修改账号")
        }
        Button(onClick = { pwd.value = ""; showPwd = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("修改密码")
        }
        Button(onClick = { vm.updateVersion {} }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("检查并升级版本")
        }
        Button(
            onClick = { showRestart = true },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("重启 WAF 控制台") }
    }

    if (showName) {
        AlertDialog(
            onDismissRequest = { showName = false },
            confirmButton = {
                TextButton(onClick = { vm.updateUsername(name.value) { showName = false } }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showName = false }) { Text("取消") } },
            title = { Text("修改账号") },
            text = { LabeledField(name.value, { name.value = it }, "新账号") }
        )
    }

    if (showPwd) {
        AlertDialog(
            onDismissRequest = { showPwd = false },
            confirmButton = {
                TextButton(onClick = { vm.updatePassword(pwd.value) { showPwd = false } }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPwd = false }) { Text("取消") } },
            title = { Text("修改密码") },
            text = { LabeledField(pwd.value, { pwd.value = it }, "新密码", isPassword = true) }
        )
    }

    if (showRestart) {
        AlertDialog(
            onDismissRequest = { showRestart = false },
            confirmButton = {
                TextButton(onClick = { vm.restart {}; showRestart = false }) { Text("确认重启") }
            },
            dismissButton = { TextButton(onClick = { showRestart = false }) { Text("取消") } },
            title = { Text("重启 WAF 控制台") },
            text = { Text("重启期间后台将短暂不可用，确定继续？", Modifier.padding(top = 8.dp)) }
        )
    }
}
