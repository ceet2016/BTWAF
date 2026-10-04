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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asString
import com.bt.waf.util.l
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.ReplaceViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private const val MATCH_SITE = "site_rules"
private const val MATCH_URI = "uri_rules"

/**
 * 内容替换（关键词替换）。
 * 规则分两类：site_rules 全站生效、uri_rules 只对指定 URI 生效。
 * 每条规则记录命中次数（hit），可单独清零。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: ReplaceViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showSitePicker by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var deleteTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    val form = remember { mutableStateMapOf("type" to MATCH_SITE, "keyward" to "", "reValue" to "") }

    LaunchedEffect(Unit) { vm.loadSites(); vm.loadRules() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.consumeMessage()
        }
    }

    // 切换站点后自动重载规则
    LaunchedEffect(vm.siteId) {
        if (vm.siteId.isNotBlank()) vm.loadRules()
    }

    fun toast(s: String) {
        scope.launch { snackbar.showSnackbar(s) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (vm.siteId.isNotBlank()) {
                FloatingActionButton(
                    onClick = {
                        form["type"] = MATCH_SITE
                        form["keyward"] = ""
                        form["reValue"] = ""
                        showAdd = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) { Icon(Icons.Filled.Add, contentDescription = "新增规则") }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(
                        "内容替换",
                        subtitle = "自动把响应中的敏感内容替换掉",
                        modifier = Modifier.weight(1f)
                    )
                    SmallButton("刷新") { vm.loadRules() }
                }
            }

            // 站点选择
            item {
                WafCard(onClick = { showSitePicker = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Language, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "当前站点",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                vm.siteName.ifBlank { "请选择站点" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Filled.UnfoldMore, null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (vm.siteId.isBlank()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Language,
                        title = "还没有防护网站",
                        description = "内容替换需要绑定到具体站点，请先到「防护」页添加站点"
                    )
                }
            } else {
                item {
                    LoadingBar(vm.loading)
                    ErrorBanner(vm.error) { vm.loadRules() }
                }

                if (vm.rules.isEmpty() && !vm.loading) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.FindReplace,
                            title = "该站点还没有替换规则",
                            description = "添加规则后，命中的关键词会在返回给访客前被替换",
                            actionText = "新增规则",
                            onAction = { showAdd = true }
                        )
                    }
                }

                items(vm.rules.size, key = { vm.rules[it]["key"].asString() }) { ri ->
                    val rule = vm.rules[ri]
                    val isUri = rule["match_type"].asString() == MATCH_URI
                    val hit = rule.l("hit")
                    WafCard {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        (if (isUri) Color(0xFF7C3AED) else Color(0xFF0891B2))
                                            .copy(alpha = 0.12f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isUri) Icons.Filled.Link else Icons.Filled.Public,
                                    null,
                                    tint = if (isUri) Color(0xFF7C3AED) else Color(0xFF0891B2),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(Space.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule["keyward"].asString().ifBlank { "(空关键词)" },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Row {
                                    StatusChip(
                                        if (isUri) "URI 范围" else "全站",
                                        if (isUri) ChipTone.INFO else ChipTone.NEUTRAL
                                    )
                                    Spacer(Modifier.width(Space.xs))
                                    StatusChip(
                                        "命中 $hit",
                                        if (hit > 0) ChipTone.WARNING else ChipTone.NEUTRAL
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(Space.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("替换为", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                rule["re_value"].asString().ifBlank { "(空)" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (isUri) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "限定 URI：${rule["match_value"].asString().ifBlank { "全部" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(Space.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SmallButton(
                                text = "编辑",
                                tone = ChipTone.INFO,
                                modifier = Modifier.weight(1f)
                            ) {
                                form["type"] = rule["match_type"].asString().ifBlank { MATCH_SITE }
                                form["keyward"] = rule["keyward"].asString()
                                form["reValue"] = rule["re_value"].asString()
                                editTarget = rule
                            }
                            SmallButton(
                                text = "清命中",
                                modifier = Modifier.weight(1f)
                            ) { vm.clearHit(rule) { e -> toast(e ?: "命中数已清零") } }
                            SmallButton(
                                text = "删除",
                                tone = ChipTone.DANGER,
                                modifier = Modifier.weight(1f)
                            ) { deleteTarget = rule }
                        }
                    }
                }

                item {
                    WafCard {
                        SwitchRow(
                            title = "整站替换总开关",
                            description = "关闭后该站点的所有替换规则都不再生效",
                            checked = vm.rules.isNotEmpty(),
                            enabled = !vm.saving,
                            onChange = { on -> vm.setOpen(on) { e -> toast(e ?: "已${if (on) "开启" else "关闭"}") } }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }

    // 站点选择
    if (showSitePicker) {
        AlertDialog(
            onDismissRequest = { showSitePicker = false },
            icon = { Icon(Icons.Filled.Language, null) },
            title = { Text("选择站点") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (vm.sites.isEmpty()) {
                        Text("暂无防护网站", style = MaterialTheme.typography.bodyMedium)
                    }
                    vm.sites.forEach { s ->
                        val name = pickString(s, "site_name", "name", "domain", "id")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    vm.selectSite(s)
                                    showSitePicker = false
                                }
                                .padding(vertical = Space.sm, horizontal = Space.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = vm.siteId == pickString(s, "site_id", "id"),
                                onClick = {
                                    vm.selectSite(s)
                                    showSitePicker = false
                                }
                            )
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSitePicker = false }) { Text("关闭") }
            }
        )
    }

    // 新增 / 编辑规则
    if (showAdd || editTarget != null) {
        val isEdit = editTarget != null
        AlertDialog(
            onDismissRequest = { showAdd = false; editTarget = null },
            icon = { Icon(if (isEdit) Icons.Filled.Edit else Icons.Filled.Add, null) },
            title = { Text(if (isEdit) "编辑替换规则" else "新增替换规则") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    DropdownField(
                        value = form["type"].orEmpty(),
                        onValueChange = { form["type"] = it },
                        label = "生效范围",
                        options = listOf(
                            "全站（所有响应）" to MATCH_SITE,
                            "限定 URI" to MATCH_URI
                        )
                    )
                    Spacer(Modifier.height(Space.sm))
                    LabeledField(
                        value = form["keyward"].orEmpty(),
                        onValueChange = { form["keyward"] = it },
                        label = "关键词",
                        hint = "要被替换的原文"
                    )
                    Spacer(Modifier.height(Space.sm))
                    LabeledField(
                        value = form["reValue"].orEmpty(),
                        onValueChange = { form["reValue"] = it },
                        label = "替换为",
                        hint = "留空表示删除该关键词"
                    )
                    if (form["type"] == MATCH_URI) {
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "限定 URI 的规则需要在编辑时补充具体路径；此处先用关键词匹配全站。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !vm.saving,
                    onClick = {
                        val kw = form["keyward"].orEmpty()
                        val rv = form["reValue"].orEmpty()
                        val cb: (String?) -> Unit = { e ->
                            if (e != null) toast(e) else { showAdd = false; editTarget = null }
                        }
                        val t = editTarget
                        if (t != null) {
                            vm.updateRule(t, kw, rv, cb)
                        } else {
                            vm.addRule(form["type"].orEmpty(), kw, rv, cb)
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; editTarget = null }) { Text("取消") }
            }
        )
    }

    // 删除确认
    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除替换规则") },
            text = {
                Text(
                    "确认删除关键词「${rule["keyward"].asString()}」吗？删除后该规则立即失效。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = deleteTarget
                    deleteTarget = null
                    if (r != null) vm.deleteRule(r) { e -> toast(e ?: "规则已删除") }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
