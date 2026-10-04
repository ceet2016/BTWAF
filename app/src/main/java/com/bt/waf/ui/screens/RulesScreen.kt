package com.bt.waf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.model.FieldSpec
import com.bt.waf.ui.theme.Space
import com.bt.waf.viewmodel.RulesViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

/**
 * 防护规则操作面板。
 * 参数严格对齐 aawaf 后端 wafrules 模块：
 *  - wafrules/apply_all                 -> { type: cc | number_attacks }
 *  - wafrules/set_cc_rules              -> { site_id, status, cycle, open, limit, endtime, cc_type_status, type }
 *  - wafrules/set_site_run_mode         -> { site_id, mode }
 *  - wafrules/set_malicious_ip          -> { site_id, open, type }
 *  - wafrules/recover_default_config    -> { site_id, type: global | site }
 *  - wafrules/update_spider_rules       -> {}（从官方源拉取最新爬虫规则）
 */
private data class RuleAction(
    val label: String,
    val note: String,
    val path: String,
    val fields: List<FieldSpec> = emptyList(),
    val extra: Map<String, Any?> = emptyMap(),
    val icon: ImageVector,
    val tint: Color,
    /** 危险操作需要二次确认 */
    val dangerous: Boolean = false,
    /** 展开表单（无字段的操作用 false 走一键执行） */
    val formless: Boolean = false
)

private data class RuleGroup(val title: String, val actions: List<RuleAction>)

private val ccFields: List<FieldSpec> = listOf(
    FieldSpec("site_id", "站点 ID", hint = "全部站点填 allsite", defaultValue = "allsite"),
    FieldSpec("open", "总开关", options = listOf("开启" to "1", "关闭" to "0"), defaultValue = "1"),
    FieldSpec("cycle", "统计周期（秒）", hint = "默认 60", defaultValue = "60"),
    FieldSpec("limit", "请求数阈值", hint = "超过即触发", defaultValue = "120"),
    FieldSpec("endtime", "封禁时长（秒）", hint = "默认 300", defaultValue = "300"),
    FieldSpec("status", "拦截状态码", hint = "444 表示直接断开", defaultValue = "444"),
    FieldSpec("cc_type_status", "类型状态", options = listOf("拦截" to "2", "记录" to "1"), defaultValue = "2"),
    FieldSpec("type", "作用范围", options = listOf("全局" to "global", "单站点" to "site"), defaultValue = "global")
)

private val scopeFields: List<FieldSpec> = listOf(
    FieldSpec("site_id", "站点 ID", hint = "全部站点填 allsite", defaultValue = "allsite"),
    FieldSpec("type", "作用范围", options = listOf("全局" to "global", "单站点" to "site"), defaultValue = "global")
)

private val RULE_GROUPS = listOf(
    RuleGroup("CC 攻击防护", listOf(
        RuleAction(
            "下发全局 CC 规则到所有站点", "把全局 CC 防护配置批量应用到全部站点",
            "wafrules/apply_all", extra = mapOf("type" to "cc"),
            icon = Icons.Filled.Bolt, tint = Color(0xFF7C3AED), formless = true
        ),
        RuleAction(
            "下发全局频次规则到所有站点", "把频次攻击防护批量应用到全部站点",
            "wafrules/apply_all", extra = mapOf("type" to "number_attacks"),
            icon = Icons.Filled.TrendingUp, tint = Color(0xFF2563EB), formless = true
        ),
        RuleAction(
            "CC 防护阈值配置", "按站点设置统计周期、触发阈值与封禁时长",
            "wafrules/set_cc_rules", fields = ccFields,
            icon = Icons.Filled.Tune, tint = Color(0xFFF97316)
        )
    )),
    RuleGroup("站点运行模式", listOf(
        RuleAction(
            "设置站点运行模式", "0 关闭防护 · 1 仅观察 · 2 正常拦截",
            "wafrules/set_site_run_mode",
            fields = listOf(
                FieldSpec("site_id", "站点 ID", hint = "全部站点填 allsite", defaultValue = "allsite"),
                FieldSpec(
                    "mode", "运行模式",
                    options = listOf("正常拦截" to "2", "仅观察" to "1", "关闭防护" to "0"),
                    defaultValue = "2"
                )
            ),
            icon = Icons.Filled.PlayCircle, tint = Color(0xFF0891B2)
        )
    )),
    RuleGroup("云端能力", listOf(
        RuleAction(
            "云端恶意 IP 库", "接入官方恶意 IP 情报库，自动识别已知攻击源",
            "wafrules/set_malicious_ip",
            fields = listOf(
                FieldSpec("site_id", "站点 ID", hint = "全部站点填 allsite", defaultValue = "allsite"),
                FieldSpec("open", "开关", options = listOf("开启" to "1", "关闭" to "0"), defaultValue = "1"),
                FieldSpec("type", "作用范围", options = listOf("全局" to "global", "单站点" to "site"), defaultValue = "global")
            ),
            icon = Icons.Filled.Security, tint = Color(0xFFDC2626)
        ),
        RuleAction(
            "更新搜索引擎爬虫规则库", "从官方源拉取最新爬虫特征库（需外网）",
            "wafrules/update_spider_rules",
            icon = Icons.Filled.TravelExplore, tint = Color(0xFF16A34A), formless = true
        )
    )),
    RuleGroup("维护操作", listOf(
        RuleAction(
            "恢复默认规则", "重置为官方默认防护配置，自定义规则会被覆盖",
            "wafrules/recover_default_config", fields = scopeFields,
            icon = Icons.Filled.Restore, tint = Color(0xFFEA580C), dangerous = true
        )
    ))
)

