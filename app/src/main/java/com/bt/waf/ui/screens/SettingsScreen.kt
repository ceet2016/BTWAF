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
import com.bt.waf.util.asList
import com.bt.waf.util.asString
import com.bt.waf.viewmodel.SettingsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

private enum class Panel { NONE, BASIC, AUTH, API, PWD, ADMIN, PORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: SettingsViewModel = viewModel(factory = WafViewModelFactory(repo))

    var panel by remember { mutableStateOf(Panel.NONE) }
    val bf = remember { mutableStateMapOf("title" to "", "domain" to "", "lan" to "zh", "timeout" to "120", "ips" to "") }
    val af = remember { mutableStateMapOf("user" to "", "pwd" to "") }
    val pf = remember { mutableStateMapOf("pwd" to "") }
    val apiLog = remember { mutableStateMapOf("limit" to "") }
    val adminPath = remember { mutableStateOf("") }
    val portValue = remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(vm.error)
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        KeyValueCard("系统配置", vm.config)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("安全 / 网络")
                DataRow("端口", vm.port.ifBlank { "-" })
                DataRow("Basic 认证", vm.basicAuth["open"].asString())
                DataRow("开放 API", vm.apiInfo["open"].asString())
                DataRow("限制地址", vm.apiInfo["limit_addr"].asList().joinToString(",") { it.asString() })
            }
        }
        Spacer(Modifier.height(12.dp))
        WrapButton("修改基本信息", Modifier.fillMaxWidth()) {
            bf["title"] = vm.config["title"].asString()
            bf["domain"] = vm.config["accept_domain"].asString()
            bf["lan"] = vm.config["language"].asString().ifBlank { "zh" }
            bf["timeout"] = vm.config["session_timeout"].asString().ifBlank { "120" }
            bf["ips"] = vm.config["accept_ip"].asList().joinToString(",") { it.asString() }
            panel = Panel.BASIC
        }
        WrapButton("修改账号密码", Modifier.fillMaxWidth()) { pf["pwd"] = ""; panel = Panel.PWD }
        WrapButton("Basic 认证开关", Modifier.fillMaxWidth()) {
            af["user"] = vm.basicAuth["basic_user"].asString()
            af["pwd"] = ""
            panel = Panel.AUTH
        }
        WrapButton("开放 API 设置", Modifier.fillMaxWidth()) {
            apiLog["limit"] = vm.apiInfo["limit_addr"].asList().joinToString("\n") { it.asString() }
            panel = Panel.API
        }
        WrapButton("修改安全入口", Modifier.fillMaxWidth()) {
            adminPath.value = vm.config["admin_path"].asString().trimStart('/')
            panel = Panel.ADMIN
        }
        WrapButton("修改后台端口", Modifier.fillMaxWidth()) {
            portValue.value = vm.port
            panel = Panel.PORT
        }
    }

    when (panel) {
        Panel.BASIC -> Dialog("修改基本信息", "保存", { panel = Panel.NONE }, {
            vm.setTitle(bf["title"].orEmpty()) {}
            vm.setDomain(bf["domain"].orEmpty()) {}
            vm.setLanguage(bf["lan"].orEmpty()) {}
            vm.setSessionTimeout(bf["timeout"].orEmpty()) {}
            vm.setAcceptIp(bf["ips"].orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }) {}
        }) {
            LabeledField(bf["title"] ?: "", { bf["title"] = it }, "后台标题")
            Spacer(Modifier.height(8.dp))
            LabeledField(bf["domain"] ?: "", { bf["domain"] = it }, "允许访问域名")
            Spacer(Modifier.height(8.dp))
            LabeledField(bf["lan"] ?: "zh", { bf["lan"] = it }, "语言 zh/en")
            Spacer(Modifier.height(8.dp))
            LabeledField(bf["timeout"] ?: "120", { bf["timeout"] = it }, "会话超时(秒)")
            Spacer(Modifier.height(8.dp))
            LabeledField(bf["ips"] ?: "", { bf["ips"] = it }, "允许访问IP(逗号分隔)")
        }

        Panel.PWD -> Dialog("修改账号密码", "保存", { panel = Panel.NONE }, {
            vm.updatePassword(pf["pwd"].orEmpty()) {}
        }) {
            LabeledField(pf["pwd"] ?: "", { pf["pwd"] = it }, "新密码", isPassword = true)
            Text(
                "密码需满足后台复杂度要求，且会立即生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Panel.AUTH -> Dialog("Basic 认证", "保存", { panel = Panel.NONE }, {
            vm.setBasicAuth(true, af["user"].orEmpty(), af["pwd"].orEmpty()) {}
        }) {
            LabeledField(af["user"] ?: "", { af["user"] = it }, "认证用户名")
            Spacer(Modifier.height(8.dp))
            LabeledField(af["pwd"] ?: "", { af["pwd"] = it }, "认证密码", isPassword = true)
        }

        Panel.API -> Dialog("开放 API", "保存限制地址", { panel = Panel.NONE }, {
            vm.setOpenApi(3, apiLog["limit"].orEmpty()) {}
        }) {
            LabeledField(apiLog["limit"] ?: "", { apiLog["limit"] = it }, "限制地址(每行一个)")
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = { vm.setOpenApi(2) {} }) { Text("切换开关") }
                TextButton(onClick = { vm.setOpenApi(1) {} }) { Text("重置 Token") }
            }
        }

        Panel.ADMIN -> Dialog("修改安全入口", "保存", { panel = Panel.NONE }, {
            vm.setAdminPath(adminPath.value) {}
        }) {
            LabeledField(adminPath.value, { adminPath.value = it }, "安全入口(≥8位)")
            Text(
                "修改后客户端需同步更新服务器地址，否则无法登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Panel.PORT -> Dialog("修改后台端口", "保存", { panel = Panel.NONE }, {
            vm.setPort(portValue.value) {}
        }) {
            LabeledField(portValue.value, { portValue.value = it }, "端口(1-65535)")
        }

        Panel.NONE -> Unit
    }
}

@Composable
private fun WrapButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.padding(vertical = 4.dp)) { Text(text) }
}

@Composable
private fun KeyValueCard(title: String, data: Map<String, Any?>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SectionTitle(title)
            data.forEach { (k, v) -> DataRow(k.toString(), v.asString()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dialog(
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    )
}
