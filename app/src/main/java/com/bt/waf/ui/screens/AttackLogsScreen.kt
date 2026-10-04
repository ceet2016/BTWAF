package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import com.bt.waf.util.asList
import com.bt.waf.util.asString
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.AttackLogsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttackLogsScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: AttackLogsViewModel = viewModel(factory = WafViewModelFactory(repo))
    var detail by remember { mutableStateOf<Map<String, Any?>?>(null) }
    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            LabeledField(vm.keyword, { vm.keyword = it }, "搜索关键字", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.load() }) { Text("搜索") }
        }
        ErrorText(vm.error)
        if (vm.loading) CenteredProgress()
        else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.logs) { log ->
                    val title = pickString(log, "url", "rule_name", "ip", "attack_ip", "site", "domain", "id")
                    val sub = pickString(log, "time", "create_time", "rule", "type", "action", "country")
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.info(log["id"]) { d, _ -> detail = d } }) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.bodyMedium)
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { d ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            title = { Text("攻击详情") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    d.forEach { (k, v) -> DataRowW(k.toString(), v.asString()) }
                }
            }
        )
    }
}

@Composable
private fun DataRowW(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