@Composable
fun RulesScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: RulesViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val values = remember { mutableStateMapOf<String, String>() }
    var globalRules by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmTarget by remember { mutableStateOf<RuleAction?>(null) }
    var running by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.getGlobalRules { r, _ -> globalRules = r ?: emptyMap() } }

    fun submit(a: RuleAction) {
        running = a.path
        val params = HashMap<String, Any?>(a.extra)
        a.fields.forEach { f ->
            val raw = values["${a.path}:${f.key}"] ?: f.defaultValue
            params[f.key] = raw.toIntOrNull() ?: raw
        }
        vm.run(a.path, params) { err ->
            running = null
            expanded = null
            scope.launch {
                snackbar.showSnackbar(err ?: "${a.label} 已执行")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item {
                SectionHeader(
                    "防护规则总览",
                    subtitle = "集中管理 CC 防护、运行模式与云端情报库"
                )
            }

            // 全局规则快照
            item {
                WafCard {
                    SectionHeader(
                        "当前全局规则",
                        subtitle = "来自 wafrules/get_global_rules",
                        action = {
                            SmallButton("刷新", tone = ChipTone.INFO) {
                                vm.getGlobalRules { r, _ -> globalRules = r ?: emptyMap() }
                            }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    if (globalRules.isEmpty()) {
                        Text(
                            "未读取到全局规则数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        globalRules.entries.take(8).forEach { (k, v) ->
                            DataRow(k.toString(), renderRuleValue(v))
                        }
                        if (globalRules.size > 8) {
                            Text(
                                "另有 ${globalRules.size - 8} 项未显示",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            RULE_GROUPS.forEach { group ->
                item(key = "h_${group.title}") {
                    SectionHeader(group.title, subtitle = "${group.actions.size} 项操作")
                }
                items(group.actions, key = { it.path + it.label }) { a ->
                    val isOpen = expanded == a.path + a.label
                    val busy = running == a.path
                    WafCard {
                        ListRow(
                            icon = a.icon,
                            title = a.label,
                            subtitle = a.note,
                            iconTint = a.tint,
                            iconSize = 36.dp,
                            trailing = {
                                if (a.dangerous) {
                                    StatusChip("危险", ChipTone.DANGER)
                                }
                            }
                        )

                        Spacer(Modifier.height(Space.md))

                        if (a.formless) {
                            PrimaryButton(
                                text = "立即执行",
                                onClick = { submit(a) },
                                loading = busy,
                                enabled = !busy
                            )
                        } else {
                            // 展开参数表单
                            if (isOpen) {
                                a.fields.forEach { f ->
                                    Spacer(Modifier.height(Space.sm))
                                    FieldEditor(
                                        spec = f,
                                        value = values["${a.path}:${f.key}"] ?: f.defaultValue,
                                        onChange = { values["${a.path}:${f.key}"] = it }
                                    )
                                }
                                Spacer(Modifier.height(Space.md))
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                    SmallButton(
                                        text = "取消",
                                        onClick = { expanded = null },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                    PrimaryButton(
                                        text = "提交",
                                        onClick = { submit(a) },
                                        loading = busy,
                                        modifier = Modifier.weight(2f)
                                    )
                                }
                            } else {
                                if (a.dangerous) {
                                    SmallButton(
                                        text = "配置并执行",
                                        tone = ChipTone.DANGER,
                                        onClick = { confirmTarget = a },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    SmallButton(
                                        text = "配置并执行",
                                        tone = ChipTone.INFO,
                                        onClick = { expanded = a.path + a.label },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }

    // 危险操作二次确认
    confirmTarget?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(a.label) },
            text = {
                Column {
                    Text(
                        "该操作会覆盖现有配置，且无法自动撤销。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Space.sm))
                    val scopeType = values["${a.path}:type"] ?: "global"
                    val siteId = values["${a.path}:site_id"] ?: "allsite"
                    Text(
                        "作用范围：${if (scopeType == "global") "全局" else "单站点"} · 站点：$siteId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTarget = null
                    submit(a)
                }) { Text("确认执行", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("取消") }
            }
        )
    }
}

/** 表单字段渲染：有 options 用下拉，否则文本框；multiline 用多行输入。 */
@Composable
private fun FieldEditor(spec: FieldSpec, value: String, onChange: (String) -> Unit) {
    when {
        spec.options != null -> DropdownField(
            value = value,
            onValueChange = onChange,
            label = spec.label,
            options = spec.options
        )
        spec.multiline -> LabeledField(
            value = value,
            onValueChange = onChange,
            label = spec.label,
            hint = spec.hint,
            singleLine = false
        )
        else -> LabeledField(
            value = value,
            onValueChange = onChange,
            label = spec.label,
            hint = spec.hint,
            keyboardType = if (spec.key.endsWith("len") || spec.key.endsWith("time") || spec.key == "limit")
                androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text
        )
    }
}

/** 全局规则值可能被后端序列化成字符串化的 JSON，这里做轻量清洗。 */
private fun renderRuleValue(v: Any?): String {
    val raw = v?.toString() ?: return "-"
    return if (raw.length > 120) raw.take(120) + "…" else raw
}
