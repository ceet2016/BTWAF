package com.bt.waf.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.util.asString
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.SitesViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitesScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: SitesViewModel = viewModel(factory = WafViewModelFactory(repo))

    var showCreate by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var deleteTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var detailTab by remember { mutableStateOf("info") }
    var siteLogs by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var siteSsl by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    val cf = remember { mutableStateMapOf("site_name" to "", "port" to "80", "server_ip" to "") }

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                cf["site_name"] = ""; cf["port"] = "80"; cf["server_ip"] = ""
                showCreate = true
            }) { Icon(Icons.Filled.Add, contentDescription = "新增站点") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ErrorText(vm.error)
            if (vm.loading) {
                CenteredProgress()
            } else if (vm.sites.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无防护网站", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(vm.sites) { site ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f).clickable {
                                    selected = site
                                    detailTab = "info"
                                    siteLogs = emptyList()
                                    siteSsl = emptyList()
                                }) {
                                    Text(
                                        pickString(site, "site_name", "name", "domain", "id").ifBlank { "(未命名站点)" },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        pickString(site, "status", "run_mode", "port", "is_cdn", "site_id"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                val status = site["status"].asString()
                                Switch(
                                    checked = status == "1" || status.equals("true", true),
                                    onCheckedChange = { vm.setStatus(site, it) { err -> if (err != null) vm.error = err } }
                                )
                                IconButton(onClick = {
                                    selected = site
                                    detailTab = "info"
                                    siteLogs = emptyList()
                                    siteSsl = emptyList()
                                }) { Icon(Icons.Filled.Info, contentDescription = "详情") }
                                IconButton(onClick = { deleteTarget = site }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.create(cf["site_name"].orEmpty(), cf["port"].orEmpty(), cf["server_ip"].orEmpty()) { err ->
                        if (err == null) showCreate = false else vm.error = err
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
            title = { Text("新增防护网站") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(8.dp))
                    LabeledField(cf["site_name"] ?: "", { cf["site_name"] = it }, "站点域名", "example.com")
                    Spacer(Modifier.height(8.dp))
                    LabeledField(cf["port"] ?: "80", { cf["port"] = it }, "端口", "80")
                    Spacer(Modifier.height(8.dp))
                    LabeledField(cf["server_ip"] ?: "", { cf["server_ip"] = it }, "源站地址", "127.0.0.1:8080")
                }
            }
        )
    }

    deleteTarget?.let { site ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(site) { err -> deleteTarget = null; if (err != null) vm.error = err }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            title = { Text("确认删除站点") },
            text = { Text(pickString(site, "site_name", "name", "domain", "id").ifBlank { "该站点" }) }
        )
    }

    selected?.let { site ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
            title = { Text(pickString(site, "site_name", "name", "domain", "id").ifBlank { "站点详情" }) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Row {
                        TextButton(onClick = { detailTab = "info" }) { Text("信息") }
                        TextButton(onClick = {
                            detailTab = "log"
                            vm.getLog(vm.siteId(site)) { l, e ->
                                siteLogs = l ?: emptyList()
                                if (e != null) vm.error = e
                            }
                        }) { Text("日志") }
                        TextButton(onClick = {
                            detailTab = "ssl"
                            vm.getSsl { s, e ->
                                siteSsl = s ?: emptyList()
                                if (e != null) vm.error = e
                            }
                        }) { Text("SSL") }
                    }
                    when (detailTab) {
                        "log" -> siteLogs.forEach { log ->
                            DataRow(pickString(log, "time", "create_time"), pickString(log, "url", "rule", "content"))
                        }

                        "ssl" -> siteSsl.forEach { s ->
                            DataRow(pickString(s, "name", "id"), pickString(s, "status", "endtime", "dns", "not_after"))
                        }

                        else -> site.forEach { (k, v) -> DataRow(k.toString(), v.asString()) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        vm.delete(site) { err -> if (err == null) selected = null else vm.error = err }
                    }) { Text("删除站点") }
                }
            }
        )
    }
}
