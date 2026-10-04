package com.bt.waf.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asString
import com.bt.waf.viewmodel.RulesViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

/* ══════════════════════════════════════════════════════════════════
 * 防护规则
 *
 * 对应 aawaf `modules/waf_rules.go`（Go 结构体 Wafrules）的全部 41 个接口，
 * 分九个 Tab：运行模式 / CC 防护 / CDN / HTTP 限制 / 爬虫与模拟 /
 * 区域拦截 / 单 IP 限制 / URL 改写 / 全局规则。
 *
 * 贯穿全文件的几条约定：
 *
 * 1. 写操作的错误一律渲染在弹窗内部（FormDialog 的 error 参数，
 *    或无表单的写操作走 RvNotice 弹窗），绝不用 Snackbar 承载错误：
 *    Snackbar 挂在 Scaffold 上而 AlertDialog 盖在其上，错误 100% 不可见。
 *    这是本项目已修过的坑，不要退回 Snackbar。
 *
 * 2. 成功文案不承诺成功。后端大量写接口把错误用 core.Success 包起来返回
 *    code=0（UserLimit 三兄弟的「没有此条规则」、recoverDefaultConfig 的 global
 *    分支、updateSpiderRules 的全流程、setMaliciousIp 的非 global 分支），
 *    所以统一用 rvDone 的「已提交，列表已重新拉取，请确认结果是否变化」。
 *
 * 3. 破坏性操作一律走 RvConfirm 二次确认，并在确认文案里写清后果：
 *    recoverDefaultConfig(site)、applyAll、applyGlobalRulesToSite、
 *    setHvv(open=true)、delRegionalCountryRules、以及三个删除类接口。
 *
 * 4. type 类参数全部做成必填下拉。setRetryRules / setIdcDeny / setMaliciousIp /
 *    setHvv 的 type 不在服务端 ParamsCheck 列表里但函数体无条件读取，
 *    漏传就是 nil 断言 panic（500），不会得到友好的参数错误。
 *
 * 5. 数组类参数由 ViewModel 封装成 JSON 数组，界面只管把 List 或
 *    逗号分隔的原始输入交给它，绝不在界面里拼 JSON 或数组字面量。
 * ══════════════════════════════════════════════════════════════════ */

private enum class RvTab(val label: String) {
    RUN("运行模式"),
    CC("CC 防护"),
    CDN("CDN"),
    HTTP("HTTP 限制"),
    SPIDER("爬虫与模拟"),
    REGION("区域拦截"),
    LIMIT("单 IP 限制"),
    REWRITE("URL 改写"),
    GLOBAL("全局规则")
}

/** 破坏性二次确认。用类承载是为了能把 lambda 一起存进 state。 */
private class RvConfirm(
    val title: String,
    val message: String,
    val warning: String? = null,
    val confirmText: String = "确认执行",
    val onConfirm: () -> Unit
)

/**
 * 结果告知弹窗。
 *
 * 承担两件事：
 *  a. 无表单的写操作（开关切换、删除、更新）失败时的错误出口；
 *  b. code=0 假成功的原文出口（例如 applyGlobalRulesToSite 把成功与
 *     失败的站点名拼在同一段 res 里）。
 * 两者都必须让用户看见，所以都进弹窗而不是 Snackbar。
 */
private class RvNotice(
    val title: String,
    val text: String,
    val danger: Boolean = false
)

/** 把 ViewModel 与页面级回调打包，避免每个 Tab 都拖一长串参数。 */
private class RvEnv(
    val vm: RulesViewModel,
    val siteId: String,
    val onSiteId: (String) -> Unit,
    val ask: (RvConfirm) -> Unit,
    val note: (RvNotice) -> Unit,
    val done: (String) -> Unit
)

/** 分组卡片里的一行入口。 */
private data class RvEntry(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val tone: ChipTone = ChipTone.NEUTRAL,
    val onClick: () -> Unit
)

/* ─────────────────────────── 枚举下拉选项 ─────────────────────────── */

private val RV_SCOPE_GS = listOf(
    "global · 写全局 config.json" to "global",
    "not_global · 写站点完整 7 项" to "not_global",
    "site · 只写 open 一个字段" to "site"
)

private val RV_SCOPE_GLOBAL_SITE = listOf(
    "global · 写全局 config.json" to "global",
    "site · 写站点配置" to "site"
)

private val RV_SCOPE_NOTGLOBAL_SITE = listOf(
    "not_global · 写站点完整配置" to "not_global",
    "site · 只写 cdn 一个字段" to "site"
)

private val RV_SCOPE_GLOBAL_NOTGLOBAL = listOf(
    "global · 写全局 config.json" to "global",
    "not_global · 写站点配置" to "not_global"
)

private val RV_RUN_MODES = RulesViewModel.SITE_RUN_MODES.map { (v, n) -> "$v · $n" to v.toString() }

private val RV_WAF_MODES = RulesViewModel.WAF_MODES.map { (v, n) -> "$v · $n" to v.toString() }

private val RV_CC_TYPE_STATUS = listOf(
    "0 · 关闭" to "0",
    "1 · 观察" to "1",
    "2 · 标准" to "2",
    "3 · 严格" to "3"
)

private val RV_ADD_DEL = listOf("add · 新增" to "add", "del · 删除" to "del")

private val RV_URI_OPS = listOf(
    "add · 新增" to "add",
    "modify · 编辑" to "modify",
    "del · 删除" to "del"
)

private val RV_TEXT_TYPES = listOf(
    "default · 用后端默认内容" to "default",
    "text · 自定义文本" to "text"
)

private val RV_REGION_RULE_TYPES = listOf(
    "refuse · 拦截该地区来访" to "refuse",
    "allow · 只允许该地区来访" to "allow"
)

private val RV_REGION_ADD_TYPES = listOf(
    "province · 按省" to "province",
    "city · 按市" to "city",
    "site_city · 海外" to "site_city"
)

private val RV_LIMIT_TYPES = listOf(
    "all · 全站（后端强制把 url 改成 /）" to "all",
    "url · 指定 URL" to "url"
)

private val RV_LIMIT_ACTIONS = listOf(
    "status · 用 return 里的状态码" to "status",
    "content · 返回自定义内容" to "content",
    "drop · 直接断开连接" to "drop",
    "status_404 · 返回 404" to "status_404",
    "status_403 · 返回 403" to "status_403",
    "status_502 · 返回 502" to "status_502",
    "status_503 · 返回 503" to "status_503"
)

private val RV_LIMIT_RETURNS = listOf(
    "html · HTML 提示页" to "html",
    "json · JSON 响应" to "json",
    "444 · 直接断开" to "444"
)

private val RV_LIMIT_CONDITIONS = listOf(
    "1 · 单个 IP" to "1",
    "2 · 单个 IP + UA" to "2",
    "3 · 所有 IP" to "3"
)

/** 站点配置里没有 method_type 节点时用这份常见清单兜底，方便直接建键。 */
private val RV_METHOD_FALLBACK = listOf(
    "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT"
)

/* ─────────────────────────── 值渲染工具 ─────────────────────────── */

private fun rvInt(v: Any?): Int = when (v) {
    is Number -> v.toInt()
    null -> 0
    else -> v.toString().trim().toIntOrNull() ?: 0
}

private fun rvLong(v: Any?): Long = when (v) {
    is Number -> v.toLong()
    null -> 0L
    else -> v.toString().trim().toLongOrNull() ?: 0L
}

/** 把任意层级的节点转成 Map；不是 Map 时返回空 map，调用方不用判空。 */
private fun rvMapOf(v: Any?): Map<String, Any?> {
    val raw = v ?: return emptyMap()
    return (raw as? Map<*, *>)?.entries?.associate { (k, v2) -> k.toString() to v2 } ?: emptyMap()
}

private fun rvListOf(v: Any?): List<Any?> = (v as? List<*>) ?: emptyList()

/** 节点里的 mode 字段转成可读文案。 */
private fun rvModeText(v: Any?): String {
    val m = rvInt(v)
    return RulesViewModel.WAF_MODES.firstOrNull { it.first == m }?.second ?: "m=$m"
}

/** 布尔型开关节点（idc / malicious_ip / readonly 等）的展示。 */
private fun rvSwitchText(v: Any?): String = if (v.toBoolState()) "开启" else "关闭"

private fun rvRegionLabel(k: String): String = when (k) {
    "中国" -> "中国大陆(不含港澳台)"
    "海外" -> "中国大陆以外的地区(含港澳台)"
    else -> k
}

