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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.model.ListConfig
import com.bt.waf.util.asString
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.ManagedListViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

/** 后端开关字段写法不统一（布尔 / 数字 / 字符串），统一在此判定。 */
private fun isToggleOn(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toInt() == 1
    else -> v.asString() == "1" || v.asString().equals("true", true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagedListScreen(config: ListConfig) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: ManagedListViewModel = viewModel(factory = WafViewModelFactory(repo, config))

    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var createErr by remember { mutableStateOf<String?>(null) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(config.key) { vm.load() }

    Scaffold(
        floatingActionButton = {
            if (config.createPath != null) {
                FloatingActionButton(onClick = {
                    fieldValues.clear()
                    config.createFields.forEach { fieldValues[it.key] = it.defaultValue }
                    createErr = null
                    showCreate = true
                }) { Icon(Icons.Filled.Add, contentDescription = "新增") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (config.keywordSearch) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LabeledField(vm.keyword, { vm.keyword = it }, "搜索", modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.load() }) { Text("搜索") }
                }
            }
            ErrorText(vm.error)
            if (vm.loading) CenteredProgress()
            else if (vm.items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无数据", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn {
                    items(vm.items) { item ->
                        val title = pickString(item, *config.titleKeys.toTypedArray())
                        val sub = pickString(item, *config.subtitleKeys.toTypedArray())
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp).clickable {
                                    // 点击暂不展开，避免误触；删除/启停用按钮
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.bodyMedium)
                                    if (sub.isNotBlank()) {
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                if (config.togglePath != null) {
                                    Switch(
                                        checked = isToggleOn(item[config.toggleStateKey]),
                                        onCheckedChange = { vm.toggle(item) { err -> if (err != null) vm.error = err } }
                                    )
                                }
                                if (config.deletePath != null) {
                                    IconButton(onClick = { deleteTarget = item }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                                    }
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
                    vm.create(fieldValues.toMap()) { err ->
                        if (err == null) showCreate = false else createErr = err
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
            title = { Text("新增 · ${config.title}") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    config.createFields.forEach { f ->
                        Spacer(Modifier.height(8.dp))
                        if (f.options != null) {
                            DropdownField(fieldValues[f.key] ?: f.defaultValue, { fieldValues[f.key] = it }, f.label, f.options)
                        } else {
                            LabeledField(fieldValues[f.key] ?: f.defaultValue, { fieldValues[f.key] = it }, f.label, f.hint)
                        }
                    }
                    ErrorText(createErr)
                }
            }
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(item) { err -> deleteTarget = null; if (err != null) vm.error = err }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            title = { Text("确认删除") },
            text = { Text(pickString(item, *config.titleKeys.toTypedArray()).ifBlank { "该条目" }) }
        )
    }
}
