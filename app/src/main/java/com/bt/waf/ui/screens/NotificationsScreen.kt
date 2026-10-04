package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.CenteredProgress
import com.bt.waf.ui.components.ErrorText
import com.bt.waf.ui.components.LabeledField
import com.bt.waf.ui.components.SectionTitle
import com.bt.waf.util.asMap
import com.bt.waf.util.asList
import com.bt.waf.util.asString
import com.bt.waf.viewmodel.NotificationsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

/** 各渠道的配置字段（依据 public/notification 目录下各通知器结构定义）。 */
private val CHANNEL_FIELDS: Map<String, List<Pair<String, String>>> = mapOf(
    "email" to listOf(
        "email" to "发件邮箱",
        "host" to "SMTP 地址",
        "port" to "SMTP 端口",
        "password" to "SMTP 密码",
        "receivers" to "收件人(逗号分隔)"
    ),
    "dingding" to listOf("url" to "机器人 Webhook"),
    "feishu" to listOf("url" to "机器人 Webhook"),
    "weixin" to listOf("url" to "机器人 Webhook")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: NotificationsViewModel = viewModel(factory = WafViewModelFactory(repo))
    var editing by remember { mutableStateOf<Map<String, Any?>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(vm.error)
        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        LazyColumn {
            items(vm.channels) { ch ->
                val type = ch["type"].asString()
                val name = ch["name"].asString()
                val configured = ch["is_configured"] == true
                val cfg = ch["config"].asMap()
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (configured) "已配置" else "未配置",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row {
                            TextButton(onClick = {
                                values.clear()
                                (CHANNEL_FIELDS[type] ?: emptyList()).forEach { (k, _) ->
                                    values[k] = when (k) {
                                        "receivers" -> cfg["receivers"].asList().joinToString(",") { it.asString() }
                                        else -> cfg[k].asString()
                                    }
                                }
                                editing = ch
                            }) { Text("配置") }
                            TextButton(onClick = { vm.clear(type) {} }) { Text("清除") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { ch ->
        val type = ch["type"].asString()
        val fields = CHANNEL_FIELDS[type] ?: emptyList()
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    val cfg = HashMap<String, Any?>()
                    fields.forEach { (k, _) ->
                        cfg[k] = if (k == "receivers") {
                            values[k].orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
                        } else {
                            values[k].orEmpty()
                        }
                    }
                    vm.save(type, cfg) { editing = null }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
            title = { Text("配置 ${ch["name"].asString()}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionTitle("通知渠道参数")
                    fields.forEach { (k, label) ->
                        Spacer(Modifier.height(8.dp))
                        LabeledField(values[k] ?: "", { values[k] = it }, label, isPassword = k == "password")
                    }
                }
            }
        )
    }
}
