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
import com.bt.waf.ui.components.CenteredProgress
import com.bt.waf.ui.components.ErrorText
import com.bt.waf.ui.components.LabeledField
import com.bt.waf.ui.components.SectionTitle
import com.bt.waf.ui.model.FieldSpec
import com.bt.waf.viewmodel.RulesViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

/**
 * 防护规则操作面板。
 * 参数严格对齐 modules/waf_rules.go：
 *  - wafrules/apply_all                 -> { type: cc | number_attacks }
 *  - wafrules/set_cc_rules              -> { site_id, status, cycle, open, limit, endtime, cc_type_status, type }
 *  - wafrules/set_site_run_mode         -> { site_id, mode }
 *  - wafrules/set_malicious_ip          -> { site_id, open, type }
 *  - wafrules/recover_default_config    -> { site_id, type: global | site }
 *  - wafrules/update_spider_rules       -> {}  （从 bt.cn 拉取最新爬虫规则）
 */
private data class RuleAction(
    val label: String,
    val note: String,
    val path: String,
    val fields: List<FieldSpec>,
    val extra: Map<String, Any?> = emptyMap()
)

private val cc: List<FieldSpec> = listOf(
    FieldSpec("site_id", "站点ID / allsite", defaultValue = "allsite"),
    FieldSpec("open", "开关 1开/0关", defaultValue = "1"),
    FieldSpec("cycle", "统计周期(秒)", defaultValue = "60"),
    FieldSpec("limit", "请求数阈值", defaultValue = "120"),
    FieldSpec("endtime", "封禁时长(秒)", defaultValue = "300"),
    FieldSpec("status", "拦截状态码", defaultValue = "444"),
    FieldSpec("cc_type_status", "类型状态", defaultValue = "2"),
    FieldSpec("type", "作用范围 global/site", defaultValue = "global")
)

private val RULE_ACTIONS: List<RuleAction> = listOf(
    RuleAction(
        "应用全局规则到所有站点（CC）", "将全局 CC 防护规则下发到全部站点",
        "wafrules/apply_all", emptyList(), mapOf("type" to "cc")
    ),
    RuleAction(
        "应用全局规则到所有站点（频次）", "将全局频次攻击规则下发到全部站点",
        "wafrules/apply_all", emptyList(), mapOf("type" to "number_attacks")
    ),
    RuleAction("设置 CC 防护", "按站点配置 CC 防护阈值", "wafrules/set_cc_rules", cc),
    RuleAction(
        "设置站点运行模式", "mode: 0关闭 1仅观察 2正常拦截",
        "wafrules/set_site_run_mode",
        listOf(
            FieldSpec("site_id", "站点ID", defaultValue = "allsite"),
            FieldSpec("mode", "模式 0/1/2", defaultValue = "2")
        )
    ),
    RuleAction(
        "云端恶意 IP 库", "open 1开启 0关闭；type 取 global / site",
        "wafrules/set_malicious_ip",
        listOf(
            FieldSpec("site_id", "站点ID", defaultValue = "allsite"),
            FieldSpec("open", "开关 1/0", defaultValue = "1"),
            FieldSpec("type", "作用范围 global/site", defaultValue = "global")
        )
    ),
    RuleAction(
        "恢复默认规则", "type 取 global / site，恢复后站点自定义规则将被覆盖",
        "wafrules/recover_default_config",
        listOf(
            FieldSpec("site_id", "站点ID", defaultValue = "allsite"),
            FieldSpec("type", "作用范围 global/site", defaultValue = "global")
        )
    ),
    RuleAction("更新爬虫规则库", "从官方源拉取最新爬虫特征（需外网）", "wafrules/update_spider_rules", emptyList())
)

@Composable
fun RulesScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: RulesViewModel = viewModel(factory = WafViewModelFactory(repo))
    val values = remember { mutableStateMapOf<String, String>() }
    var globalRules by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }

    LaunchedEffect(Unit) { vm.getGlobalRules { r, _ -> globalRules = r ?: emptyMap() } }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(vm.message)
        vm.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("全局规则当前值")
                if (globalRules.isEmpty()) Text("未读取到全局规则", color = MaterialTheme.colorScheme.outline)
                else globalRules.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(k, Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        Text(v.toString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        RULE_ACTIONS.forEach { a ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(a.label, style = MaterialTheme.typography.titleMedium)
                    Text(a.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    a.fields.forEach { f ->
                        Spacer(Modifier.height(6.dp))
                        val key = "${a.path}:${f.key}"
                        LabeledField(
                            value = values[key] ?: f.defaultValue,
                            onValueChange = { values[key] = it },
                            label = f.label
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val params = HashMap<String, Any?>(a.extra)
                        a.fields.forEach { f ->
                            val raw = values["${a.path}:${f.key}"] ?: f.defaultValue
                            params[f.key] = raw.toIntOrNull() ?: raw
                        }
                        vm.run(a.path, params) { }
                    }) { Text("执行") }
                }
            }
        }
    }
}