/** 把逗号 / 顿号 / 空格 / 换行分隔的输入切成 List，去空去重。 */
private fun rvSplitList(raw: String): List<String> =
    raw.split(',', '，', ' ', '\n', '、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** 地区字典的形状随版本变化（可能是对象也可能是数组），两种都渲染成名字列表。 */
private fun rvRegionNames(raw: Any?): List<String> = when (raw) {
    is Map<*, *> -> raw.keys.map { rvRegionLabel(it.toString()) }
    is List<*> -> raw.map { item ->
        val m = item as? Map<*, *>
        if (m != null && m.isNotEmpty()) rvRegionLabel(m.keys.first().toString()) else item.toString()
    }

    else -> emptyList()
}

/** 限流规则的 region 节点摘要。 */
private fun rvLimitRegionText(region: Any?): String {
    val m = rvMapOf(region)
    if (m.isEmpty()) return "-"
    val parts = mutableListOf<String>()
    if (m.containsKey("req")) parts += "每秒请求数 ≤ ${rvInt(m["req"])}"
    val c = rvMapOf(m["count"])
    if (c.isNotEmpty()) parts += "${rvInt(c["time"])} 秒内超过 ${rvInt(c["count"])} 次"
    return if (parts.isEmpty()) "-" else parts.joinToString("；")
}

private fun rvLimitActionText(action: String): String =
    RV_LIMIT_ACTIONS.firstOrNull { it.second == action }?.first ?: action.ifBlank { "-" }

private fun rvLimitReturnText(v: String): String =
    RV_LIMIT_RETURNS.firstOrNull { it.second == v }?.first ?: v.ifBlank { "-" }

private fun rvLimitSiteText(v: Any?): String {
    val m = rvMapOf(v)
    if (m.isEmpty()) return v?.asString()?.ifBlank { "-" } ?: "-"
    if (m.containsKey("allsite")) return "全部网站"
    return m.keys.joinToString("、")
}

private fun rvLimitConditionText(v: Any?): String =
    RV_LIMIT_CONDITIONS.firstOrNull { it.second == rvInt(v).toString() }?.first ?: "condition=${rvInt(v)}"

/** 后端返回的 unix 秒，转成可读时间。 */
private fun rvUnixText(v: Any?): String {
    val sec = rvLong(v)
    if (sec <= 0) return "-"
    return formatTime(sec * 1000L)
}

/** 单 URL 频率规则：globalRules 里的 cc_uri_frequency 节点。 */
private fun rvUriFreqRules(global: Map<String, Any?>): List<Pair<String, Map<String, Any?>>> =
    rvMapOf(global["cc_uri_frequency"])
        .entries
        .map { it.key to rvMapOf(it.value) }
        .sortedBy { it.first }

/** 全局规则值的紧凑渲染：嵌套对象只取前几个键，避免一行刷屏。 */
private fun rvNodeText(v: Any?): String = when (v) {
    null -> "-"
    is Map<*, *> -> {
        val m = rvMapOf(v)
        if (m.isEmpty()) {
            "{}"
        } else {
            val head = m.entries.take(4).joinToString("、") { "${it.key}=${rvScalar(it.value)}" }
            if (m.size > 4) "$head … 共 ${m.size} 项" else head
        }
    }

    is List<*> -> if (v.isEmpty()) "[]" else v.joinToString("、") { rvScalar(it) }
    else -> rvScalar(v)
}

private fun rvScalar(v: Any?): String = when (v) {
    null -> "-"
    is Boolean -> if (v) "true" else "false"
    is Map<*, *> -> "{…}"
    is List<*> -> "[…]"
    else -> v.toString()
}

/** 把防护项键名转成可读中文，仅用于下拉项与标签。 */
private fun rvHumanKey(key: String): String = when (key) {
    "sql" -> "SQL 注入拦截"
    "xss" -> "XSS 注入拦截"
    "ssrf" -> "SSRF 代码执行"
    "download" -> "恶意下载防御"
    "scan" -> "扫描器防御"
    "file_import" -> "文件包含防御"
    "file_upload" -> "恶意文件上传"
    "from_data" -> "畸形协议检测"
    "user_agent" -> "恶意爬虫防御"
    "php_eval" -> "PHP 代码执行"
    "cookie" -> "Cookie 头攻击"
    else -> key
}

/* ══════════════════════════════════════════════════════════════════
 * 入口
 * ══════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: RulesViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(RvTab.RUN) }
    var confirm by remember { mutableStateOf<RvConfirm?>(null) }
    var notice by remember { mutableStateOf<RvNotice?>(null) }
    var siteId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.getGlobalRules { _, _ -> } }

    val env = RvEnv(
        vm = vm,
        siteId = siteId,
        onSiteId = { siteId = it },
        ask = { confirm = it },
        note = { notice = it },
        done = { what ->
            // 成功文案只陈述「已提交且已重新拉取」，不承诺生效。
            scope.launch { snackbar.showSnackbar("$what 已提交，列表已重新拉取，请确认结果是否变化") }
        }
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = RvTab.entries.indexOf(tab),
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                RvTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(t.label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            LoadingBar(vm.loading)
            ErrorBanner(vm.error)

            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    RvTab.RUN -> RvRunTab(env)
                    RvTab.CC -> RvCcTab(env)
                    RvTab.CDN -> RvCdnTab(env)
                    RvTab.HTTP -> RvHttpTab(env)
                    RvTab.SPIDER -> RvSpiderTab(env)
                    RvTab.REGION -> RvRegionTab(env)
                    RvTab.LIMIT -> RvLimitTab(env)
                    RvTab.REWRITE -> RvRewriteTab(env)
                    RvTab.GLOBAL -> RvGlobalTab(env)
                }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = {
                Icon(
                    if (c.warning.isNullOrBlank()) Icons.Filled.Info else Icons.Filled.Warning,
                    null,
                    tint = if (c.warning.isNullOrBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            },
            title = { Text(c.title) },
            text = {
                Column {
                    Text(c.message, style = MaterialTheme.typography.bodyMedium)
                    if (!c.warning.isNullOrBlank()) {
                        Spacer(Modifier.height(Space.md))
                        HintText(c.warning, danger = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    c.onConfirm()
                }) { Text(c.confirmText, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            }
        )
    }

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = { notice = null },
            icon = {
                Icon(
                    if (n.danger) Icons.Filled.ErrorOutline else Icons.Filled.Info,
                    null,
                    tint = if (n.danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(n.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HintText(n.text, danger = n.danger)
                }
            },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } }
        )
    }
}

/* ══════════════════════════════════════════════════════════════════
 * 公共片段
 * ══════════════════════════════════════════════════════════════════ */

/** 目标站点输入框。凡是带 site_id 的接口都要先填这里。 */
@Composable
private fun RvSiteField(env: RvEnv, hint: String = "填站点 ID；全部网站填 allsite") {
    LabeledField(
        value = env.siteId,
        onValueChange = env.onSiteId,
        label = "目标站点 site_id",
        hint = hint
    )
}

/**
 * 一组入口收进一张卡片。
 * 组内条目用可点击行 + HorizontalDivider，而不是每个入口一张卡片，
 * 这样一屏能看到的入口数量从 3 个提到 8 个。
 */
@Composable
private fun RvEntryCard(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    entries: List<RvEntry>
) {
    WafCard(contentPadding = rowCardPadding) {
        SectionHeader(title, subtitle = subtitle, action = action)
        Spacer(Modifier.height(Space.xs))
        entries.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { e.onClick() }
                    .padding(vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(icon = e.icon, tint = MaterialTheme.colorScheme.primary, size = 30.dp, corner = 9.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        e.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Spacer(Modifier.width(Space.sm))
                val chipText = when (e.tone) {
                    ChipTone.DANGER -> "高危"
                    ChipTone.WARNING -> "注意"
                    ChipTone.INFO -> "可读"
                    else -> ""
                }
                if (chipText.isNotEmpty()) StatusChip(chipText, e.tone)
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 每个 Tab 的 LazyColumn 统一内边距与间距。 */
/**
 * 规则页各Tab 共用的列表容器内边距。
 * 底边留Space.xxl 是因为本页没有 FAB，需要给最后一个卡片多留呼吸位。
 */
private fun rvTabPadding() = PaddingValues(
    start = Space.lg, end = Space.lg,
    top = Space.md, bottom = Space.xxl
)

/** 无表单的写操作统一在这里收口：成功走 done，失败进 notice 弹窗。 */
private fun RvEnv.act(
    what: String,
    checkLastMessage: Boolean = false,
    block: ((String?) -> Unit) -> Unit
) {
    block { e ->
        if (e == null) {
            val msg = vm.lastMessage
            if (checkLastMessage && !msg.isNullOrBlank() && msg.contains("没有此条规则")) {
                note(RvNotice("未生效", "服务端返回：$msg", danger = true))
            } else {
                done(what)
            }
        } else {
            note(RvNotice("操作失败", e, danger = true))
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 1 · 运行模式
 * 覆盖：getRules(只读展示) / setSiteRunMode / setModeRules / setHvv /
 *      setIdcDeny / setMaliciousIp / setFileUploadRules / setFileScanRules
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvRunTab(env: RvEnv) {
    val vm = env.vm
    var runMode by remember { mutableStateOf(false) }
    var modeLevel by remember { mutableStateOf(false) }
    var hvv by remember { mutableStateOf(false) }
    var idc by remember { mutableStateOf(false) }
    var malicious by remember { mutableStateOf(false) }
    var fileUpload by remember { mutableStateOf(false) }
    var fileScan by remember { mutableStateOf(false) }

    LaunchedEffect(env.siteId) { if (env.siteId.isNotBlank()) vm.getRules(env.siteId) }

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader(
                "运行模式与防护开关",
                subtitle = "先选站点，再逐项调整；带 type 的接口漏传 type 会 panic"
            )
        }

        item {
            WafCard {
                RvSiteField(env)
                Spacer(Modifier.height(Space.sm))
                SmallButton(
                    text = "读取站点规则",
                    tone = ChipTone.INFO,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (env.siteId.isBlank()) {
                        env.note(RvNotice("缺少 site_id", "site_id 不能为空。", danger = true))
                    } else {
                        vm.getRules(env.siteId)
                    }
                }
            }
        }

        item {
            WafCard {
                SectionHeader("站点当前状态", subtitle = "get_rules 返回的该站点规则子树")
                Spacer(Modifier.height(Space.sm))
                if (vm.siteRules.isEmpty()) {
                    Text(
                        "尚未读取。站点 ID 不在防护配置内时后端返回空对象，属正常情况。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val cc = rvMapOf(vm.siteRules["cc"])
                    val scan = rvMapOf(vm.siteRules["scan"])
                    GridRow {
                        GridCell {
                            MetricCard(
                                icon = Icons.Filled.PlayCircle,
                                label = "运行模式",
                                value = rvModeText(vm.siteRules["mode"]),
                                hint = "0 关 / 1 记录 / 2 拦截 / 3 维护页",
                                accent = MaterialTheme.colorScheme.primary
                            )
                        }
                        GridCell {
                            MetricCard(
                                icon = Icons.Filled.Bolt,
                                label = "CC 防护",
                                value = rvSwitchText(cc["open"]),
                                hint = "阈值 ${rvInt(cc["limit"])} / ${rvInt(cc["cycle"])} 秒",
                                accent = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    GridRow {
                        GridCell {
                            MetricCard(
                                icon = Icons.Filled.Shield,
                                label = "护网只读",
                                value = rvSwitchText(rvMapOf(vm.siteRules["readonly"])["open"]),
                                hint = "开启会破坏登录支付",
                                accent = MaterialTheme.colorScheme.error
                            )
                        }
                        GridCell {
                            MetricCard(
                                icon = Icons.Filled.Search,
                                label = "扫描器防御",
                                value = rvModeText(scan["mode"]),
                                hint = "站点规则共 ${vm.siteRules.size} 项",
                                accent = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        item {
            RvEntryCard(
                title = "防护开关",
                subtitle = "7 项 · 都作用于当前选中的站点",
                entries = listOf(
                    RvEntry(
                        Icons.Filled.PlayCircle, "站点运行模式",
                        "0 关闭 / 1 只记录 / 2 拦截 / 3 维护页",
                        ChipTone.INFO
                    ) { runMode = true },
                    RvEntry(
                        Icons.Filled.Tune, "单项防护级别",
                        "SQL / XSS / SSRF 等 11 个节点的 mode"
                    ) { modeLevel = true },
                    RvEntry(
                        Icons.Filled.ShieldMoon, "护网只读模式",
                        "全模块唯一会直接破坏业务功能的开关",
                        ChipTone.DANGER
                    ) { hvv = true },
                    RvEntry(
                        Icons.Filled.Public, "禁用 IDC 机房",
                        "按整数 0 / 1 写入 idc 节点",
                        ChipTone.WARNING
                    ) { idc = true },
                    RvEntry(
                        Icons.Filled.Security, "云端恶意 IP 库",
                        "服务端只有 global 分支真实生效",
                        ChipTone.WARNING
                    ) { malicious = true },
                    RvEntry(
                        Icons.Filled.Upload, "恶意文件上传拦截",
                        "同时写 mode 与 status"
                    ) { fileUpload = true },
                    RvEntry(
                        Icons.Filled.FolderOpen, "目录扫描防御",
                        "只改全局，没有 site_id 也没有 type"
                    ) { fileScan = true }
                )
            )
        }

        item {
            WafCard {
                SectionHeader("这两个 type 决定写哪个文件", subtitle = "set_mode_rules / set_file_upload_rules")
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "type=global 改的是全局模板 config.json，会影响之后所有按全局初始化的站点；" +
                        "type=site 改的是站点自己的配置。modename 传一个配置里不存在的节点名，" +
                        "服务端会直接对该节点做 map 断言，返回 500。"
                )
            }
        }
    }

    if (runMode) RvRunModeDialog(env) { runMode = false }
    if (modeLevel) RvModeLevelDialog(env) { modeLevel = false }
    if (hvv) RvHvvDialog(env) { hvv = false }
    if (idc) RvIdcDialog(env) { idc = false }
    if (malicious) RvMaliciousIpDialog(env) { malicious = false }
    if (fileUpload) RvFileUploadDialog(env) { fileUpload = false }
    if (fileScan) RvFileScanDialog(env) { fileScan = false }
}

/* ── set_site_run_mode ── */
@Composable
private fun RvRunModeDialog(env: RvEnv, close: () -> Unit) {
    var mode by remember { mutableStateOf("2") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "站点运行模式",
        icon = Icons.Filled.PlayCircle,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            val m = mode.toIntOrNull() ?: 2
            fun submit() {
                env.vm.setSiteRunMode(env.siteId, m) { e ->
                    if (e == null) {
                        close()
                        env.done("设置运行模式")
                    } else {
                        err = e
                    }
                }
            }
            if (m == 3) {
                env.ask(
                    RvConfirm(
                        title = "确认切换到维护页模式",
                        message = "site_id=${env.siteId} 会被切到 mode=3。",
                        warning = "维护页模式会直接对外返回维护页面，真实访客将无法访问该站点，" +
                            "本页面也没有恢复的快捷入口。",
                        confirmText = "确认切到维护页"
                    ) { submit() }
                )
            } else {
                submit()
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = mode, onValueChange = { mode = it }, label = "运行模式", options = RV_RUN_MODES)
        Spacer(Modifier.height(Space.sm))
        HintText(
            "mode 不在枚举里不会报错：操作日志里的模式名会变成空字符串，" +
                "但配置里仍会写入那个非法数字。站点 ID 不在 site.json 里时后端跳过写入却仍返回成功。"
        )
    }
}

/* ── set_mode_rules ── */
@Composable
private fun RvModeLevelDialog(env: RvEnv, close: () -> Unit) {
    var key by remember { mutableStateOf(RulesViewModel.RULE_MODE_KEYS.first()) }
    var mode by remember { mutableStateOf("2") }
    var type by remember { mutableStateOf("global") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "单项防护级别",
        icon = Icons.Filled.Tune,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setModeRules(
                env.siteId,
                (mode.toIntOrNull() ?: 2),
                key,
                type
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置 $key 级别")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = key,
            onValueChange = { key = it },
            label = "防护项 modename",
            options = RulesViewModel.RULE_MODE_KEYS.map { rvHumanKey(it) to it }
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = mode, onValueChange = { mode = it }, label = "防护级别", options = RV_WAF_MODES)
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "作用范围 type", options = RV_SCOPE_GLOBAL_SITE)
        Spacer(Modifier.height(Space.sm))
        HintText("modename 只能取服务端默认配置里存在的节点名，其他值会让服务端 500。")
    }
}

/* ── set_hvv ── */
@Composable
private fun RvHvvDialog(env: RvEnv, close: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("not_global") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "护网 / 只读模式",
        icon = Icons.Filled.ShieldMoon,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            fun submit() {
                env.vm.setHvv(env.siteId, open, type) { e ->
                    if (e == null) {
                        close()
                        env.done("设置护网只读模式")
                    } else {
                        err = e
                    }
                }
            }
            if (open) {
                env.ask(
                    RvConfirm(
                        title = "确认开启只读（护网）模式",
                        message = "site_id=${env.siteId}，type=$type。",
                        warning = "服务端对只读模式的说明是「请勿在非攻防演练时开启」，" +
                            "开启后站点的登录、支付、搜索、注册、评论等功能都会受影响。" +
                            "这是本模块唯一会直接破坏业务功能的开关。",
                        confirmText = "我已知晓后果，确认开启"
                    ) { submit() }
                )
            } else {
                submit()
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "开启只读模式",
            checked = open,
            onChange = { open = it },
            description = "开启时服务端会先查授权，数量不足直接返回「授权网站数量不足」"
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "作用范围 type（必填）", options = RV_SCOPE_GS)
        Spacer(Modifier.height(Space.sm))
        HintText("type 不在服务端参数校验列表里，但函数体无条件读取它，漏传会直接 panic。")
    }
}

/* ── set_idc_deny ── */
@Composable
private fun RvIdcDialog(env: RvEnv, close: () -> Unit) {
    var open by remember { mutableStateOf("1") }
    var type by remember { mutableStateOf("not_global") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "禁用 IDC 机房",
        icon = Icons.Filled.Public,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setIdcDeny(env.siteId, open.toIntOrNull() ?: 0, type) { e ->
                if (e == null) {
                    close()
                    env.done("设置 IDC 限制")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = open,
            onValueChange = { open = it },
            label = "open（整数 0 / 1）",
            options = listOf("1 · 禁用 IDC" to "1", "0 · 允许 IDC" to "0")
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "作用范围 type（必填）", options = RV_SCOPE_GS)
        Spacer(Modifier.height(Space.sm))
        HintText("开启后 IDC 机房 IP 段的访客会被拒绝访问，属于影响线上可达性的改动。open 传的是整数不是布尔。")
    }
}

/* ── set_malicious_ip ── */
@Composable
private fun RvMaliciousIpDialog(env: RvEnv, close: () -> Unit) {
    var open by remember { mutableStateOf("1") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "云端恶意 IP 库",
        icon = Icons.Filled.Security,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setMaliciousIp(env.siteId, open.toIntOrNull() ?: 0, "global") { e ->
                if (e == null) {
                    close()
                    env.done("设置云端恶意 IP 库")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = open,
            onValueChange = { open = it },
            label = "open（整数 0 / 1）",
            options = listOf("1 · 接入云端库" to "1", "0 · 关闭" to "0")
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            "服务端这个接口的 switch 只有 global 一个分支：传 not_global 或 site 时" +
                "它不写任何东西，却继续走完备份并返回「操作成功」。所以这里固定提交 global，" +
                "不提供站点级开关入口。",
            danger = true
        )
    }
}

/* ── set_file_upload_rules ── */
@Composable
private fun RvFileUploadDialog(env: RvEnv, close: () -> Unit) {
    var status by remember { mutableStateOf("444") }
    var mode by remember { mutableStateOf("3") }
    var type by remember { mutableStateOf("global") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "恶意文件上传拦截",
        icon = Icons.Filled.Upload,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setFileUploadRules(
                env.siteId,
                (status.toIntOrNull() ?: 444),
                (mode.toIntOrNull() ?: 3),
                type
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置文件上传拦截")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = mode, onValueChange = { mode = it }, label = "防护级别 mode", options = RV_WAF_MODES)
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = status,
            onValueChange = { status = it },
            label = "拦截响应码 status",
            hint = "444 表示直接断开连接",
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "作用范围 type", options = RV_SCOPE_GLOBAL_SITE)
        Spacer(Modifier.height(Space.sm))
        HintText("file_upload 节点必须已存在于对应配置文件里，否则服务端无检查断言会 500。")
    }
}

/* ── set_file_scan_rules ── */
@Composable
private fun RvFileScanDialog(env: RvEnv, close: () -> Unit) {
    var mode by remember { mutableStateOf("2") }
    var limit by remember { mutableStateOf("300") }
    var cycle by remember { mutableStateOf("60") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "目录扫描防御",
        icon = Icons.Filled.FolderOpen,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setFileScanRules(
                mode.toIntOrNull() ?: 2,
                limit.toIntOrNull() ?: 300,
                cycle.toIntOrNull() ?: 60
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置目录扫描防御")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        DropdownField(value = mode, onValueChange = { mode = it }, label = "防护级别 mode", options = RV_WAF_MODES)
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledField(
                value = limit,
                onValueChange = { limit = it },
                label = "limit 目录数上限",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
            LabeledField(
                value = cycle,
                onValueChange = { cycle = it },
                label = "cycle 周期秒",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
        }
        Spacer(Modifier.height(Space.sm))
        HintText(
            "这个接口没有 site_id 也没有 type，只改全局 config.json 的 file_scan 节点，" +
                "改完立即对所有站点生效。",
            danger = true
        )
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 2 · CC 防护
 * 覆盖：setSmartCcRules / setCcRules / setCcRulesWait / setRetryRules / applyAll
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvCcTab(env: RvEnv) {
    val vm = env.vm
    var smartCc by remember { mutableStateOf(false) }
    var ccRules by remember { mutableStateOf(false) }
    var wait by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(false) }
    var applyAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (vm.globalRules.isEmpty()) vm.getGlobalRules { _, _ -> } }

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("CC 防护", subtitle = "基础 CC、智能 CC、等待室、攻击次数拦截与批量下发")
        }

        item {
            WafCard {
                val cc = rvMapOf(vm.globalRules["cc"])
                val na = rvMapOf(vm.globalRules["number_attacks"])
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Bolt,
                            label = "全局 CC 开关",
                            value = rvSwitchText(cc["open"]),
                            hint = "阈值 ${rvInt(cc["limit"])}",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.TrendingUp,
                            label = "攻击次数拦截",
                            value = rvSwitchText(na["open"]),
                            hint = "${rvInt(na["retry"])} 次 / ${rvInt(na["retry_cycle"])} 秒",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                SmallButton(
                    text = "刷新全局规则",
                    tone = ChipTone.INFO,
                    modifier = Modifier.fillMaxWidth()
                ) { vm.getGlobalRules { _, _ -> } }
            }
        }

        item {
            RvEntryCard(
                title = "CC 相关接口",
                subtitle = "5 项",
                entries = listOf(
                    RvEntry(
                        Icons.Filled.SmartToy, "智能 CC 开关",
                        "只切开关，阈值要去别处调",
                        ChipTone.INFO
                    ) { smartCc = true },
                    RvEntry(
                        Icons.Filled.Tune, "基础 CC 规则",
                        "8 个必传参数，type 决定写几个字段"
                    ) { ccRules = true },
                    RvEntry(
                        Icons.Filled.Schedule, "CC 等待室",
                        "会顺带改掉该站 cc.open",
                        ChipTone.WARNING
                    ) { wait = true },
                    RvEntry(
                        Icons.Filled.TrendingUp, "攻击次数拦截",
                        "type 不传会 panic",
                        ChipTone.WARNING
                    ) { retry = true },
                    RvEntry(
                        Icons.Filled.CloudQueue, "下发全局到全部站点",
                        "覆盖所有站点，无撤销入口",
                        ChipTone.DANGER
                    ) { applyAll = true }
                )
            )
        }

        item {
            WafCard {
                SectionHeader("set_cc_rules 的三个 type 差别很大", subtitle = "选错会静默丢参数")
                Spacer(Modifier.height(Space.sm))
                DataRowDivider("global", "写 config.json 的 cc 节点，影响所有站点")
                DataRowDivider("not_global", "写 site.json 的 cc 节点，7 个字段全写")
                DataRowDivider("site", "只写 open 一个字段，其余 6 个参数被丢弃")
                Spacer(Modifier.height(Space.xs))
                HintText("所以「只想开启或关闭某站 CC」用 site，「要完整配置」用 not_global。")
            }
        }
    }

    if (smartCc) RvSmartCcDialog(env) { smartCc = false }
    if (ccRules) RvCcRulesDialog(env) { ccRules = false }
    if (wait) RvWaitDialog(env) { wait = false }
    if (retry) RvRetryDialog(env) { retry = false }
    if (applyAll) RvApplyAllDialog(env) { applyAll = false }
}

/* ── set_smart_cc_rules ── */
@Composable
private fun RvSmartCcDialog(env: RvEnv, close: () -> Unit) {
    var open by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "智能 CC 开关",
        icon = Icons.Filled.SmartToy,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setSmartCcRules(env.siteId, open) { e ->
                if (e == null) {
                    close()
                    env.done("设置智能 CC 开关")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "开启智能 CC",
            checked = open,
            onChange = { open = it },
            description = "开启时服务端先查授权，数量不足返回「授权网站数量不足」"
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            "该站还没有 smart_cc 节点时，开启会用服务端默认值新建：" +
                "状态码 444、平均响应 2000 毫秒、错误数 5、过期 120 秒、QPS 10、IP 封禁 360 秒。" +
                "你先前配置过但被删掉的参数不会保留。"
        )
    }
}

/* ── set_cc_rules ── */
@Composable
private fun RvCcRulesDialog(env: RvEnv, close: () -> Unit) {
    var status by remember { mutableStateOf("444") }
    var cycle by remember { mutableStateOf("60") }
    var open by remember { mutableStateOf(true) }
    var limit by remember { mutableStateOf("120") }
    var endTime by remember { mutableStateOf("300") }
    var ccType by remember { mutableStateOf("2") }
    var type by remember { mutableStateOf("not_global") }
    var isCcUrl by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "基础 CC 规则",
        icon = Icons.Filled.Tune,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setCcRules(
                siteId = env.siteId,
                status = status.toIntOrNull() ?: 444,
                cycle = cycle.toIntOrNull() ?: 60,
                open = open,
                limit = limit.toIntOrNull() ?: 120,
                endTime = endTime.toIntOrNull() ?: 300,
                ccTypeStatus = ccType.toIntOrNull() ?: 2,
                type = type,
                isCcUrl = isCcUrl
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置 CC 规则")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        SwitchRow(title = "开启 CC 防护 open", checked = open, onChange = { open = it })
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "作用范围 type", options = RV_SCOPE_GS)
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledField(
                value = limit,
                onValueChange = { limit = it },
                label = "limit 请求上限",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
            LabeledField(
                value = cycle,
                onValueChange = { cycle = it },
                label = "cycle 周期秒",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
        }
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledField(
                value = endTime,
                onValueChange = { endTime = it },
                label = "endtime 封禁秒",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
            LabeledField(
                value = status,
                onValueChange = { status = it },
                label = "status 响应码",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
        }
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = ccType, onValueChange = { ccType = it }, label = "cc_type_status", options = RV_CC_TYPE_STATUS)
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "is_cc_url 只统计 URL 维度",
            checked = isCcUrl,
            onChange = { isCcUrl = it },
            description = "后端判空后读取，不传等价于 false；传 false 会把已有配置覆盖成 false"
        )
        Spacer(Modifier.height(Space.sm))
        HintText("type=site 时后端只写 open，其余 6 个参数全部被丢弃。")
    }
}

/* ── set_cc_rules_wait ── */
@Composable
private fun RvWaitDialog(env: RvEnv, close: () -> Unit) {
    var open by remember { mutableStateOf(true) }
    var waitOpen by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("5") }
    var qps by remember { mutableStateOf("10") }
    var user by remember { mutableStateOf("50") }
    var type by remember { mutableStateOf("default") }
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "CC 等待室",
        icon = Icons.Filled.Schedule,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setCcRulesWait(
                siteId = env.siteId,
                open = open,
                waitOpen = waitOpen,
                waitTime = time.toIntOrNull() ?: 5,
                waitQps = qps.toIntOrNull() ?: 10,
                waitUser = user.toIntOrNull() ?: 50,
                waitType = type,
                waitText = text
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置 CC 等待室")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "该站 CC 防护总开关 open",
            checked = open,
            onChange = { open = it },
            description = "这个接口会顺手把该站 cc.open 一起改掉"
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow(title = "启用等待室 wait.open", checked = waitOpen, onChange = { waitOpen = it })
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledField(
                value = time,
                onValueChange = { time = it },
                label = "等待时间 time",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
            LabeledField(
                value = user,
                onValueChange = { user = it },
                label = "并发用户 user",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
        }
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = qps,
            onValueChange = { qps = it },
            label = "QPS 上限 qps",
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "等待室类型 type", options = RV_TEXT_TYPES)
        if (type == "text") {
            Spacer(Modifier.height(Space.sm))
            LabeledField(value = text, onValueChange = { text = it }, label = "自定义提示文本", singleLine = false)
        }
        Spacer(Modifier.height(Space.sm))
        HintText(
            "wait 对象的六个键都被服务端无检查断言，time / qps / user 必须是 JSON 数字，" +
                "传字符串会 500。服务端校验：time 不小于 1、user 不小于 1、qps 不小于 0。"
        )
    }
}

/* ── set_retry_rules ── */
@Composable
private fun RvRetryDialog(env: RvEnv, close: () -> Unit) {
    var cycle by remember { mutableStateOf("60") }
    var retry by remember { mutableStateOf("5") }
    var retryTime by remember { mutableStateOf("300") }
    var type by remember { mutableStateOf("not_global") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "攻击次数拦截",
        icon = Icons.Filled.TrendingUp,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setRetryRules(
                env.siteId,
                cycle.toIntOrNull() ?: 60,
                retry.toIntOrNull() ?: 5,
                retryTime.toIntOrNull() ?: 300,
                type
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置攻击次数拦截")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = type,
            onValueChange = { type = it },
            label = "作用范围 type（必填）",
            options = RV_SCOPE_GLOBAL_NOTGLOBAL
        )
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledField(
                value = retry,
                onValueChange = { retry = it },
                label = "retry 攻击次数",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
            LabeledField(
                value = cycle,
                onValueChange = { cycle = it },
                label = "retry_cycle 周期秒",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
        }
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = retryTime,
            onValueChange = { retryTime = it },
            label = "retry_time 拦截时长秒",
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            "type 不在服务端参数校验列表里，但函数体无条件读取它 —— 漏传就是 nil 断言 500，" +
                "所以这里做成必填下拉。",
            danger = true
        )
    }
}

/* ── apply_all ── */
@Composable
private fun RvApplyAllDialog(env: RvEnv, close: () -> Unit) {
    var type by remember { mutableStateOf("cc") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "下发全局规则到所有站点",
        icon = Icons.Filled.CloudQueue,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            env.ask(
                RvConfirm(
                    title = "确认覆盖全部站点的" +
                        (if (type == "cc") " CC 防护" else "攻击次数拦截") + "配置",
                    message = "作用范围：全部站点（由服务端 siteid.json 决定），规则类别 type=$type。",
                    warning = "该操作会用全局值覆盖每一个站点的该类配置，各站点原有的差异化设置全部丢失，" +
                        "并且没有任何撤销入口。站点列表读不到时服务端返回「没有站点」，" +
                        "全局配置缺对应节点时返回「没有全局规则」。",
                    confirmText = "确认覆盖所有站点"
                ) {
                    env.vm.applyAll(type) { e ->
                        if (e == null) {
                            close()
                            env.done("下发全局规则")
                        } else {
                            err = e
                        }
                    }
                }
            )
            null
        }
    ) {
        DropdownField(
            value = type,
            onValueChange = { type = it },
            label = "下发类别 type",
            options = listOf("cc · 基础 CC 防护" to "cc", "number_attacks · 攻击次数拦截" to "number_attacks")
        )
        Spacer(Modifier.height(Space.sm))
        HintText("这个接口没有 site_id，它作用于服务端读到的全部站点。", danger = true)
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 3 · CDN
 * 覆盖：setCdnRules / setCdnHeaders
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvCdnTab(env: RvEnv) {
    val vm = env.vm
    var cdnRules by remember { mutableStateOf(false) }
    var cdnHeader by remember { mutableStateOf(false) }

    LaunchedEffect(env.siteId) { if (env.siteId.isNotBlank()) vm.getRules(env.siteId) }

    val headers = rvListOf(vm.siteRules["cdn_header"]).map { it.asString() }.filter { it.isNotBlank() }

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("CDN", subtitle = "CDN 兼容开关与回源 IP 请求头白名单")
        }

        item {
            WafCard { RvSiteField(env) }
        }

        item {
            RvEntryCard(
                title = "CDN 接口",
                subtitle = "2 项",
                entries = listOf(
                    RvEntry(
                        Icons.Filled.Cloud, "CDN 接入开关",
                        "type=site 时 cdn_baidu 会被丢弃",
                        ChipTone.WARNING
                    ) { cdnRules = true },
                    RvEntry(
                        Icons.Filled.AddLink, "CDN 回源请求头",
                        "删错会导致所有基于 IP 的防护失效",
                        ChipTone.DANGER
                    ) { cdnHeader = true }
                )
            )
        }

        item {
            WafCard {
                SectionHeader(
                    "当前 cdn_header 列表",
                    subtitle = "共 ${headers.size} 个 · 来自 get_rules 的 cdn_header 字段",
                    action = {
                        SmallButton("刷新", tone = ChipTone.INFO) { vm.getRules(env.siteId) }
                    }
                )
                Spacer(Modifier.height(Space.sm))
                if (headers.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.AddLink,
                        title = "还没有配置任何回源请求头",
                        description = "站点配置里没有 cdn_header 节点时，新增会返回「cdn_header不存在」"
                    )
                } else {
                    headers.forEach { h -> DataRowDivider(h, "CDN 回源时携带此头") }
                }
            }
        }
    }

    if (cdnRules) RvCdnRulesDialog(env) { cdnRules = false }
    if (cdnHeader) RvCdnHeaderDialog(env, headers) { cdnHeader = false }
}

@Composable
private fun RvCdnRulesDialog(env: RvEnv, close: () -> Unit) {
    var cdn by remember { mutableStateOf(true) }
    var baidu by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("not_global") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "CDN 接入开关",
        icon = Icons.Filled.Cloud,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setCdnRules(env.siteId, cdn, baidu, type) { e ->
                if (e == null) {
                    close()
                    env.done("设置 CDN 开关")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = type,
            onValueChange = { type = it },
            label = "作用范围 type",
            options = RV_SCOPE_NOTGLOBAL_SITE
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow(title = "cdn 开启 CDN 兼容取 IP", checked = cdn, onChange = { cdn = it })
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "cdn_baidu 百度 CDN 兼容",
            checked = baidu,
            onChange = { baidu = it },
            description = "type=site 时后端只写 cdn，cdn_baidu 被丢弃"
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            "开启 CDN 后 WAF 取真实客户端 IP 的方式会改变。这是本模块少数会重新生成整份" +
                "站点配置文件的接口，回写失败时返回「站点配置生成失败」。",
            danger = true
        )
    }
}

@Composable
private fun RvCdnHeaderDialog(env: RvEnv, current: List<String>, close: () -> Unit) {
    var header by remember { mutableStateOf("x-forwarded-for") }
    var type by remember { mutableStateOf("add") }
    var err by remember { mutableStateOf<String?>(null) }
    fun submit() {
        env.vm.setCdnHeaders(env.siteId, header.trim(), type) { e ->
            if (e == null) {
                close()
                env.done(if (type == "add") "新增 CDN 请求头" else "删除 CDN 请求头")
            } else {
                err = e
            }
        }
    }
    FormDialog(
        title = "CDN 回源请求头",
        icon = Icons.Filled.AddLink,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            if (type == "del") {
                env.ask(
                    RvConfirm(
                        title = "确认删除请求头 $header",
                        message = "site_id=${env.siteId}，请求头 $header。",
                        warning = "删错回源请求头会导致 WAF 取不到真实客户端 IP，" +
                            "进而所有基于 IP 的防护（CC、地区限制、恶意 IP 库、IDC 限制）同时失效。" +
                            "删一个不存在的头服务端不会报错，会静默返回成功。",
                        confirmText = "确认删除"
                    ) { submit() }
                )
            } else {
                submit()
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = header,
            onValueChange = { header = it },
            label = "请求头名 cdn_header",
            hint = "单个头名，例如 x-forwarded-for"
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "操作 type", options = RV_ADD_DEL)
        Spacer(Modifier.height(Space.sm))
        if (current.isNotEmpty()) {
            Text(
                "已配置：${current.joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.sm))
        }
        HintText(
            "站点配置里没有 cdn_header 节点时返回「cdn_header不存在」；" +
                "重复添加同名头返回「该cdn_header已存在」。"
        )
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 4 · HTTP 限制
 * 覆盖：getHttpMethodTypeRules / setHttpMethodTypeRules /
 *      getHttpHeaderLenRules / addHttpHeaderLenRules /
 *      modifyHttpHeaderLenRules / delHttpHeaderLenRules / setSingleUri
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvHttpTab(env: RvEnv) {
    val vm = env.vm
    var addHeader by remember { mutableStateOf(false) }
    var editHeader by remember { mutableStateOf<String?>(null) }
    var uriOp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (vm.methodTypeRules.isEmpty()) vm.getHttpMethodTypeRules()
        if (vm.headerLenRules.isEmpty()) vm.getHttpHeaderLenRules()
        if (vm.globalRules.isEmpty()) vm.getGlobalRules { _, _ -> }
    }

    val methodKeys = if (vm.methodTypeRules.isNotEmpty()) {
        vm.methodTypeRules.keys.sorted()
    } else {
        RV_METHOD_FALLBACK
    }
    val headerKeys = vm.headerLenRules.keys.sorted()
    val uriRules = rvUriFreqRules(vm.globalRules)

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("HTTP 限制", subtitle = "请求方法开关、请求头长度上限、单 URL 频率限制")
        }

        item {
            WafCard {
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.SwapHoriz,
                            label = "方法开关",
                            value = vm.methodTypeRules.size.toString(),
                            hint = "method_type 节点",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Compress,
                            label = "请求头上限",
                            value = headerKeys.size.toString(),
                            hint = "header_len 节点",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Link,
                            label = "单 URL 频率",
                            value = uriRules.size.toString(),
                            hint = "cc_uri_frequency",
                            accent = MaterialTheme.colorScheme.secondary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Warning,
                            label = "空配置提示",
                            value = if (vm.emptyNotice.isNullOrBlank()) "无" else "有",
                            hint = vm.emptyNotice ?: "配置已初始化",
                            accent = if (vm.emptyNotice.isNullOrBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        }

        item {
            WafCard {
                SectionHeader(
                    "HTTP 方法开关",
                    subtitle = "get / set_http_method_type_rules",
                    action = { SmallButton("刷新", tone = ChipTone.INFO) { vm.getHttpMethodTypeRules() } }
                )
                Spacer(Modifier.height(Space.sm))
                if (vm.methodTypeRules.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.SwapHoriz,
                        title = "method_type 节点尚未初始化",
                        description = vm.emptyNotice
                            ?: "服务端在配置里找不到该节点时返回字符串「获取数据为空」，不是空对象。",
                        actionText = "重新读取",
                        onAction = { vm.getHttpMethodTypeRules() }
                    )
                } else {
                    methodKeys.forEach { k ->
                        SwitchRow(
                            title = k,
                            checked = vm.methodTypeRules[k].toBoolState(),
                            onChange = { nv ->
                                env.act("修改 $k 方法开关") { cb ->
                                    vm.setHttpMethodTypeRules(k, nv, cb)
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "方法名不在配置里时后端会新增一个键而不是报错；配置里没有 method_type 节点时" +
                        "整个写入被跳过，却仍然返回「操作成功」。"
                )
            }
        }

        item {
            WafCard {
                SectionHeader(
                    "请求头长度上限",
                    subtitle = "get / add / modify / del_http_header_len_rules",
                    action = { SmallButton("新增", tone = ChipTone.INFO) { addHeader = true } }
                )
                Spacer(Modifier.height(Space.sm))
                if (headerKeys.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Compress,
                        title = "header_len 节点尚未初始化",
                        description = vm.emptyNotice
                            ?: "服务端在配置里找不到该节点时返回字符串「获取数据为空」。" +
                            "节点不存在时新增会触发服务端无检查断言返回 500，所以先刷新确认。",
                        actionText = "重新读取",
                        onAction = { vm.getHttpHeaderLenRules() }
                    )
                } else {
                    headerKeys.forEach { k ->
                        ListRow(
                            icon = Icons.Filled.Compress,
                            title = k,
                            subtitle = "上限 ${rvInt(vm.headerLenRules[k])} 字节",
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            iconSize = 34.dp,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    SmallButton("编辑", tone = ChipTone.INFO) { editHeader = k }
                                    SmallButton("删除", tone = ChipTone.DANGER) {
                                        env.ask(
                                            RvConfirm(
                                                title = "确认删除请求头长度限制 $k",
                                                message = "将从全局 header_len 节点移除 $k 的长度上限。",
                                                warning = "删掉某个头的长度上限等于取消该头的长度检查，会放宽防护。" +
                                                    "另外服务端删一个不存在的头不报错，会静默返回成功，" +
                                                    "删除后请重新读取列表确认。",
                                                confirmText = "确认删除"
                                            ) {
                                                env.act("删除请求头长度限制") { cb ->
                                                    vm.delHttpHeaderLenRules(k, cb)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        item {
            WafCard {
                SectionHeader(
                    "单 URL 频率限制",
                    subtitle = "set_single_uri · 现有规则来自 get_global_rules 的 cc_uri_frequency",
                    action = { SmallButton("新增 / 编辑", tone = ChipTone.INFO) { uriOp = true } }
                )
                Spacer(Modifier.height(Space.sm))
                if (uriRules.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Link,
                        title = "还没有单 URL 频率规则",
                        description = "cc_uri_frequency 没有独立查询接口，只能从全局规则返回值里读"
                    )
                } else {
                    uriRules.forEach { (u, conf) ->
                        ListRow(
                            icon = Icons.Filled.Link,
                            title = u,
                            subtitle = "周期 ${rvInt(conf["cycle"])} 秒内超过 ${rvInt(conf["frequency"])} 次",
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconSize = 34.dp,
                            trailing = {
                                SmallButton("管理", tone = ChipTone.INFO) { uriOp = true }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "这个接口的返回值是布尔值 true，不是文案，界面不把它当提示语展示。" +
                        "另外服务端有 bug：cc_uri_frequency 节点不存在时，无论 type 是什么" +
                        "（包括 del）都会新建该节点，也就是「删除」不存在的规则反而会把它创建出来。",
                    danger = true
                )
            }
        }
    }

    if (addHeader) {
        RvHeaderLenDialog(env, null) { addHeader = false }
    }
    editHeader?.let { k ->
        RvHeaderLenDialog(env, k) { editHeader = null }
    }
    if (uriOp) {
        RvSingleUriDialog(env, uriRules.firstOrNull()?.first ?: "") { uriOp = false }
    }
}

@Composable
private fun RvHeaderLenDialog(env: RvEnv, editKey: String?, close: () -> Unit) {
    val isEdit = editKey != null
    var name by remember { mutableStateOf(editKey ?: "") }
    var length by remember {
        mutableStateOf(if (isEdit) rvInt(env.vm.headerLenRules[editKey]).toString() else "10000")
    }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = if (isEdit) "修改请求头长度上限" else "新增请求头长度上限",
        icon = Icons.Filled.Compress,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            val n = length.toIntOrNull() ?: 0
            val cb: (String?) -> Unit = { e ->
                if (e == null) {
                    close()
                    env.done(if (isEdit) "修改请求头长度上限" else "新增请求头长度上限")
                } else {
                    err = e
                }
            }
            if (isEdit) {
                env.vm.modifyHttpHeaderLenRules(name.trim(), n, cb)
            } else {
                env.vm.addHttpHeaderLenRules(name.trim(), n, cb)
            }
            null
        }
    ) {
        LabeledField(value = name, onValueChange = { name = it }, label = "请求头名 type（小写）")
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = length,
            onValueChange = { length = it },
            label = "长度上限 length",
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            if (isEdit) {
                "与服务端 add 不同，modify 一个不存在的 type 不会报错，会直接新增该键。"
            } else {
                "type 已存在时返回「该类型已存在」。节点不存在时服务端无检查断言会 500。"
            }
        )
    }
}

@Composable
private fun RvSingleUriDialog(env: RvEnv, preset: String, close: () -> Unit) {
    var uri by remember { mutableStateOf(preset) }
    var cycle by remember { mutableStateOf("60") }
    var freq by remember { mutableStateOf("100") }
    var type by remember { mutableStateOf("add") }
    var err by remember { mutableStateOf<String?>(null) }
    val existing = rvUriFreqRules(env.vm.globalRules)
    FormDialog(
        title = "单 URL 频率限制",
        icon = Icons.Filled.Link,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            val cb: (String?) -> Unit = { e ->
                if (e == null) {
                    close()
                    env.done("设置单 URL 频率规则")
                } else {
                    err = e
                }
            }
            if (type == "del") {
                env.ask(
                    RvConfirm(
                        title = "确认删除 $uri 的频率限制",
                        message = "将从全局 cc_uri_frequency 节点移除 $uri。",
                        warning = "服务端在 cc_uri_frequency 节点不存在时，无论 type 是什么都会新建该节点，" +
                            "所以「删除」一个本来不存在的规则反而会把它创建出来。",
                        confirmText = "确认删除"
                    ) {
                        env.vm.setSingleUri(uri.trim(), cycle.toIntOrNull() ?: 60, freq.toIntOrNull() ?: 100, "del", cb)
                    }
                )
            } else {
                env.vm.setSingleUri(uri.trim(), cycle.toIntOrNull() ?: 60, freq.toIntOrNull() ?: 100, type, cb)
            }
            null
        }
    ) {
        LabeledField(value = uri, onValueChange = { uri = it }, label = "URI 路径", hint = "以 / 开头")
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            LabeledField(
                value = cycle,
                onValueChange = { cycle = it },
                label = "统计周期 cycle 秒",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
            LabeledField(
                value = freq,
                onValueChange = { freq = it },
                label = "次数上限 frequency",
                modifier = Modifier.weight(1f),
                keyboardType = KeyboardType.Number
            )
        }
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "操作 type", options = RV_URI_OPS)
        Spacer(Modifier.height(Space.sm))
        if (existing.isNotEmpty()) {
            Text(
                "现有路径：${existing.joinToString("、") { it.first }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.sm))
        }
        HintText("type=del 时后端仍然要求 uri / cycle / frequency 三个键都存在，所以这里保持它们可填。")
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 5 · 爬虫与模拟攻击
 * 覆盖：getSpiderRules / updateSpiderRules / setCrawlerInfos / simulatedAttack
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvSpiderTab(env: RvEnv) {
    val vm = env.vm
    val clipboard = LocalClipboardManager.current
    var spiderType by remember { mutableStateOf("1") }
    var crawler by remember { mutableStateOf(false) }
    var spiderErr by remember { mutableStateOf<String?>(null) }
    var attackErr by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("爬虫与模拟攻击", subtitle = "蜘蛛池 UA 片段、爬虫加密水印、SQL 注入模拟 URL")
        }

        item {
            WafCard {
                SectionHeader("蜘蛛池", subtitle = "get_spider_rules · 编号 1 到 100")
                Spacer(Modifier.height(Space.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabeledField(
                        value = spiderType,
                        onValueChange = { spiderType = it.filter { c -> c.isDigit() } },
                        label = "spider_type",
                        modifier = Modifier.weight(1f),
                        keyboardType = KeyboardType.Number
                    )
                    Spacer(Modifier.width(Space.sm))
                    SmallButton("读取", tone = ChipTone.INFO) {
                        val t = spiderType.toIntOrNull()
                        if (t == null) {
                            spiderErr = "spider_type 必须是 1 到 100 的整数"
                        } else {
                            spiderErr = null
                            vm.getSpiderRules(t) { e -> if (e != null) spiderErr = e }
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                ErrorBanner(spiderErr)
                Spacer(Modifier.height(Space.sm))
                if (vm.spiderRule.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.TravelExplore,
                        title = "还没有读取蜘蛛池",
                        description = "服务端先校验编号在 1 到 100 之间，再把编号拼成文件路径读取，" +
                            "所以必须传字符串，传数字会 panic。"
                    )
                } else {
                    DataRow("UA 片段条数", vm.spiderRule.size.toString())
                    Spacer(Modifier.height(Space.sm))
                    vm.spiderRule.take(50).forEachIndexed { i, ua ->
                        DataRowDivider("#${i + 1}", ua)
                    }
                    if (vm.spiderRule.size > 50) {
                        Text(
                            "另有 ${vm.spiderRule.size - 50} 条未显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        item {
            WafCard {
                SectionHeader("从官方源更新蜘蛛池", subtitle = "update_spider_rules")
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "这个接口对 1 到 8 号蜘蛛池逐个访问官方接口拉取 UA 片段，与本地文件取并集后覆写。" +
                        "循环里所有错误（网络、读取、解析、写文件）都是 continue 跳过，" +
                        "函数结尾无条件返回「操作成功」—— 完全没更新成功界面也一样显示成功。" +
                        "它不备份配置，内网环境必然拿不到新数据。",
                    danger = true
                )
                Spacer(Modifier.height(Space.md))
                PrimaryButton(
                    text = "拉取官方 UA 片段并合并",
                    loading = vm.saving,
                    onClick = {
                        env.ask(
                            RvConfirm(
                                title = "确认更新蜘蛛池",
                                message = "将向官方接口请求 1 到 8 号蜘蛛池的 UA 片段。",
                                warning = "该接口全程吞错：即使一条都没更新成功也会返回「操作成功」。" +
                                    "唯一的验证方式是重新读取蜘蛛池对比条目数量。",
                                confirmText = "确认拉取"
                            ) {
                                env.vm.updateSpiderRules { e ->
                                    if (e == null) {
                                        env.note(
                                            RvNotice(
                                                "已提交",
                                                "服务端已返回成功，但它在流程里吞掉了所有错误。" +
                                                    "请重新读取蜘蛛池对比条目数量，确认是否真的变化。"
                                            )
                                        )
                                    } else {
                                        env.note(RvNotice("更新失败", e, danger = true))
                                    }
                                }
                            }
                        )
                    }
                )
            }
        }

        item {
            RvEntryCard(
                title = "爬虫处理",
                subtitle = "1 项",
                entries = listOf(
                    RvEntry(
                        Icons.Filled.Fingerprint, "爬虫加密与水印",
                        "整块覆盖，不传全三个键会清掉已有配置",
                        ChipTone.WARNING
                    ) { crawler = true }
                )
            )
        }

        item {
            WafCard {
                SectionHeader(
                    "SQL 注入模拟攻击 URL",
                    subtitle = "simulated_attack · 为每个站点拼一条注入 URL",
                    action = {
                        SmallButton("生成", tone = ChipTone.INFO) {
                            attackErr = null
                            vm.simulatedAttack { e -> if (e != null) attackErr = e }
                        }
                    }
                )
                Spacer(Modifier.height(Space.sm))
                ErrorBanner(attackErr)
                Spacer(Modifier.height(Space.sm))
                if (vm.simulatedUrls.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Bolt,
                        title = "还没有生成模拟攻击 URL",
                        description = "服务端读不到域名清单时返回「没有站点」。" +
                            "这些 URL 是攻击样例，界面上只提供复制，不做成可点击跳转。"
                    )
                } else {
                    vm.simulatedUrls.forEach { u ->
                        ListRow(
                            icon = Icons.Filled.Bolt,
                            title = u,
                            subtitle = "点击复制到剪贴板",
                            iconTint = MaterialTheme.colorScheme.error,
                            iconSize = 34.dp,
                            onClick = { clipboard.setText(AnnotatedString(u)) }
                        )
                    }
                }
            }
        }
    }

    if (crawler) RvCrawlerDialog(env) { crawler = false }
}

@Composable
private fun RvCrawlerDialog(env: RvEnv, close: () -> Unit) {
    val current = rvMapOf(rvMapOf(env.vm.siteRules)["crawler"])
    val enc = rvMapOf(current["encryption"])
    val wm = rvMapOf(current["watermark"])
    var encOpen by remember { mutableStateOf(enc["open"].toBoolState()) }
    var encType by remember { mutableStateOf(enc["type"]?.asString()?.ifBlank { "default" } ?: "default") }
    var encText by remember { mutableStateOf(enc["text"]?.asString() ?: "") }
    var wmOpen by remember { mutableStateOf(wm["open"].toBoolState()) }
    var wmType by remember { mutableStateOf(wm["type"]?.asString()?.ifBlank { "default" } ?: "default") }
    var wmText by remember { mutableStateOf(wm["text"]?.asString() ?: "") }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(env.siteId) { if (env.siteId.isNotBlank()) env.vm.getRules(env.siteId) }

    FormDialog(
        title = "爬虫加密与水印",
        icon = Icons.Filled.Fingerprint,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.setCrawlerInfos(
                siteId = env.siteId,
                encryptionOpen = encOpen,
                encryptionType = encType,
                encryptionText = encText,
                watermarkOpen = wmOpen,
                watermarkType = wmType,
                watermarkText = wmText
            ) { e ->
                if (e == null) {
                    close()
                    env.done("设置爬虫加密与水印")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        Text("加密", style = MaterialTheme.typography.titleSmall)
        SwitchRow(title = "开启加密", checked = encOpen, onChange = { encOpen = it })
        Spacer(Modifier.height(Space.xs))
        DropdownField(value = encType, onValueChange = { encType = it }, label = "加密类型", options = RV_TEXT_TYPES)
        if (encType == "text") {
            Spacer(Modifier.height(Space.sm))
            LabeledField(value = encText, onValueChange = { encText = it }, label = "加密文本", singleLine = false)
        }
        Spacer(Modifier.height(Space.md))
        Text("水印", style = MaterialTheme.typography.titleSmall)
        SwitchRow(title = "开启水印", checked = wmOpen, onChange = { wmOpen = it })
        Spacer(Modifier.height(Space.xs))
        DropdownField(value = wmType, onValueChange = { wmType = it }, label = "水印类型", options = RV_TEXT_TYPES)
        if (wmType == "text") {
            Spacer(Modifier.height(Space.sm))
            LabeledField(value = wmText, onValueChange = { wmText = it }, label = "水印文本", singleLine = false)
        }
        Spacer(Modifier.height(Space.sm))
        HintText(
            "这是整块覆盖：服务端直接替换站点的 crawler 节点，encryption 与 watermark " +
                "各含 open / type / text 三个键，三个都被无检查断言，一个都不能少，" +
                "给 open 传字符串会 500。",
            danger = true
        )
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 6 · 区域拦截
 * 覆盖：getRegionalRestrictions / getRegionalRestrictionsRules /
 *      addRegionalRestrictionsRules / setRegionalRestrictionsRules /
 *      delRegionalRestrictionsRules / resetRegionalCountryRules
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvRegionTab(env: RvEnv) {
    val vm = env.vm
    var search by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var add by remember { mutableStateOf(false) }
    var delIds by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (vm.regionCatalogRaw == null) vm.getRegionalRestrictions()
        if (vm.regionRules.isEmpty()) vm.getRegionalRestrictionsRules()
    }

    val catalog = rvRegionNames(vm.regionCatalogRaw)
    val totalPages = if (vm.regionRuleTotal <= 0) 1 else (vm.regionRuleTotal + RV_PAGE_SIZE - 1) / RV_PAGE_SIZE

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("区域拦截", subtitle = "国家 / 地区字典与地区限制规则")
        }

        item {
            WafCard {
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Public,
                            label = "地区字典",
                            value = catalog.size.toString(),
                            hint = "get_regional_restrictions",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Rule,
                            label = "规则条数",
                            value = vm.regionRuleTotal.toString(),
                            hint = "第 $page / $totalPages 页",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                SmallButton(
                    text = "刷新字典",
                    tone = ChipTone.INFO,
                    modifier = Modifier.fillMaxWidth()
                ) { vm.getRegionalRestrictions() }
                Spacer(Modifier.height(Space.sm))
                if (catalog.isEmpty()) {
                    Text(
                        "尚未读取地区字典。服务端返回的是 citys.json 原样内容，" +
                            "形状随版本变化（可能是对象也可能是数组），两种都已按名字列表渲染。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        catalog.take(24).joinToString("、") + if (catalog.size > 24) " …" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            WafCard {
                SectionHeader(
                    "地区限制规则",
                    subtitle = "get_regional_restrictions_rules",
                    action = { SmallButton("新增规则", tone = ChipTone.INFO) { add = true } }
                )
                Spacer(Modifier.height(Space.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabeledField(
                        value = search,
                        onValueChange = { search = it },
                        label = "搜索",
                        hint = "地区名或站点名，或直接填 region_id",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Space.sm))
                    SmallButton("查询", tone = ChipTone.INFO) {
                        page = 1
                        vm.getRegionalRestrictionsRules(search, 1, RV_PAGE_SIZE)
                    }
                }
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "搜索不是简单的站点名模糊匹配：region_id 完全相等、地区名包含搜索串、" +
                        "站点名包含搜索串，命中任一即算匹配。",
                    danger = true
                )
                Spacer(Modifier.height(Space.sm))
                HintText(
                    "注意：服务端在读取列表时发现某条规则的站点配置已不存在，会把这条规则" +
                        "从文件里删掉并回收站点授权。也就是说「打开列表」本身会改变服务端数据。",
                    danger = true
                )
            }
        }

        if (vm.regionRules.isEmpty()) {
            item {
                WafCard {
                    EmptyState(
                        icon = Icons.Filled.Public,
                        title = "还没有地区限制规则",
                        description = "可以点上面的「新增规则」按省或按市添加一条"
                    )
                }
            }
        } else {
            itemsIndexed(vm.regionRules) { _, row ->
                val rid = row["region_id"]?.asString() ?: ""
                val regionMap = rvMapOf(row["region"])
                val type = row["types"]?.asString() ?: ""
                val siteName = row["site"]?.asString()?.ifBlank { "-" } ?: "-"
                val uriText = row["uri"]?.asString()?.takeIf { it.isNotBlank() && it != "/" }
                WafCard {
                    ListRow(
                        icon = if (type == "allow") Icons.Filled.CheckCircle else Icons.Filled.Block,
                        title = regionMap.keys.joinToString("、").ifBlank { rid },
                        subtitle = "region_id=$rid · 站点 $siteName" +
                            (uriText?.let { " · $it" } ?: ""),
                        iconTint = if (type == "allow") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        iconSize = 36.dp,
                        trailing = {
                            StatusChip(
                                if (type == "allow") "只允许" else "拦截",
                                if (type == "allow") ChipTone.SUCCESS else ChipTone.DANGER
                            )
                        }
                    )
                    Spacer(Modifier.height(Space.xs))
                    DataRow("命中次数", rvInt(row["count"]).toString())
                    DataRow("创建时间", rvUnixText(row["time"]))
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        val enabled = row["status"].toBoolState()
                        SmallButton(
                            text = if (enabled) "停用" else "启用",
                            tone = ChipTone.INFO
                        ) {
                            env.ask(
                                RvConfirm(
                                    title = "确认" + (if (enabled) "停用" else "启用") + "地区规则 $rid",
                                    message = "作用范围：${regionMap.keys.joinToString("、")}，站点 $siteName。",
                                    warning = if (enabled) {
                                        "停用后该地区将不再被这条规则处理。"
                                    } else {
                                        "启用一条「只允许」规则会让未列出的所有地区被拒绝访问；" +
                                            "启用时服务端还会查授权，数量不足返回「授权网站数量不足」。"
                                    },
                                    confirmText = "确认"
                                ) {
                                    env.act("切换地区规则状态") { cb ->
                                        vm.setRegionalRestrictionsRules(rid, !enabled, cb)
                                    }
                                }
                            )
                        }
                        SmallButton("计数清零", tone = ChipTone.WARNING) {
                            env.ask(
                                RvConfirm(
                                    title = "确认清零 $rid 的命中次数",
                                    message = "只把该规则的 count 归零，规则本身保留。",
                                    warning = "命中次数是统计数据，清零后无法恢复。",
                                    confirmText = "确认清零"
                                ) {
                                    env.act("清零地区规则计数") { cb ->
                                        vm.resetRegionalCountryRules(rid, cb)
                                    }
                                }
                            )
                        }
                        SmallButton("删除", tone = ChipTone.DANGER) { delIds = listOf(rid) }
                    }
                }
            }
        }

        item {
            WafCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SmallButton(
                        text = "上一页",
                        enabled = page > 1,
                        modifier = Modifier.weight(1f)
                    ) {
                        page -= 1
                        vm.getRegionalRestrictionsRules(search, page, RV_PAGE_SIZE)
                    }
                    SmallButton(
                        text = "下一页",
                        enabled = page < totalPages,
                        modifier = Modifier.weight(1f)
                    ) {
                        page += 1
                        vm.getRegionalRestrictionsRules(search, page, RV_PAGE_SIZE)
                    }
                }
            }
        }
    }

    if (add) RvRegionAddDialog(env, catalog) { add = false }
    if (delIds.isNotEmpty()) {
        RvRegionDelDialog(env, delIds) { delIds = emptyList() }
    }
}

@Composable
private fun RvRegionAddDialog(env: RvEnv, catalog: List<String>, close: () -> Unit) {
    var siteIds by remember { mutableStateOf("allsite") }
    var type by remember { mutableStateOf("refuse") }
    var region by remember { mutableStateOf("") }
    var addType by remember { mutableStateOf("province") }
    var uri by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "新增地区限制规则",
        icon = Icons.Filled.Public,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        onConfirm = {
            env.vm.addRegionalRestrictionsRules(
                siteIds = rvSplitList(siteIds),
                type = type,
                region = rvSplitList(region),
                addType = addType,
                uri = uri.trim()
            ) { e ->
                if (e == null) {
                    close()
                    env.done("新增地区限制规则")
                } else {
                    err = e
                }
            }
            null
        }
    ) {
        LabeledField(
            value = siteIds,
            onValueChange = { siteIds = it },
            label = "站点 site_ids",
            hint = "填 allsite 表示全部网站"
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = type, onValueChange = { type = it }, label = "限制方式 type", options = RV_REGION_RULE_TYPES)
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = addType,
            onValueChange = { addType = it },
            label = "添加粒度 add_type",
            options = RV_REGION_ADD_TYPES
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = region,
            onValueChange = { region = it },
            label = "地区名 region",
            hint = "多个用逗号分隔",
            singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        if (addType != "site_city") {
            LabeledField(value = uri, onValueChange = { uri = it }, label = "URI 路径（可选）", hint = "以 / 开头")
            Spacer(Modifier.height(Space.sm))
        }
        if (catalog.isNotEmpty()) {
            Text(
                "字典示例：${catalog.take(10).joinToString("、")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.sm))
        }
        HintText("站点与地区都是 JSON 数组，界面已把逗号分隔输入交给 ViewModel 转成数组。")
        Spacer(Modifier.height(Space.sm))
        HintText(
            "两条硬限制：非 allsite 时只能填一个站点（服务端把数组用「, 」拼成一个字符串去查站点名，" +
                "多选必然查不到行并 panic）；add_type=site_city 只取 site_ids 的第一个元素。",
            danger = true
        )
    }
}

@Composable
private fun RvRegionDelDialog(env: RvEnv, ids: List<String>, close: () -> Unit) {
    var raw by remember { mutableStateOf(ids.joinToString(",")) }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "删除地区限制规则",
        icon = Icons.Filled.DeleteSweep,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            val list = rvSplitList(raw)
            env.ask(
                RvConfirm(
                    title = "确认删除 ${list.size} 条地区规则",
                    message = "region_ids=${list.joinToString("、")}。",
                    warning = "服务端会先逐个校验 region_id 是否存在，任意一个查不到就整体失败，" +
                        "一条都不会被删。删除不可撤销；若被删规则是作用于具体站点的「海外」规则，" +
                        "还会顺手删掉数据库里对应的记录。",
                    confirmText = "确认删除"
                ) {
                    env.vm.delRegionalRestrictionsRules(list) { e ->
                        if (e == null) {
                            close()
                            env.done("删除地区限制规则")
                        } else {
                            err = e
                        }
                    }
                }
            )
            null
        }
    ) {
        LabeledField(
            value = raw,
            onValueChange = { raw = it },
            label = "region_id 列表",
            hint = "多个用逗号分隔，服务端按 JSON 数组接收",
            singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        HintText("注意与 set_regional_restrictions_rules 的区别：启停传单个字符串，删除传数组。")
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 7 · 单 IP 限制
 * 覆盖：getUserLimit / addUserLimit / editUserLimit / delUserLimit / setUserLimit
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvLimitTab(env: RvEnv) {
    val vm = env.vm
    var add by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (vm.userLimits.isEmpty()) vm.getUserLimit() }

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("单 IP 访问限制", subtitle = "限流规则的增删改查与开关切换")
        }

        item {
            WafCard {
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.PersonOutline,
                            label = "规则总数",
                            value = vm.userLimits.size.toString(),
                            hint = "get_user_limit",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Speed,
                            label = "已开启",
                            value = vm.userLimits.count { it["open"].toBoolState() }.toString(),
                            hint = "open 为 true",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.height(Space.md))
                PrimaryButton(text = "新增限流规则", onClick = { add = true })
                Spacer(Modifier.height(Space.sm))
                SmallButton(
                    text = "刷新列表",
                    tone = ChipTone.INFO,
                    modifier = Modifier.fillMaxWidth()
                ) { vm.getUserLimit() }
            }
        }

        item {
            WafCard {
                HintText(
                    "set_user_limit 的语义是「取反」而不是「设成指定值」，所以这里只提供切换按钮。" +
                        "文件读不出来、解析失败或 id 不存在时，服务端会返回 code=0 与字符串" +
                        "「没有此条规则」—— 界面会把这句原文原样展示出来。",
                    danger = true
                )
            }
        }

        if (vm.userLimits.isEmpty()) {
            item {
                WafCard {
                    EmptyState(
                        icon = Icons.Filled.PersonOutline,
                        title = "还没有限流规则",
                        description = "服务端在文件不存在或读取失败时返回空数组，不是错误"
                    )
                }
            }
        } else {
            itemsIndexed(vm.userLimits) { _, row ->
                val id = row["id"]?.asString() ?: ""
                val ruleName = row["name"]?.asString()?.ifBlank { "未命名规则" } ?: "未命名规则"
                val isUrl = row["types"]?.asString() == "url"
                val urlText = if (isUrl) (row["url"]?.asString()?.ifBlank { "/" } ?: "/") else "全站"
                val enabled = row["open"].toBoolState()
                WafCard {
                    ListRow(
                        icon = Icons.Filled.Speed,
                        title = ruleName,
                        subtitle = "${rvLimitSiteText(row["site"])} · $urlText",
                        iconTint = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        iconSize = 36.dp,
                        trailing = {
                            StatusChip(
                                if (enabled) "已开启" else "已关闭",
                                if (enabled) ChipTone.SUCCESS else ChipTone.NEUTRAL
                            )
                        }
                    )
                    Spacer(Modifier.height(Space.xs))
                    DataRow("限制对象", rvLimitConditionText(row["condition"]))
                    DataRow("限制方式", rvLimitRegionText(row["region"]))
                    DataRow("命中动作", rvLimitActionText(row["action"]?.asString() ?: ""))
                    DataRow("返回形式", rvLimitReturnText(row["return"]?.asString() ?: ""))
                    DataRow("规则 id", id)
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton(text = if (enabled) "关闭" else "开启", tone = ChipTone.INFO) {
                            env.ask(
                                RvConfirm(
                                    title = "确认切换限流规则开关",
                                    message = "规则「$ruleName」。",
                                    warning = "服务端这个接口是取反语义，不是设成指定值，" +
                                        "所以连续点两次等于没改。",
                                    confirmText = "确认切换"
                                ) {
                                    env.act("切换限流规则开关", checkLastMessage = true) { cb ->
                                        vm.setUserLimit(id, cb)
                                    }
                                }
                            )
                        }
                        SmallButton("编辑", tone = ChipTone.INFO) { editId = id }
                        SmallButton("删除", tone = ChipTone.DANGER) {
                            env.ask(
                                RvConfirm(
                                    title = "确认删除限流规则",
                                    message = "规则「$ruleName」，id=$id。",
                                    warning = "删除不可撤销。误删会让该限流规则立即失效，" +
                                        "可能导致站点被刷或者正常用户被拦。" +
                                        "另外服务端在 id 不存在时返回 code=0 与「没有此条规则」。",
                                    confirmText = "确认删除"
                                ) {
                                    env.act("删除限流规则", checkLastMessage = true) { cb ->
                                        vm.delUserLimit(id, cb)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (add) RvLimitFormDialog(env, null) { add = false }
    editId?.let { id ->
        RvLimitFormDialog(env, id) { editId = null }
    }
}

@Composable
private fun RvLimitFormDialog(env: RvEnv, editId: String?, close: () -> Unit) {
    val isEdit = editId != null
    val src = env.vm.userLimits.firstOrNull { it["id"]?.asString() == editId }
    val srcRegion = rvMapOf(src?.get("region"))
    val srcCount = rvMapOf(srcRegion["count"])

    var name by remember { mutableStateOf(src?.get("name")?.asString() ?: "") }
    var site by remember {
        mutableStateOf(rvMapOf(src?.get("site")).keys.joinToString(",").ifBlank { "allsite" })
    }
    var types by remember { mutableStateOf(src?.get("types")?.asString() ?: "all") }
    var url by remember { mutableStateOf(src?.get("url")?.asString() ?: "/") }
    var condition by remember { mutableStateOf(rvInt(src?.get("condition")).toString()) }
    var regionMode by remember { mutableStateOf(if (srcRegion.containsKey("count")) "count" else "req") }
    var req by remember { mutableStateOf(rvInt(srcRegion["req"]).toString()) }
    var countTime by remember { mutableStateOf(rvInt(srcCount["time"]).toString()) }
    var countNum by remember { mutableStateOf(rvInt(srcCount["count"]).toString()) }
    var action by remember { mutableStateOf(src?.get("action")?.asString() ?: "status") }
    var returnType by remember { mutableStateOf(src?.get("return")?.asString()?.ifBlank { "html" } ?: "html") }
    var err by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val region: Map<String, Any?> = if (regionMode == "req") {
            mapOf("req" to (req.toIntOrNull() ?: 0))
        } else {
            mapOf(
                "count" to mapOf(
                    "time" to (countTime.toIntOrNull() ?: 0),
                    "count" to (countNum.toIntOrNull() ?: 0)
                )
            )
        }
        val cb: (String?) -> Unit = { e ->
            if (e == null) {
                val msg = env.vm.lastMessage
                close()
                if (!msg.isNullOrBlank() && msg.contains("没有此条规则")) {
                    env.note(RvNotice("未生效", "服务端返回：$msg", danger = true))
                } else {
                    env.done(if (isEdit) "修改限流规则" else "新增限流规则")
                }
            } else {
                err = e
            }
        }
        if (isEdit) {
            env.vm.editUserLimit(
                id = editId.orEmpty(),
                name = name.trim(),
                siteIds = rvSplitList(site),
                types = types,
                url = url.trim(),
                condition = condition.toIntOrNull() ?: 1,
                region = region,
                action = action,
                returnType = returnType,
                onResult = cb
            )
        } else {
            env.vm.addUserLimit(
                name = name.trim(),
                siteIds = rvSplitList(site),
                types = types,
                url = url.trim(),
                condition = condition.toIntOrNull() ?: 1,
                region = region,
                action = action,
                returnType = returnType,
                onResult = cb
            )
        }
    }

    FormDialog(
        title = if (isEdit) "编辑限流规则" else "新增限流规则",
        icon = Icons.Filled.Speed,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            if (isEdit) {
                env.ask(
                    RvConfirm(
                        title = "确认覆盖这条限流规则",
                        message = "规则「$name」，id=$editId。",
                        warning = "服务端是整体覆写 limit.json，改名撞上其他规则时返回" +
                            "「名称已经被其他规则使用」。id 不存在时它会返回 code=0 与" +
                            "「没有此条规则」，也就是提示成功但实际没改。",
                        confirmText = "确认修改"
                    ) { submit() }
                )
            } else {
                env.ask(
                    RvConfirm(
                        title = "确认新增限流规则",
                        message = "规则「$name」，站点 $site。",
                        warning = "高危数据风险：当 limit.json 读取或反序列化失败时，服务端会直接用" +
                            "「只含本次新规则」的数组覆盖整个文件，等于清空已有的全部限流规则。" +
                            "提交前建议先刷新列表确认文件可读。",
                        confirmText = "确认新增"
                    ) { submit() }
                )
            }
            null
        }
    ) {
        LabeledField(
            value = name,
            onValueChange = { name = it },
            label = "规则名称 name",
            hint = "不能含尖括号或双引号"
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = site,
            onValueChange = { site = it },
            label = "站点 site",
            hint = "多个站点用逗号分隔；全部网站填 allsite"
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = types, onValueChange = { types = it }, label = "路径类型 types", options = RV_LIMIT_TYPES)
        Spacer(Modifier.height(Space.sm))
        if (types == "url") {
            LabeledField(
                value = url,
                onValueChange = { url = it },
                label = "URL 路径",
                hint = "必须以 / 开头，且不能是 /"
            )
        } else {
            HintText("types=all 时服务端会把 url 强制改成 /。")
        }
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = condition.ifBlank { "1" },
            onValueChange = { condition = it },
            label = "限制对象 condition",
            options = RV_LIMIT_CONDITIONS
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = regionMode,
            onValueChange = { regionMode = it },
            label = "限制方式",
            options = listOf("req · 每秒请求数" to "req", "count · 时间窗口次数" to "count")
        )
        Spacer(Modifier.height(Space.sm))
        if (regionMode == "req") {
            LabeledField(
                value = req.ifBlank { "10" },
                onValueChange = { req = it },
                label = "每秒请求数 req",
                keyboardType = KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            HintText("每秒访问限制的请求数不能小于 1。")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                LabeledField(
                    value = countTime.ifBlank { "60" },
                    onValueChange = { countTime = it },
                    label = "时间窗口 time 秒",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number
                )
                LabeledField(
                    value = countNum.ifBlank { "100" },
                    onValueChange = { countNum = it },
                    label = "窗口内次数 count",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number
                )
            }
            Spacer(Modifier.height(Space.sm))
            HintText("时间访问限制：time 不能小于 30 秒，count 不能小于 20 次。")
        }
        Spacer(Modifier.height(Space.sm))
        DropdownField(value = action, onValueChange = { action = it }, label = "命中动作 action", options = RV_LIMIT_ACTIONS)
        Spacer(Modifier.height(Space.sm))
        if (action == "content") {
            LabeledField(
                value = returnType,
                onValueChange = { returnType = it },
                label = "返回内容 return",
                hint = "自定义返回内容，最终值必须是 html / json / 444"
            )
        } else {
            HintText(
                if (action == "status") {
                    "action=status 时 return 决定返回的状态码，取 html / json / 444。"
                } else {
                    "action 为 drop 与四种 status_xxx 时，服务端会把 return 强制改成 html。"
                }
            )
            Spacer(Modifier.height(Space.sm))
            DropdownField(
                value = returnType,
                onValueChange = { returnType = it },
                label = "返回形式 return",
                options = RV_LIMIT_RETURNS
            )
        }
        Spacer(Modifier.height(Space.sm))
        HintText("限制方式 region 是 JSON 对象，两个键都不传时服务端返回「请选择一个限制方式」。")
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 8 · URL 改写
 * 覆盖：setRewriteUrl / delRewriteUrl
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvRewriteTab(env: RvEnv) {
    val vm = env.vm
    var add by remember { mutableStateOf(false) }
    var delTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(env.siteId) { if (env.siteId.isNotBlank()) vm.getRules(env.siteId) }

    val rules = rvMapOf(vm.siteRules["rewrite_url"])

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("URL 改写", subtitle = "把一个对外路径重写到另一个地址")
        }

        item {
            WafCard { RvSiteField(env) }
        }

        item {
            WafCard {
                HintText(
                    "存储方向与入参名字相反：服务端写入的是「目标 URL → 原 URL」，" +
                        "也就是 map 的键是重写后的地址、值是用户访问的原地址。" +
                        "所以下面列表按「访问 X 会得到 Y」来读。",
                    danger = true
                )
            }
        }

        item {
            WafCard {
                SectionHeader(
                    "改写规则",
                    subtitle = "共 ${rules.size} 条 · 每站最多 5 条",
                    action = { SmallButton("新增", tone = ChipTone.INFO) { add = true } }
                )
                Spacer(Modifier.height(Space.sm))
                if (rules.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Link,
                        title = "还没有 URL 改写规则",
                        description = "每站最多 5 条，已有 5 条时再提交返回「只允许五个url重写」"
                    )
                } else {
                    rules.forEach { (target, origin) ->
                        val originText = origin.asString()
                        ListRow(
                            icon = Icons.Filled.Link,
                            title = "访问 $target",
                            subtitle = "实际返回 $originText",
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconSize = 34.dp,
                            trailing = {
                                SmallButton("删除", tone = ChipTone.DANGER) {
                                    delTarget = target to originText
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (add) RvRewriteAddDialog(env) { add = false }
    delTarget?.let { pair ->
        RvRewriteDelDialog(env, pair.first, pair.second) { delTarget = null }
    }
}

@Composable
private fun RvRewriteAddDialog(env: RvEnv, close: () -> Unit) {
    var oldUrl by remember { mutableStateOf("") }
    var rewriteUrl by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "新增 URL 改写",
        icon = Icons.Filled.Link,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            env.ask(
                RvConfirm(
                    title = "确认新增 URL 改写",
                    message = "用户访问 $rewriteUrl 时会拿到 $oldUrl 的内容。",
                    warning = "URL 改写直接改变对外访问路径，配错会导致站点 404。" +
                        "每站最多 5 条，已有 5 条时服务端返回「只允许五个url重写」。" +
                        "站点 ID 不在 site.json 里时服务端静默跳过，却仍返回成功。",
                    confirmText = "确认新增"
                ) {
                    env.vm.setRewriteUrl(env.siteId, oldUrl.trim(), rewriteUrl.trim()) { e ->
                        if (e == null) {
                            close()
                            env.done("新增 URL 改写")
                        } else {
                            err = e
                        }
                    }
                }
            )
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        LabeledField(value = oldUrl, onValueChange = { oldUrl = it }, label = "原地址 old_url", hint = "用户想访问的地址")
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = rewriteUrl,
            onValueChange = { rewriteUrl = it },
            label = "重写为 rewrite_url",
            hint = "实际返回内容的地址"
        )
        Spacer(Modifier.height(Space.sm))
        HintText("注意：服务端存储时是「rewrite_url → old_url」，与这两个输入框的直觉方向相反。")
    }
}

@Composable
private fun RvRewriteDelDialog(env: RvEnv, target: String, origin: String, close: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "删除 URL 改写",
        icon = Icons.Filled.DeleteOutline,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            env.ask(
                RvConfirm(
                    title = "确认删除这条改写",
                    message = "访问 $target 将不再被改写到 $origin。",
                    warning = "删除不可撤销。另外服务端的删除逻辑只按 old_url（即上面的 $origin）匹配，" +
                        "rewrite_url 参数实际上从未被使用；如果多条规则的 old_url 相同，会被一次性全部删除。",
                    confirmText = "确认删除"
                ) {
                    env.vm.delRewriteUrl(env.siteId, origin, target) { e ->
                        if (e == null) {
                            close()
                            env.done("删除 URL 改写")
                        } else {
                            err = e
                        }
                    }
                }
            )
            null
        }
    ) {
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        DataRow("访问地址", target)
        DataRow("原地址", origin)
        Spacer(Modifier.height(Space.sm))
        HintText("三个参数都必传，缺一个服务端直接返回「参数错误」。")
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 9 · 全局规则
 * 覆盖：getGlobalRules（摘要与全量）/ applyGlobalRulesToSite / recoverDefaultConfig
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RvGlobalTab(env: RvEnv) {
    val vm = env.vm
    var apply by remember { mutableStateOf(false) }
    var recover by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (vm.globalRules.isEmpty()) vm.getGlobalRules { _, _ -> } }

    LazyColumn(
        contentPadding = rvTabPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            SectionHeader("全局规则", subtitle = "config.json 的关键状态摘要与批量下发")
        }

        item {
            WafCard {
                val cc = rvMapOf(vm.globalRules["cc"])
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Assessment,
                            label = "配置项总数",
                            value = vm.globalRules.size.toString(),
                            hint = "config.json 顶层键",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Bolt,
                            label = "全局 CC",
                            value = rvSwitchText(cc["open"]),
                            hint = "阈值 ${rvInt(cc["limit"])}",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Spacer(Modifier.height(Space.sm))
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.SwapHoriz,
                            label = "方法开关",
                            value = rvMapOf(vm.globalRules["method_type"]).size.toString(),
                            hint = "method_type",
                            accent = MaterialTheme.colorScheme.secondary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Compress,
                            label = "请求头上限",
                            value = rvMapOf(vm.globalRules["header_len"]).size.toString(),
                            hint = "header_len",
                            accent = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(Modifier.height(Space.md))
                SmallButton(
                    text = "刷新全局规则",
                    tone = ChipTone.INFO,
                    modifier = Modifier.fillMaxWidth()
                ) { vm.getGlobalRules { _, _ -> } }
            }
        }

        item {
            RvEntryCard(
                title = "批量操作",
                subtitle = "2 项高危",
                entries = listOf(
                    RvEntry(
                        Icons.Filled.CloudQueue, "全局规则下发到指定站点",
                        "逐站写盘，成功与失败站点名混在同一段返回里",
                        ChipTone.DANGER
                    ) { apply = true },
                    RvEntry(
                        Icons.Filled.Restore, "恢复默认配置",
                        "site 分支重置整站规则；global 分支什么都不改",
                        ChipTone.DANGER
                    ) { recover = true }
                )
            )
        }

        item {
            WafCard {
                SectionHeader("全局规则全量", subtitle = "get_global_rules 原始返回值")
                Spacer(Modifier.height(Space.sm))
                if (vm.globalRules.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Assessment,
                        title = "尚未读取全局规则",
                        description = "所有候选配置文件都不存在或读不出来时服务端返回「未找到配置文件」",
                        actionText = "重新读取",
                        onAction = { vm.getGlobalRules { _, _ -> } }
                    )
                } else {
                    vm.globalRules.keys.sorted().forEach { k ->
                        DataRowDivider(k, rvNodeText(vm.globalRules[k]))
                    }
                }
            }
        }

        item {
            WafCard {
                SectionHeader("上次批量下发的结果", subtitle = "apply_global_rules_to_site 的返回原文")
                Spacer(Modifier.height(Space.sm))
                val lastResult = vm.applyResultMessage
                if (lastResult.isNullOrBlank()) {
                    Text(
                        "还没有执行过批量下发。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    HintText(lastResult, danger = true)
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "服务端把成功与失败的站点名拼在同一段文字里返回，需要自己分辨哪一段是失败列表。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (apply) RvApplyToSiteDialog(env) { apply = false }
    if (recover) RvRecoverDialog(env) { recover = false }
}

@Composable
private fun RvApplyToSiteDialog(env: RvEnv, close: () -> Unit) {
    var type by remember { mutableStateOf("cc") }
    var sites by remember { mutableStateOf("allsite") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "全局规则下发到指定站点",
        icon = Icons.Filled.CloudQueue,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            val list = rvSplitList(sites)
            env.ask(
                RvConfirm(
                    title = "确认覆写 ${list.size} 个站点的规则",
                    message = "规则类别 type=$type（${RulesViewModel.GLOBAL_APPLY_RULE_TYPES[type] ?: "-"}），" +
                        "站点 ${list.joinToString("、")}。",
                    warning = "服务端逐站写 site.json，会覆盖选中站点该类别的全部配置，不可撤销。" +
                        "站点配置读不到、站点不在 site.json 或写盘失败的站点会被跳过，" +
                        "但函数结尾仍无条件返回成功，并把失败站点名拼在返回文字里。",
                    confirmText = "确认覆写"
                ) {
                    env.vm.applyGlobalRulesToSite(
                        type = type,
                        siteIdList = list,
                        onResult = { e ->
                            if (e == null) {
                                close()
                                val msg = env.vm.applyResultMessage
                                if (msg.isNullOrBlank()) {
                                    env.done("下发全局规则到站点")
                                } else {
                                    env.note(RvNotice("已下发，请核对失败站点", msg, danger = true))
                                }
                            } else {
                                err = e
                            }
                        }
                    )
                }
            )
            null
        }
    ) {
        DropdownField(
            value = type,
            onValueChange = { type = it },
            label = "规则类别 type",
            options = RulesViewModel.GLOBAL_APPLY_RULE_TYPES.map { (k, n) -> "$k · $n" to k }
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = sites,
            onValueChange = { sites = it },
            label = "站点 site_id_list",
            hint = "多个用逗号分隔；全部网站填 allsite"
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            "site_id_list 必须是 JSON 数组，逗号串会命中服务端的数组断言。" +
                "除 rce 之外，规则节点里原有的命中次数会被保留。"
        )
    }
}

@Composable
private fun RvRecoverDialog(env: RvEnv, close: () -> Unit) {
    var type by remember { mutableStateOf("site") }
    var err by remember { mutableStateOf<String?>(null) }
    FormDialog(
        title = "恢复默认配置",
        icon = Icons.Filled.Restore,
        onDismiss = close,
        busy = env.vm.saving,
        error = err,
        confirmText = "下一步",
        onConfirm = {
            if (type == "site") {
                env.ask(
                    RvConfirm(
                        title = "确认把整站规则重置为出厂默认",
                        message = "site_id=${env.siteId} 的整份规则会被替换成后端硬编码的默认值。",
                        warning = "只保留 idc、smart_cc、cdn、cdn_baidu、readonly 五项已有配置，" +
                            "其余所有用户定制的防护参数全部丢失，不可撤销。" +
                            "另外这个分支不调用配置更新也不备份，要等下一次配置更新才生效。",
                        confirmText = "确认重置整站规则"
                    ) {
                        env.vm.recoverDefaultConfig(env.siteId, "site") { e ->
                            if (e == null) {
                                close()
                                env.done("重置整站规则")
                            } else {
                                err = e
                            }
                        }
                    }
                )
            } else {
                env.ask(
                    RvConfirm(
                        title = "确认提交 global 分支",
                        message = "将对 site_id=${env.siteId} 提交 type=global。",
                        warning = "服务端的 global 分支什么都不修改，只写一条操作日志就返回「操作成功」。" +
                            "这是一个纯假成功，提交后不会有任何配置变化。",
                        confirmText = "仍然提交"
                    ) {
                        env.vm.recoverDefaultConfig(env.siteId, "global") { e ->
                            if (e == null) {
                                close()
                                env.note(
                                    RvNotice(
                                        "已提交，但没有生效",
                                        "服务端的 global 分支不修改任何配置，只写了一条操作日志。" +
                                            "真正会重置整站规则的是 type=site。"
                                    )
                                )
                            } else {
                                err = e
                            }
                        }
                    }
                )
            }
            null
        }
    ) {
        DropdownField(
            value = type,
            onValueChange = { type = it },
            label = "重置范围 type",
            options = listOf(
                "site · 重置该站整份规则（真正生效）" to "site",
                "global · 只写日志，不改任何配置" to "global"
            )
        )
        Spacer(Modifier.height(Space.sm))
        RvSiteField(env)
        Spacer(Modifier.height(Space.sm))
        HintText("两个 type 的行为天差地别，选错不会有任何提示。", danger = true)
    }
}

/** 地区限制列表的每页条数，与服务端 p_size 参数一致。 */
private const val RV_PAGE_SIZE = 20
