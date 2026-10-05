package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.*
import com.bt.waf.viewmodel.WafMasterViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

/* ══════════════════════════════════════════════════════════════════
 * 宝塔云 WAF · DNS 集群管理
 *
 * 对应 WafMasterViewModel 的全部接口，分四个 Tab：
 *   厂商 / 域名 / 解析 / 负载均衡
 *
 * 数据本身是四层嵌套的（厂商 → 域名 → 解析记录，负载均衡是独立的第四层），
 * 所以 Tab 顺序就是层级顺序，每层都单独给出「当前选中的上一层」，
 * 否则会出现「换了厂商却看不到记录」的错觉。
 *
 * 贯穿全文件的几条约定：
 *
 * 1. 写操作的错误一律渲染在 FormDialog 的 error 参数里。
 *    Snackbar 挂在 Scaffold 上而 AlertDialog 盖在其上，写在 Snackbar 里的
 *    错误 100% 不可见 —— 这是本项目已修过的坑，不要退回 Snackbar。
 *
 * 2. 数组类参数（检测域名、删除厂商、删除分组的入参）由 ViewModel 封装成
 *    JSON 数组，界面只管把 List 传下去，绝不能自己拼逗号分隔字符串。
 *
 * 3. 多个写接口是「假成功」：腾讯云删除记录与阿里云删除记录都吞掉错误仍返回
 *    code=0。所以成功文案统一走 submitDone，只说「已提交、列表已重新拉取」，
 *    不承诺「已删除」，让用户自己看列表有没有变化。
 *
 * 4. 错误文案一律原样展示。服务端在英文语言下会把中文错误替换成字面量
 *    "Fail"，所以界面不能对错误文案做中文匹配。
 * ══════════════════════════════════════════════════════════════════ */

private enum class WmTab(val label: String) {
    VENDOR("厂商"), DOMAIN("域名"), RECORD("解析"), LOAD("负载均衡")
}

private const val VENDOR_TENCENT = "tencent"
private const val VENDOR_ALIYUN = "aliyun"

/** 后端 create_api_key 把 domains 硬编码成这个值，界面必须如实告知用户。 */
private const val HARDCODED_DOMAINS = "baidu.com,bt.cn"

/** 脱敏时服务端直接切 SecretId 的前 10 位，不足 10 位会 panic。 */
private const val MIN_SECRET_ID_LEN = 10

/** 阿里云接口的分页被服务端写死成 500 条，超出会静默截断。 */
private const val ALIYUN_PAGE_LIMIT = 500

private val RECORD_TYPE_OPTIONS = listOf(
    "A" to "A", "AAAA" to "AAAA", "CNAME" to "CNAME", "MX" to "MX",
    "TXT" to "TXT", "NS" to "NS", "SRV" to "SRV", "CAA" to "CAA"
)

/**
 * 腾讯云 DNSPod 线路。get_allow_line 大概率拉不到（domain 参数被服务端忽略），
 * 所以这里给一份常用兜底，用户也可以在新增记录时直接手填。
 */
private val TENCENT_LINE_OPTIONS = listOf(
    "默认", "电信", "联通", "移动", "教育网", "海外", "搜索引擎", "微博", "百度"
)

/** 阿里云线路名与腾讯云不通用，必须分开给。 */
private val ALIYUN_LINE_OPTIONS = listOf(
    "default" to "默认",
    "telecom" to "电信",
    "unicom" to "联通",
    "mobile" to "移动",
    "education" to "教育网",
    "oversea" to "海外"
)

private val LOAD_METHOD_OPTIONS = listOf(
    "dns" to "DNS 轮询（dns）",
    "region" to "区域分流（region）",
    "weight" to "权重分流（weight）"
)

/** 通用破坏性二次确认。用类承载是为了能把 lambda 一起存进 state。 */
private class WmConfirm(
    val title: String,
    val message: String,
    val confirmText: String = "确认",
    val danger: Boolean = true,
    val warning: String? = null,
    val onConfirm: () -> Unit
)

/** 负载均衡节点的额外字段。sid 来自 get_site_all_node，是分组归属的唯一依据。 */
private data class WmNodeExtra(
    val ip: String = "",
    val weight: String = "1",
    val region: String = "",
    val status: Int = 1
)

/* ══════════════════════════════════════════════════════════════════
 * 入口
 * ══════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WafMasterScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: WafMasterViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(WmTab.VENDOR) }
    var confirm by remember { mutableStateOf<WmConfirm?>(null) }
    // 解析 Tab 当前选中的域名。提到这一层是为了让顶部 ErrorBanner 的重试
    // 知道要重查哪个域名 —— 否则重试只能盲目重跑厂商列表。
    var recDomain by remember { mutableStateOf("") }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    /**
     * 写操作成功后的统一文案。
     * 后端多个写接口吞错仍返回成功，所以这里只陈述「已提交且已重新拉取」，
     * 把「到底生效没有」的判断交还给列表本身。
     */
    fun submitDone(what: String) {
        toast("$what 已提交，列表已重新拉取，请确认结果是否真的变化")
    }

    fun askConfirm(task: WmConfirm) {
        confirm = task
    }

    LaunchedEffect(Unit) { vm.refresh(); vm.getDnsManufacturerList { _, _ -> } }
    LaunchedEffect(vm.message) {
        vm.message?.let { toast(it); vm.consumeMessage() }
    }

    // SubPageScaffold 而不是 Scaffold：内层 Scaffold 会再消费一遍
    // windowInsets，顶部多出约 250px 空白（详见 SubPageScaffold 注释）。
    SubPageScaffold(snackbarHost = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            WafTabRow(
                labels = WmTab.entries.map { it.label },
                selectedIndex = WmTab.entries.indexOf(tab),
                onSelect = { idx -> tab = WmTab.entries[idx] },
                containerColor = MaterialTheme.colorScheme.surface,
            )

            LoadingBar(vm.loading)
            ErrorBanner(vm.error) {
                when (tab) {
                    WmTab.VENDOR -> vm.loadDnsList()
                    WmTab.DOMAIN -> if (vm.currentVendor.isNotBlank()) {
                        vm.loadDnsDomainList(vm.currentVendor)
                    }

                    WmTab.RECORD -> when {
                        vm.currentVendor.isBlank() -> vm.loadDnsList()
                        recDomain.isBlank() -> vm.loadDnsDomainList(vm.currentVendor)
                        vm.currentVendor == VENDOR_ALIYUN -> vm.loadAliyunRecords(recDomain)
                        else -> vm.loadTencentRecords(recDomain)
                    }

                    WmTab.LOAD -> vm.loadLoadGroups()
                }
            }

            when (tab) {
                WmTab.VENDOR -> VendorTab(vm, ::submitDone, ::askConfirm)
                WmTab.DOMAIN -> DomainTab(vm, ::submitDone, ::askConfirm)
                WmTab.RECORD -> RecordTab(
                    vm = vm,
                    selectedDomain = recDomain,
                    onSelectDomain = { recDomain = it },
                    submitDone = ::submitDone,
                    askConfirm = ::askConfirm
                )

                WmTab.LOAD -> LoadTab(vm, ::submitDone, ::askConfirm)
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = {
                Icon(
                    if (c.danger) Icons.Filled.Warning else Icons.Filled.Info,
                    null,
                    tint = if (c.danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
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
                }) {
                    Text(
                        c.confirmText,
                        color = if (c.danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }
        )
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 1 · 厂商（第一层：dns_info 表）
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun VendorTab(
    vm: WafMasterViewModel,
    submitDone: (String) -> Unit,
    askConfirm: (WmConfirm) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var showFreeInfo by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }

    // 厂商下拉与已授权列表都是首屏要看的，进来先各拉一次
    LaunchedEffect(Unit) { if (vm.dnsList.isEmpty()) vm.loadDnsList() }

    val authorized = vm.dnsList.map { wmVendorOf(it) }.filter { it.isNotBlank() }.toSet()
    val freeSlots = listOf(VENDOR_TENCENT, VENDOR_ALIYUN).filter { it !in authorized }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                GridRow {
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.Key,
                            label = "已授权",
                            value = formatNumber(vm.dnsList.size.toLong()),
                            hint = "厂商数",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                    GridCell {
                        MetricCard(
                            icon = Icons.Filled.AccountTree,
                            label = "待授权位",
                            value = formatNumber(freeSlots.size.toLong()),
                            hint = "腾讯云 / 阿里云",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "授权厂商",
                        subtitle = "dns_info 表 · 一行 = 一个已授权厂商",
                        action = { SmallButton("刷新") { vm.loadDnsList() } }
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "密钥在服务端加密保存，列表里只能看到脱敏串（前 10 位 + 星号）。" +
                            "编辑时密钥框留空表示沿用旧值，绝不能把脱敏串回填进去。"
                    )
                    Spacer(Modifier.height(Space.md))
                    LabeledField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = "按备注过滤",
                        hint = "服务端只匹配备注 ps，不匹配厂商名"
                    )
                }
            }

            if (vm.dnsList.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Key,
                        title = "还没有授权任何 DNS 厂商",
                        description = "授权之后才能拉取域名接管状态与解析记录",
                        actionText = "新增授权",
                        onAction = { showCreate = true }
                    )
                }
            }

            items(vm.dnsList, key = { wmVendorOf(it) }) { row ->
                WafCard {
                    val vendor = wmVendorOf(row)
                    val on = (row["status"].asString().toIntOrNull() ?: 1) == 1
                    ListRow(
                        icon = if (vendor == VENDOR_TENCENT) Icons.Filled.Cloud else Icons.Filled.CloudQueue,
                        title = wmVendorLabel(vendor),
                        subtitle = listOf(
                            row["ps"].asString().ifBlank { "无备注" },
                            pickString(row, "api_key").ifBlank { "无密钥串" }
                        ).joinToString(" · "),
                        iconTint = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        trailing = {
                            StatusChip(if (on) "启用中" else "已停用", if (on) ChipTone.SUCCESS else ChipTone.NEUTRAL)
                        }
                    )
                    Spacer(Modifier.height(Space.xs))
                    DataRowDivider("厂商标识", vendor)
                    DataRowDivider(
                        "已登记域名",
                        row["domains"].asString().ifBlank { "未填写" }
                    )
                    DataRowDivider(
                        "域名总数",
                        (row["domain_total"].asString().toIntOrNull() ?: 0).toString()
                    )
                    DataRowDivider("授权时间", wmTimeText(row["create_time"]))
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("编辑", tone = ChipTone.INFO) { editTarget = row }
                        SmallButton("删除授权", tone = ChipTone.DANGER) {
                            askConfirm(
                                WmConfirm(
                                    title = "删除厂商授权",
                                    message = "确认删除「${wmVendorLabel(vendor)}」的授权记录吗？",
                                    confirmText = "确认删除",
                                    warning = "这一行存着该厂商的唯一密钥，删除后该厂商的域名接管状态、" +
                                        "解析记录读写、套餐查询全部立即失效，且密钥不可恢复（列表里只有脱敏串）。" +
                                        "另外若该厂商已被负载均衡分组引用，服务端会拒绝删除，" +
                                        "需先解除 dns_name 绑定。",
                                    onConfirm = {
                                        // name_list 必须是 JSON 数组，元素是厂商标识而不是域名或 id
                                        vm.deleteDnsRecord(listOf(vendor)) { e ->
                                            if (e != null) vm.error = wmCleanText(e)
                                            else submitDone("删除厂商授权")
                                        }
                                    }
                                )
                            )
                        }
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "厂商下拉选项",
                        subtitle = "get_dns_manufacturer_list · 决定后续接口的 types 取值"
                    )
                    Spacer(Modifier.height(Space.sm))
                    if (vm.manufacturers.isEmpty()) {
                        Text(
                            "暂无数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        vm.manufacturers.forEach { m ->
                            val value = m["value"].asString()
                            val methods = m["method"].asList()
                                .joinToString(" · ") { wmMethodLabel(it) }
                            DataRowDivider(
                                m["label"].asString().ifBlank { "(空)" },
                                listOf("value=$value", methods).filter { it.isNotBlank() }
                                    .joinToString("　")
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "服务端只支持腾讯云与阿里云两项，下拉里恒定以「不设置」结尾。" +
                            "厂商标识就是上面这些 value，后续所有接口都用它。"
                    )
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        title = "免费额度查询",
                        subtitle = "get_free_info",
                        accent = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "后端接口暂不可用。服务端解析请求体的代码被写在了 tencent 分支内部，" +
                            "而分支判断用的结构体还是零值，任何厂商都会在第一次调用就失败。",
                        danger = true
                    )
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        // 常态入口：灰掉，说明功能未实现
                        SmallButton("查询套餐余量", enabled = false) {}
                        SmallButton("为什么灰着", tone = ChipTone.WARNING) { showFreeInfo = true }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "新增厂商授权") }
    }

    if (showCreate) {
        CreateApiKeyDialog(
            vm = vm,
            vendorOptions = freeSlots.ifEmpty { listOf(VENDOR_TENCENT, VENDOR_ALIYUN) },
            allTaken = freeSlots.isEmpty(),
            onDismiss = { showCreate = false },
            onDone = { showCreate = false; submitDone("新增厂商授权") }
        )
    }

    editTarget?.let { row ->
        EditVendorDialog(
            vm = vm,
            row = row,
            onDismiss = { editTarget = null },
            onDone = { editTarget = null; submitDone("更新厂商授权") }
        )
    }

    if (showFreeInfo) {
        FreeInfoDialog(vm = vm, onDismiss = { showFreeInfo = false })
    }
}

/** 新增授权：create_api_key。domains 被服务端忽略，界面上如实说明。 */
@Composable
private fun CreateApiKeyDialog(
    vm: WafMasterViewModel,
    vendorOptions: List<String>,
    allTaken: Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    var vendor by remember { mutableStateOf(vendorOptions.firstOrNull().orEmpty()) }
    var secretId by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var ps by remember { mutableStateOf("") }

    FormDialog(
        title = "新增 DNS 厂商授权",
        icon = Icons.Filled.Key,
        confirmText = "授权",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val id = secretId.trim()
            when {
                vendor.isBlank() -> err = "请选择 DNS 厂商"
                id.length < MIN_SECRET_ID_LEN -> err = "SecretId 至少 $MIN_SECRET_ID_LEN 位，" +
                    "位数不足会触发服务端切片 panic"

                secretKey.isBlank() -> err = "请填写 SecretKey"
                else -> vm.createApiKey(vendor, id, secretKey, ps) { e ->
                    if (e == null) onDone() else err = wmCleanText(e)
                }
            }
            null
        }
    ) {
        if (allTaken) {
            HintText("腾讯云与阿里云都已授权，无法继续新增。要换密钥请在列表里点「编辑」。", danger = true)
            Spacer(Modifier.height(Space.md))
        }
        HintText(
            "保存后域名列表固定为 $HARDCODED_DOMAINS —— 服务端把 domains 参数完全忽略了，" +
                "落库时写死的就是这两个。真实域名清单要等从厂商侧读回域名列表后，用「编辑」里的" +
                "域名清单覆盖写入。",
            danger = true
        )
        Spacer(Modifier.height(Space.md))
        DropdownField(
            value = vendor,
            onValueChange = { vendor = it; err = null },
            label = "DNS 厂商",
            options = vendorOptions.map { wmVendorLabel(it) to it }
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = secretId,
            onValueChange = { secretId = it; err = null },
            label = "SecretId",
            hint = "至少 $MIN_SECRET_ID_LEN 位"
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = secretKey,
            onValueChange = { secretKey = it; err = null },
            label = "SecretKey",
            hint = "云厂商控制台的访问密钥",
            isPassword = true
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = ps,
            onValueChange = { ps = it },
            label = "备注",
            hint = "会拼进厂商下拉标签，如「腾讯云(香港节点)」"
        )
    }
}

/** 编辑授权：update_dns_record。密钥留空即沿用旧值，domains 是覆盖式。 */
@Composable
private fun EditVendorDialog(
    vm: WafMasterViewModel,
    row: Map<String, Any?>,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    val vendor = wmVendorOf(row)
    var err by remember { mutableStateOf<String?>(null) }
    var secretId by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var domains by remember {
        mutableStateOf(row["domains"].asString().trimEnd(',').replace(",", "\n"))
    }
    var ps by remember { mutableStateOf(row["ps"].asString()) }
    var enabled by remember { mutableStateOf((row["status"].asString().toIntOrNull() ?: 1) == 1) }

    FormDialog(
        title = "编辑 ${wmVendorLabel(vendor)} 授权",
        icon = Icons.Filled.Edit,
        confirmText = "保存",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val id = secretId.trim()
            val key = secretKey.trim()
            if (id.isNotEmpty() != key.isNotEmpty()) {
                err = "SecretId 与 SecretKey 必须同时填写。只填一个会被服务端当作「不改密钥」静默沿用旧值"
            } else if (id.isNotEmpty() && id.length < MIN_SECRET_ID_LEN) {
                err = "SecretId 至少 $MIN_SECRET_ID_LEN 位，位数不足会触发服务端切片 panic"
            } else {
                val list = wmSplitList(domains)
                vm.updateDnsRecord(
                    vendor = vendor,
                    secretId = id,
                    secretKey = key,
                    domains = list,
                    ps = ps,
                    status = if (enabled) 1 else 0
                ) { e ->
                    if (e == null) onDone() else err = wmCleanText(e)
                }
            }
            null
        }
    ) {
        HintText(
            "密钥框留空表示沿用旧密钥。列表里的脱敏串不能回填 —— " +
                "回填会被当成新密钥去云厂商验签，必然失败。"
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = secretId,
            onValueChange = { secretId = it; err = null },
            label = "新 SecretId（留空不改）",
            hint = "至少 $MIN_SECRET_ID_LEN 位"
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = secretKey,
            onValueChange = { secretKey = it; err = null },
            label = "新 SecretKey（留空不改）",
            hint = "与 SecretId 同时填写才会生效",
            isPassword = true
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = domains,
            onValueChange = { domains = it; err = null },
            label = "接管域名清单（每行一个）",
            hint = "留空表示保持服务端原有清单",
            singleLine = false
        )
        Spacer(Modifier.height(Space.xs))
        HintText(
            "这里是覆盖不是追加：填了非空清单会整体替换原有域名。" +
                "服务端落库时每项后面都跟一个逗号，且去重后顺序不保证。"
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = ps,
            onValueChange = { ps = it },
            label = "备注",
            hint = "留空表示不修改"
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "启用该厂商",
            checked = enabled,
            onChange = { enabled = it },
            description = "关闭后该厂商不出现在下拉里，域名接管与解析操作全部失效"
        )
        Spacer(Modifier.height(Space.xs))
        HintText(
            "注意：同时改密钥和改启用状态时，服务端会在验签分支把状态强制回填成库中旧值，" +
                "状态改动不生效。这种情况请分两次保存。"
        )
    }
}

/** get_free_info 的说明弹窗。功能本身在服务端不可实现，这里只解释并允许试一次。 */
@Composable
private fun FreeInfoDialog(vm: WafMasterViewModel, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var tried by remember { mutableStateOf(false) }

    FormDialog(
        title = "免费额度查询",
        icon = Icons.Filled.Info,
        confirmText = "试一次",
        busy = false,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            tried = true
            vm.getFreeInfo(VENDOR_TENCENT) { e -> err = wmCleanText(e) }
            null
        }
    ) {
        HintText(
            "后端接口暂不可用。第一次调用就会以「规则名称[]不存在」失败，" +
                "拿不到任何套餐余量。",
            danger = true
        )
        if (tried) {
            Spacer(Modifier.height(Space.md))
            Text(
                "已按预期失败。原始原因在服务端被丢掉了，这里只回显它给出的文案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 2 · 域名（第二层：NS 托管状态）
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun DomainTab(
    vm: WafMasterViewModel,
    submitDone: (String) -> Unit,
    askConfirm: (WmConfirm) -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    var showCheck by remember { mutableStateOf(false) }
    var statusTarget by remember { mutableStateOf<String?>(null) }
    var createDomainFor by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    val vendor = vm.currentVendor
    val vendorOptions = wmAuthorizedVendors(vm)

    LaunchedEffect(vendorOptions) {
        if (vendor.isBlank() && vendorOptions.isNotEmpty()) {
            vm.loadDnsDomainList(vendorOptions.first())
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                WafCard {
                    SectionHeader(
                        "选择厂商",
                        subtitle = "get_dns_domain_list 的 types 必填，先有授权才能查"
                    )
                    Spacer(Modifier.height(Space.md))
                    if (vendorOptions.isEmpty()) {
                        HintText(
                            "还没有任何已授权厂商。请先到「厂商」页授权，否则这里会直接失败。",
                            danger = true
                        )
                    } else {
                        DropdownField(
                            value = vendor,
                            onValueChange = { vm.loadDnsDomainList(it) },
                            label = "当前厂商",
                            options = vendorOptions.map { wmVendorLabel(it) to it }
                        )
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "域名接管",
                        subtitle = if (vendor.isBlank()) "未选择厂商"
                        else "${vm.domainList.size} 个域名（服务端不支持分页）",
                        action = {
                            Row {
                                SmallButton("批量检测", tone = ChipTone.INFO, enabled = vendor.isNotBlank()) {
                                    showCheck = true
                                }
                                Spacer(Modifier.width(Space.xs))
                                SmallButton("刷新", enabled = vendor.isNotBlank()) {
                                    vm.loadDnsDomainList(vendor, keyword)
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "只有 NS 已托管到该厂商的域名才会去拉解析记录；未托管的域名记录列表恒为空。" +
                            "服务端把分页参数解析了但从不使用，一次可能返回上百个域名。"
                    )
                    if (vendor.isNotBlank()) {
                        Spacer(Modifier.height(Space.md))
                        LabeledField(
                            value = keyword,
                            onValueChange = { keyword = it },
                            label = "按域名过滤",
                            hint = "服务端按 root_domain 模糊匹配"
                        )
                        Spacer(Modifier.height(Space.sm))
                        PrimaryButton("按关键词查询", onClick = { vm.loadDnsDomainList(vendor, keyword) })
                    }
                }
            }

            if (vendor.isNotBlank() && vm.domainList.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Language,
                        title = "该厂商下没有域名",
                        description = "请到云厂商控制台把域名的 NS 记录指向该厂商后重试"
                    )
                }
            }

            items(vm.domainList, key = { pickString(it, "root_domain") }) { d ->
                val root = pickString(d, "root_domain").ifBlank { "(未命名)" }
                val hosted = d["dns_server"].toBoolState()
                val parseList = d["parse_list"].asList()
                val open = root in expanded

                WafCard {
                    ListRow(
                        icon = if (hosted) Icons.Filled.Verified else Icons.Filled.Warning,
                        title = root,
                        subtitle = listOf(
                            pickString(d, "version").ifBlank { "套餐未知" },
                            if (hosted) "NS 已托管" else "NS 未托管",
                            "${parseList.size} 条记录"
                        ).joinToString(" · "),
                        iconTint = if (hosted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
                        onClick = {
                            expanded = if (open) expanded - root else expanded + root
                        },
                        trailing = {
                            StatusChip(
                                if (hosted) "已托管" else "未托管",
                                if (hosted) ChipTone.SUCCESS else ChipTone.WARNING
                            )
                        }
                    )

                    Spacer(Modifier.height(Space.xs))
                    DataRowDivider("子域", pickString(d, "subdomain").ifBlank { "-" })
                    DataRowDivider("厂商", wmVendorLabel(pickString(d, "dns_name")))

                    if (open) {
                        Spacer(Modifier.height(Space.sm))
                        if (!hosted) {
                            HintText(
                                "该域名 NS 未托管到本厂商，服务端不会拉它的解析记录，" +
                                    "所以下面必然是空的。",
                                danger = true
                            )
                            Spacer(Modifier.height(Space.sm))
                        }
                        if (parseList.isEmpty()) {
                            Text(
                                "没有解析记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            parseList.forEach { r ->
                                // parse_list 的 status 是 1/0 整数，腾讯云原生枚举是 ENABLE/DISABLE
                                val on = wmToEnabled(r["status"])
                                ListRow(
                                    icon = Icons.AutoMirrored.Filled.Article,
                                    title = "${r["host"].asString().ifBlank { "@" }} · ${r["type"].asString()}",
                                    subtitle = listOf(
                                        r["address"].asString(),
                                        "线路 ${r["line"].asString()}",
                                        "TTL ${r["ttl"].asString()}"
                                    ).joinToString(" · "),
                                    iconSize = 30.dp,
                                    iconTint = MaterialTheme.colorScheme.secondary,
                                    trailing = {
                                        StatusChip(if (on) "启用" else "停用", if (on) ChipTone.SUCCESS else ChipTone.NEUTRAL)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.xs))
                        HintText(
                            "这里的记录来自域名列表，不含记录 ID，所以无法在此编辑。" +
                                "要改请到「解析」页用原始记录接口操作。腾讯云侧还会跳过值里含 dnspod 的接管记录。"
                        )
                    }

                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("查询状态", tone = ChipTone.INFO, enabled = vendor.isNotBlank()) {
                            statusTarget = root
                        }
                        SmallButton("新建域名区域", tone = ChipTone.DANGER) {
                            createDomainFor = root
                        }
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "删除厂商授权记录",
                        subtitle = "delete_dns_record",
                        accent = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "这个接口删的是 dns_info 里的厂商授权行（整行含密钥），不是某一条域名记录。" +
                            "入参是厂商标识数组，不是域名也不是 id。"
                    )
                    Spacer(Modifier.height(Space.sm))
                    if (vm.dnsList.isEmpty()) {
                        Text(
                            "没有可删除的授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        vm.dnsList.forEach { row ->
                            val v = wmVendorOf(row)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Space.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    wmVendorLabel(v),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                SmallButton("删除", tone = ChipTone.DANGER) {
                                    askConfirm(
                                        WmConfirm(
                                            title = "删除厂商授权",
                                            message = "确认删除「${wmVendorLabel(v)}」吗？",
                                            confirmText = "确认删除",
                                            warning = "密钥不可恢复。若该厂商已被负载均衡分组通过 dns_name 引用，" +
                                                "服务端会拒绝删除，需先解除绑定。",
                                            onConfirm = {
                                                vm.deleteDnsRecord(listOf(v)) { e ->
                                                    if (e != null) vm.error = wmCleanText(e)
                                                    else submitDone("删除厂商授权")
                                                }
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCheck) {
        CheckDomainDialog(vm = vm, onDismiss = { showCheck = false })
    }

    statusTarget?.let { d ->
        DomainStatusDialog(vm = vm, domain = d, onDismiss = { statusTarget = null })
    }

    createDomainFor?.let { d ->
        CreateDomainRecordDialog(vm = vm, domain = d, onDismiss = { createDomainFor = null })
    }
}

/** 批量检测：check_dns_domain。domains 必须是 JSON 数组，且只能填域名不能填 IP。 */
@Composable
private fun CheckDomainDialog(vm: WafMasterViewModel, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }

    FormDialog(
        title = "批量检测域名接管",
        icon = Icons.Filled.Search,
        confirmText = "开始检测",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val list = wmSplitList(text)
            if (list.isEmpty()) {
                err = "请至少填写一个域名"
            } else {
                vm.checkDnsDomain(vm.currentVendor, list) { e ->
                    if (e == null) err = null else err = wmCleanText(e)
                }
            }
            null
        }
    ) {
        HintText(
            "每行一个域名，可带端口（如 example.com:8443）与 *. 前缀。" +
                "结果 key 是根域名：填 www.a.com 回来的 key 是 a.com。",
            danger = true
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = text,
            onValueChange = { text = it; err = null },
            label = "待检测域名",
            hint = "example.com\nexample.cn",
            singleLine = false
        )

        if (vm.domainCheck.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            Text(
                "检测结果",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Space.xs))
            vm.domainCheck.forEach { (root, st) ->
                val added = st["add_status"].toBoolState()
                val dnsOk = st["dns_status"].toBoolState()
                WafCard(contentPadding = rowCardPadding) {
                    ListRow(
                        icon = if (added) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        title = root,
                        subtitle = (if (added) "厂商账号内已存在该域名" else "厂商账号内不存在该域名") +
                            " · " + (if (dnsOk) "解析状态正常" else "解析状态异常"),
                        iconSize = 30.dp,
                        iconTint = if (added) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        trailing = {
                            StatusChip(if (dnsOk) "NS 正常" else "NS 异常", if (dnsOk) ChipTone.SUCCESS else ChipTone.DANGER)
                        }
                    )
                }
                Spacer(Modifier.height(Space.xs))
            }
        }
    }
}

/** 域名托管状态：get_dns_status_by_domain。硬编码走腾讯云凭证。 */
@Composable
private fun DomainStatusDialog(vm: WafMasterViewModel, domain: String, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf(domain) }
    var result by remember { mutableStateOf<Map<String, Any?>?>(null) }

    FormDialog(
        title = "查询域名托管状态",
        icon = Icons.Filled.Search,
        confirmText = "查询",
        busy = false,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            result = null
            vm.getDnsStatusByDomain(input) { r, e ->
                if (e != null) err = wmCleanText(e) else result = r
            }
            null
        }
    ) {
        HintText(
            "该接口在服务端硬编码使用腾讯云凭证，查阿里云域名会失败或返回错误结果。" +
                "另外所有云厂商 SDK 错误都被改写成「获取域名信息失败」，原始原因永久丢失。",
            danger = true
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = input,
            onValueChange = { input = it; err = null },
            label = "根域名",
            hint = "example.com"
        )

        val r = result
        if (r != null) {
            Spacer(Modifier.height(Space.md))
            if (r.isEmpty()) {
                HintText("返回内容为空，服务端没有给出任何字段。")
            } else {
                r.forEach { (k, v) -> DataRowDivider(wmHumanKey(k), wmRenderAny(v)) }
            }
        }
    }
}

/** create_domain_dns_record：不是「添加接管域名」，而是在云厂商账号里新建域名区域。 */
@Composable
private fun CreateDomainRecordDialog(vm: WafMasterViewModel, domain: String, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf(domain) }
    var agreed by remember { mutableStateOf(false) }

    FormDialog(
        title = "在云厂商新建域名区域",
        icon = Icons.Filled.Warning,
        confirmText = "我已了解，创建",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            when {
                input.isBlank() -> err = "请填写域名"
                !agreed -> err = "请先勾选下方确认项"
                else -> vm.createDomainDnsRecord(input) { e ->
                    if (e == null) onDismiss() else err = wmCleanText(e)
                }
            }
            null
        }
    ) {
        HintText(
            "危险操作：这不是「把已有域名接管进来」，而是在你的腾讯云 DNS 账号里新建一个域名区域。" +
                "若域名已被该账号下其他人占用，或涉及付费套餐，会产生费用；" +
                "云端留下的资源也不可逆。",
            danger = true
        )
        Spacer(Modifier.height(Space.sm))
        HintText(
            "已有域名的接管应该在腾讯云控制台改 NS 记录完成，WAF 侧用「批量检测」复查状态就够了。"
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = input,
            onValueChange = { input = it; err = null },
            label = "根域名",
            hint = "example.com"
        )
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = agreed, onCheckedChange = { agreed = it; err = null })
            Spacer(Modifier.width(Space.sm))
            Text(
                "我确认这会在云厂商账号里新建域名区域并可能产生费用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 3 · 解析记录（第三层：厂商账号下的单条记录）
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun RecordTab(
    vm: WafMasterViewModel,
    selectedDomain: String,
    onSelectDomain: (String) -> Unit,
    submitDone: (String) -> Unit,
    askConfirm: (WmConfirm) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editRecord by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var showLine by remember { mutableStateOf(false) }

    val vendor = vm.currentVendor
    val vendorOptions = wmAuthorizedVendors(vm)
    val domainOptions = vm.domainList
        .map { pickString(it, "root_domain") }
        .filter { it.isNotBlank() }
        .distinct()

    LaunchedEffect(vendorOptions) {
        if (vendor.isBlank() && vendorOptions.isNotEmpty()) {
            vm.loadDnsDomainList(vendorOptions.first())
        }
    }
    // 换厂商要把域名选择清掉，否则会拿上一家的域名去查
    LaunchedEffect(vendor) { onSelectDomain("") }
    LaunchedEffect(vendor, domainOptions) {
        if (selectedDomain.isBlank() || selectedDomain !in domainOptions) {
            onSelectDomain(domainOptions.firstOrNull().orEmpty())
        }
    }
    LaunchedEffect(vendor, selectedDomain) {
        if (selectedDomain.isBlank()) return@LaunchedEffect
        if (vendor == VENDOR_ALIYUN) vm.loadAliyunRecords(selectedDomain)
        else if (vendor == VENDOR_TENCENT) vm.loadTencentRecords(selectedDomain)
    }

    val isAliyun = vendor == VENDOR_ALIYUN
    val records = if (isAliyun) vm.aliyunRecords else vm.tencentRecords

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                WafCard {
                    SectionHeader("选择厂商", subtitle = "腾讯云与阿里云是两套完全独立的接口，字段同名但取值不同")
                    Spacer(Modifier.height(Space.md))
                    if (vendorOptions.isEmpty()) {
                        HintText("还没有已授权厂商，请先到「厂商」页授权。", danger = true)
                    } else {
                        DropdownField(
                            value = vendor,
                            onValueChange = { vm.loadDnsDomainList(it) },
                            label = "解析厂商",
                            options = vendorOptions.map { wmVendorLabel(it) to it }
                        )
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "选择域名",
                        subtitle = if (selectedDomain.isBlank()) "未选择"
                        else "${records.size} 条记录",
                        action = {
                            Row {
                                if (!isAliyun) {
                                    SmallButton("解析线路", tone = ChipTone.INFO, enabled = vendor == VENDOR_TENCENT) {
                                        showLine = true
                                    }
                                    Spacer(Modifier.width(Space.xs))
                                }
                                SmallButton("刷新", enabled = selectedDomain.isNotBlank()) {
                                    if (isAliyun) vm.loadAliyunRecords(selectedDomain)
                                    else vm.loadTencentRecords(selectedDomain)
                                }
                            }
                        }
                    )
                    Spacer(Modifier.height(Space.md))
                    if (domainOptions.isEmpty()) {
                        HintText(
                            "该厂商下没有域名。解析记录挂在域名下，没有域名就无从谈起。",
                            danger = true
                        )
                    } else {
                        DropdownField(
                            value = selectedDomain,
                            onValueChange = onSelectDomain,
                            label = "当前域名",
                            options = domainOptions.map { it to it }
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        if (isAliyun) {
                            "阿里云侧的分页被服务端写死成第 1 页 500 条，也不返回游标，" +
                                "超过 $ALIYUN_PAGE_LIMIT 条的域名会被静默截断且无法翻页。"
                        } else {
                            "腾讯云侧返回的是 DNSPod 原始记录，包含值里含 dnspod 的厂商接管记录" +
                                "（「域名」页那份列表会把它们过滤掉）。"
                        }
                    )
                }
            }

            if (isAliyun) {
                item {
                    WafCard {
                        SectionHeader(
                            "阿里云记录的特殊限制",
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.height(Space.sm))
                        HintText(
                            "1. 新建记录的 TTL 被服务端写死成 600 秒，所以新增表单里不提供 TTL 输入框。\n" +
                                "2. 修改记录的 TTL 是生效的，想改 TTL 只能靠修改。\n" +
                                "3. 该模块没有导出记录启停接口，所以这里不提供启用开关，" +
                                "客户端无法启停阿里云记录。"
                        )
                    }
                }
            }

            if (selectedDomain.isNotBlank() && records.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Article,
                        title = "该域名下没有解析记录",
                        description = if (isAliyun) "阿里云接口最多返回 $ALIYUN_PAGE_LIMIT 条" else "腾讯云原始记录为空"
                    )
                }
            }

            items(records, key = { wmRecordKey(it) }) { r ->
                WafCard {
                    val host = pickString(r, "Name", "RR", "name", "rr").ifBlank { "@" }
                    val type = pickString(r, "Type", "type")
                    val line = pickString(r, "Line", "line")
                    val value = pickString(r, "Value", "value")
                    val on = wmToEnabled(r["Status"] ?: r["status"] ?: r["Disable"])

                    ListRow(
                        icon = Icons.AutoMirrored.Filled.Article,
                        title = "$host · $type",
                        subtitle = listOf(
                            value,
                            if (line.isNotBlank()) "线路 $line" else "",
                            "TTL ${pickString(r, "TTL", "ttl").ifBlank { "-" }}"
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        iconTint = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        trailing = {
                            StatusChip(if (on) "启用" else "停用", if (on) ChipTone.SUCCESS else ChipTone.NEUTRAL)
                        }
                    )
                    Spacer(Modifier.height(Space.xs))
                    DataRowDivider("记录 ID", wmRecordId(r))
                    if (!isAliyun) {
                        DataRowDivider("权重", pickString(r, "Weight", "weight").ifBlank { "-" })
                    } else {
                        DataRowDivider("优先级", pickString(r, "Priority", "priority").ifBlank { "-" })
                    }
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("编辑", tone = ChipTone.INFO) { editRecord = r }
                        SmallButton("删除", tone = ChipTone.DANGER) {
                            val id = wmRecordId(r)
                            askConfirm(
                                WmConfirm(
                                    title = "删除解析记录",
                                    message = "确认删除 $host 的这条 $type 记录吗？",
                                    confirmText = "确认删除",
                                    warning = "公网 DNS 记录会立即消失，没有本地回滚。" +
                                        if (isAliyun) {
                                            "服务端只在能断言错误内容时才回传错误，" +
                                                "多数业务错误会被报成成功。"
                                        } else {
                                            "腾讯云侧非 SDK 错误被静默吞掉，删除失败也会返回成功。"
                                        } +
                                        "提交后请以列表是否变化为准。",
                                    onConfirm = {
                                        if (isAliyun) {
                                            // record_id 在 Go 里是 uint64，列表返回的是字符串，必须转数字
                                            val numId = wmToNumericId(id)
                                            if (numId.isBlank()) {
                                                vm.error = "记录 ID 不是数字，无法提交"
                                            } else {
                                                vm.deleteAliyunRecord(numId) { e ->
                                                    if (e != null) vm.error = wmCleanText(e)
                                                    else {
                                                        submitDone("删除阿里云记录")
                                                        vm.loadAliyunRecords(selectedDomain)
                                                    }
                                                }
                                            }
                                        } else {
                                            vm.deleteRecord(selectedDomain, id) { e ->
                                                if (e != null) vm.error = wmCleanText(e)
                                                else submitDone("删除腾讯云记录")
                                            }
                                        }
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                if (selectedDomain.isBlank()) vm.error = "请先选择域名"
                else showAdd = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "新增解析记录") }
    }

    if (showAdd) {
        RecordFormDialog(
            vm = vm,
            domain = selectedDomain,
            aliyun = isAliyun,
            existing = null,
            onDismiss = { showAdd = false },
            onDone = { showAdd = false; submitDone(if (isAliyun) "新增阿里云记录" else "新增腾讯云记录") }
        )
    }

    editRecord?.let { r ->
        RecordFormDialog(
            vm = vm,
            domain = selectedDomain,
            aliyun = isAliyun,
            existing = r,
            onDismiss = { editRecord = null },
            onDone = { editRecord = null; submitDone(if (isAliyun) "修改阿里云记录" else "修改腾讯云记录") }
        )
    }

    if (showLine) {
        AllowLineDialog(vm = vm, domain = selectedDomain, onDismiss = { showLine = false })
    }
}

/**
 * 解析记录表单，新增与修改共用。
 *
 * 腾讯云与阿里云的入参键名相同但取值有硬差异：
 * - status 只有腾讯云有，且必须下发 ENABLE/DISABLE 而不是 1/0；
 * - record_id 在两边都必须是数字，而阿里云列表返回的是字符串；
 * - TTL 只在阿里云修改时生效，新增时服务端写死 600，所以新增不给输入框。
 */
@Composable
private fun RecordFormDialog(
    vm: WafMasterViewModel,
    domain: String,
    aliyun: Boolean,
    existing: Map<String, Any?>?,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    val editing = existing != null
    var err by remember { mutableStateOf<String?>(null) }

    var host by remember { mutableStateOf(wmPick(existing, "Name", "RR", "name", "rr")) }
    var type by remember { mutableStateOf(wmPick(existing, "Type", "type").ifBlank { "A" }) }
    var line by remember {
        mutableStateOf(
            wmPick(existing, "Line", "line").ifBlank { if (aliyun) "default" else "默认" }
        )
    }
    var value by remember { mutableStateOf(wmPick(existing, "Value", "value")) }
    var ttl by remember { mutableStateOf(wmPick(existing, "TTL", "ttl").ifBlank { "600" }) }
    // 回填时把 1/0 与 ENABLE/DISABLE 两种形态都归一，提交时由 ViewModel 转回原生枚举
    var enabled by remember {
        mutableStateOf(
            existing?.let { wmToEnabled(it["Status"] ?: it["status"] ?: it["Disable"]) } ?: true
        )
    }
    val recordId = existing?.let { wmRecordId(it) }.orEmpty()

    FormDialog(
        title = if (editing) "修改解析记录" else "新增解析记录",
        icon = Icons.AutoMirrored.Filled.Article,
        confirmText = if (editing) "保存" else "创建",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            if (editing && recordId.isBlank()) {
                err = "缺少记录 ID，无法修改"
            } else if (value.isBlank()) {
                err = "请填写记录值"
            } else if (line.isBlank()) {
                err = "请选择解析线路"
            } else if (aliyun && ttl.trim().toIntOrNull() == null) {
                err = "TTL 必须是数字"
            } else {
                if (aliyun) {
                    if (editing) {
                        // record_id 必须转成数字，字符串会被 mapstructure 拒绝
                        val numId = wmToNumericId(recordId)
                        if (numId.isBlank()) {
                            err = "记录 ID 不是数字（当前值 $recordId），无法提交"
                        } else {
                            vm.modifyAliyunRecord(
                                recordId = numId,
                                recordType = type.trim(),
                                subDomain = host.trim(),
                                recordLine = line.trim(),
                                value = value.trim(),
                                ttl = ttl.trim()
                            ) { e -> if (e == null) onDone() else err = wmCleanText(e) }
                        }
                    } else {
                        vm.createAliyunRecord(
                            domain = domain,
                            subDomain = host.trim(),
                            recordType = type.trim(),
                            recordLine = line.trim(),
                            value = value.trim()
                        ) { e -> if (e == null) onDone() else err = wmCleanText(e) }
                    }
                } else {
                    if (editing) {
                        // 整体替换语义：7 个字段必须全填，漏一个会被清空
                        vm.modifyRecord(
                            domain = domain,
                            recordId = recordId,
                            recordType = type.trim(),
                            subDomain = host.trim(),
                            enabled = enabled,
                            recordLine = line.trim(),
                            value = value.trim()
                        ) { e -> if (e == null) onDone() else err = wmCleanText(e) }
                    } else {
                        vm.createRecord(
                            domain = domain,
                            subDomain = host.trim(),
                            recordType = type.trim(),
                            recordLine = line.trim(),
                            value = value.trim()
                        ) { e -> if (e == null) onDone() else err = wmCleanText(e) }
                    }
                }
            }
            null
        }
    ) {
        HintText(
            if (aliyun) {
                "写操作直接落到公网 DNS，值写错会立刻影响线上流量且没有本地回滚。" +
                    "修改是整体替换语义，未提供的字段会被清空。"
            } else {
                "写操作直接落到公网 DNS。修改是整体替换语义，7 个字段必须全部回填，" +
                    "漏一个会被清空。状态由开关转成 ENABLE/DISABLE 后下发，不能直接传 1/0。"
            },
            danger = true
        )
        Spacer(Modifier.height(Space.md))
        DataRow("所属域名", domain)
        Spacer(Modifier.height(Space.xs))
        if (editing) {
            DataRow("记录 ID", recordId)
            Spacer(Modifier.height(Space.xs))
        }
        LabeledField(
            value = host,
            onValueChange = { host = it; err = null },
            label = "主机头",
            hint = "根域名留空或填 @"
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = type,
            onValueChange = { type = it; err = null },
            label = "记录类型",
            options = RECORD_TYPE_OPTIONS
        )
        Spacer(Modifier.height(Space.sm))
        if (aliyun) {
            // 阿里云线路名与腾讯云不通用，分开给
            DropdownField(
                value = line,
                onValueChange = { line = it; err = null },
                label = "解析线路",
                options = ALIYUN_LINE_OPTIONS
            )
        } else {
            DropdownField(
                value = line,
                onValueChange = { line = it; err = null },
                label = "解析线路",
                options = (TENCENT_LINE_OPTIONS + listOf(line))
                    .distinct()
                    .map { it to it }
            )
            Spacer(Modifier.height(Space.xs))
            HintText("线路下拉来自内置常用值。想用冷门线路请到「厂商」页的解析线路查询里取值。")
        }
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = value,
            onValueChange = { value = it; err = null },
            label = "记录值",
            hint = if (type == "MX") "邮箱 + 优先级" else "1.2.3.4",
            singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        if (aliyun && editing) {
            LabeledField(
                value = ttl,
                onValueChange = { ttl = it; err = null },
                label = "TTL（秒）",
                hint = "600",
                keyboardType = KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
        } else if (aliyun) {
            HintText("新增记录的 TTL 被服务端写死成 600 秒，这里不提供输入框。想改 TTL 请记录创建后再修改。")
            Spacer(Modifier.height(Space.sm))
        }
        if (!aliyun && editing) {
            SwitchRow(
                title = "启用该记录",
                checked = enabled,
                onChange = { enabled = it },
                description = "下发值为 ENABLE / DISABLE，腾讯云不接受 1/0"
            )
        }
        if (aliyun) {
            HintText("阿里云侧没有导出记录启停接口，所以这里不提供启用开关。")
        }
    }
}

/** get_allow_line。domain 参数被服务端忽略，且厂商不对时返回字符串 OK 而非列表。 */
@Composable
private fun AllowLineDialog(vm: WafMasterViewModel, domain: String, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var grade by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }

    FormDialog(
        title = "查询腾讯云解析线路",
        icon = Icons.Filled.Tune,
        confirmText = "查询",
        busy = false,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            tried = true
            vm.getAllowLine(VENDOR_TENCENT, domain, grade) { _, e ->
                err = if (e == null) null else wmCleanText(e)
            }
            null
        }
    ) {
        HintText(
            "后端把 domain 参数忽略了：它取的是一个零值结构体里的字段，" +
                "实际查询的是空域名与 0，所以多数实例上会直接报错。" +
                "只有套餐等级 domain_grade 被正确透传。",
            danger = true
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = grade,
            onValueChange = { grade = it },
            label = "套餐等级（domain_grade）",
            hint = "如 企业旗舰版"
        )
        Spacer(Modifier.height(Space.sm))
        DataRow("目标域名", domain.ifBlank { "未选择" })
        Spacer(Modifier.height(Space.xs))
        HintText(
            "另外：厂商不等于 tencent 时服务端走的是 return 成功加字符串 OK 的分支，" +
                "返回的不是一个列表。这里只对腾讯云开放。"
        )

        if (tried && vm.allowLines.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            Text(
                "可用线路 ${vm.allowLines.size} 条",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Space.xs))
            vm.allowLines.take(60).forEach { l ->
                DataRowDivider(
                    pickString(l, "Name", "name").ifBlank { "-" },
                    listOf(
                        pickString(l, "SubGroup", "sub_group").ifBlank { "-" },
                        "值 ${wmRenderAny(l["Value"] ?: l["value"])}"
                    ).joinToString(" · ")
                )
            }
            if (vm.allowLines.size > 60) {
                Spacer(Modifier.height(Space.xs))
                Text(
                    "仅显示前 60 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else if (tried && err == null) {
            Spacer(Modifier.height(Space.md))
            HintText(
                "没有解析出线路。可能是返回了字符串 OK 而不是列表（厂商判断走错分支），" +
                    "也可能是空域名查询被云厂商拒绝。"
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 4 · 负载均衡（独立的第四层，不属于 DNS 记录）
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun LoadTab(
    vm: WafMasterViewModel,
    submitDone: (String) -> Unit,
    askConfirm: (WmConfirm) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) { if (vm.loadGroups.isEmpty()) vm.loadLoadGroups() }

    val groups = vm.loadGroups.entries.sortedBy { it.key }
    val vendorOptions = listOf("" to "不绑定") +
        wmAuthorizedVendors(vm).map { wmVendorLabel(it) to it }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg,
                top = Space.md, bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                WafCard {
                    SectionHeader(
                        "流量分组",
                        subtitle = "${groups.size} 个分组 · 来自网站聚合",
                        action = { SmallButton("刷新") { vm.loadLoadGroups() } }
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "服务端没有任何分组列表接口。这里的分组 id 是从网站列表的 load_group_id " +
                            "聚合出来的，所以没有任何网站引用的分组不会出现在这里，" +
                            "刚建好但还没绑网站的分组也看不到。",
                        danger = true
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "同样因为没有读取接口，服务端拿不到分组的名称、分流方式与节点，" +
                            "所以「编辑」时表单无法自动回填，需要手工重新输入一遍。"
                    )
                }
            }

            if (groups.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        title = "还没有识别到分组",
                        description = "新建一个分组并把网站绑到它上面之后，它才会出现在这个列表里"
                    )
                }
            }

            items(groups, key = { it.key }) { (id, name) ->
                val isDefault = id == 1
                WafCard {
                    ListRow(
                        icon = if (isDefault) Icons.Filled.Shield else Icons.AutoMirrored.Filled.CallSplit,
                        title = name,
                        subtitle = if (isDefault) {
                            "系统保留分组 · 不可改名、不可删除"
                        } else {
                            "分组 ID $id"
                        },
                        iconTint = if (isDefault) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                        trailing = {
                            StatusChip(
                                if (isDefault) "保留" else "可管理",
                                if (isDefault) ChipTone.WARNING else ChipTone.INFO
                            )
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("查看在线节点", tone = ChipTone.INFO) { vm.getLoadGroupNodes(id) }
                        SmallButton("编辑", tone = ChipTone.INFO) { editId = id }
                        SmallButton("删除", tone = ChipTone.DANGER, enabled = !isDefault) {
                            askConfirm(
                                WmConfirm(
                                    title = "删除负载均衡分组",
                                    message = "确认删除「$name」（ID $id）吗？",
                                    confirmText = "确认删除",
                                    warning = "分组被网站通过 load_group_id 引用，删除会立刻改变这些网站的流量分发。" +
                                        "服务端会先检查是否已绑定网站，已绑定则拒绝删除。" +
                                        "删除后组内节点会被置为未分组，节点本身不会消失。",
                                    onConfirm = {
                                        // load_list 必须是数组，且服务端只读 id 与 load_name 两个字段
                                        vm.deleteLoadBalance(
                                            listOf(mapOf("id" to id, "load_name" to name))
                                        ) { e ->
                                            if (e != null) {
                                                // 全部项都失败时服务端会返回空文案，不能显示空白
                                                vm.error = wmCleanText(e).ifBlank { "删除失败，请重试" }
                                            } else {
                                                submitDone("删除负载均衡分组")
                                            }
                                        }
                                    }
                                )
                            )
                        }
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader("在线节点", subtitle = "get_site_all_node · 当前查询的分组")
                    Spacer(Modifier.height(Space.sm))
                    if (vm.groupNodes.isEmpty()) {
                        Text(
                            "暂无节点。点上面某个分组的「查看在线节点」来查询。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        vm.groupNodes.forEach { n ->
                            DataRowDivider(
                                pickString(n, "remark", "sid").ifBlank { pickString(n, "sid") },
                                listOf(
                                    "sid ${pickString(n, "sid")}",
                                    if (n["itself"].toBoolState()) "本机" else ""
                                ).filter { it.isNotBlank() }.joinToString(" · ")
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "该接口只返回在线节点。离线节点不会出现在这里，" +
                            "所以不能把这份列表直接当作编辑时的完整节点集合回填 —— " +
                            "那会把离线节点从分组里彻底移除。"
                    )
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "关于「默认分组」",
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "分组名填「${WafMasterViewModel.DEFAULT_GROUP_NAME}」时，服务端会在插入后" +
                            "强制把 id 改成 1。这个名字因此被保留给 id=1，" +
                            "天然具备不可改名、不可删除的特殊身份，" +
                            "同时也是唯一允许节点为空的分组名（用于单节点或不分组场景）。" +
                            "不要自建同名分组。"
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "新建分组") }
    }

    if (showCreate) {
        LoadGroupDialog(
            vm = vm,
            id = null,
            vendorOptions = vendorOptions,
            existingNames = vm.loadGroups.filterKeys { it != 1 }.values.toSet(),
            onDismiss = { showCreate = false },
            onDone = { showCreate = false; submitDone("新建负载均衡分组") }
        )
    }

    editId?.let { id ->
        LoadGroupDialog(
            vm = vm,
            id = id,
            vendorOptions = vendorOptions,
            existingNames = vm.loadGroups.filterKeys { it != id && it != 1 }.values.toSet(),
            onDismiss = { editId = null },
            onDone = { editId = null; submitDone("修改负载均衡分组") }
        )
    }
}

/**
 * 负载均衡分组表单，新建与修改共用。
 *
 * 节点选择是这里最麻烦的一处：服务端没有「全部节点」接口，
 * 唯一能拿到 sid 的途径是按 group_id 查在线节点。所以：
 * - 新建时用 group_id = 0 查出所有未分组的在线节点作为候选；
 * - 编辑时用该分组自己的 id 查，但那份列表只含在线节点，
 *   服务端会做节点差分，没提交到的旧节点会被踢出分组，
 *   所以这里把提示摆在最显眼的位置。
 */
@Composable
private fun LoadGroupDialog(
    vm: WafMasterViewModel,
    id: Int?,
    vendorOptions: List<Pair<String, String>>,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    val editing = id != null
    val sourceGroup = id ?: 0
    val isDefault = id == 1

    var err by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var dnsName by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("dns") }
    var corrupt by remember { mutableStateOf(false) }
    var ps by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var extra by remember { mutableStateOf<Map<String, WmNodeExtra>>(emptyMap()) }
    var seeded by remember { mutableStateOf(false) }

    // 打开弹窗时清一次节点列表：这样后面第一次回来的非空结果一定是这次查询的，
    // 不会把上一次查询的残留当成当前分组的节点。
    LaunchedEffect(sourceGroup) {
        seeded = false
        vm.groupNodes = emptyList()
        vm.getLoadGroupNodes(sourceGroup)
    }
    LaunchedEffect(vm.groupNodes) {
        if (!seeded && vm.groupNodes.isNotEmpty()) {
            // 编辑时默认全选（服务端做的是差分，漏掉的节点会被踢出分组）
            picked = if (editing) vm.groupNodes.map { wmSid(it) }.filter { it.isNotBlank() }.toSet()
            else emptySet()
            seeded = true
        }
    }

    FormDialog(
        title = if (editing) "编辑分组 #$id" else "新建负载均衡分组",
        icon = Icons.AutoMirrored.Filled.CallSplit,
        confirmText = if (editing) "保存" else "创建",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val finalName = name.trim()
            val nodes = wmBuildNodes(vm.groupNodes, picked, extra)
            when {
                finalName.isBlank() -> err = "请填写分组名称"
                isDefault && finalName != WafMasterViewModel.DEFAULT_GROUP_NAME ->
                    err = "默认分组名称不允许修改"

                editing && nodes.isEmpty() -> err = "节点不能为空"
                !editing && nodes.isEmpty() && finalName != WafMasterViewModel.DEFAULT_GROUP_NAME ->
                    err = "节点不能为空（只有「${WafMasterViewModel.DEFAULT_GROUP_NAME}」允许为空）"

                finalName in existingNames -> err = "集群名称 $finalName 已存在"
                else -> {
                    val corruptInt = if (corrupt) 1 else 0
                    if (editing) {
                        vm.modifyLoadBalance(
                            id = id!!,
                            loadName = finalName,
                            dnsName = dnsName.trim(),
                            loadMethod = method,
                            corruptCheck = corruptInt,
                            ps = ps.trim(),
                            nodes = nodes
                        ) { e -> if (e == null) onDone() else err = wmCleanText(e) }
                    } else {
                        vm.createLoadBalance(
                            loadName = finalName,
                            dnsName = dnsName.trim(),
                            loadMethod = method,
                            corruptCheck = corruptInt,
                            ps = ps.trim(),
                            nodes = nodes
                        ) { e -> if (e == null) onDone() else err = wmCleanText(e) }
                    }
                }
            }
            null
        }
    ) {
        HintText(
            "服务端没有分组的读取接口，所以这里所有字段都无法自动回填，需要手工重填一遍。" +
                "分组名全局唯一。",
            danger = true
        )
        Spacer(Modifier.height(Space.md))
        LabeledField(
            value = name,
            onValueChange = { name = it; err = null },
            label = "分组名称",
            hint = WafMasterViewModel.DEFAULT_GROUP_NAME,
            enabled = !isDefault
        )
        if (isDefault) {
            Spacer(Modifier.height(Space.xs))
            HintText("id 为 1 的分组名称被锁定，服务端会拒绝任何改名请求。", danger = true)
        } else {
            Spacer(Modifier.height(Space.xs))
            HintText("名称填「${WafMasterViewModel.DEFAULT_GROUP_NAME}」会被服务端强制改成 id=1，请避开。")
        }
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = dnsName,
            onValueChange = { dnsName = it },
            label = "绑定 DNS 厂商",
            options = vendorOptions
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = method,
            onValueChange = { method = it; err = null },
            label = "分流方式",
            options = LOAD_METHOD_OPTIONS
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow(
            title = "容错 / 故障检查",
            checked = corrupt,
            onChange = { corrupt = it },
            description = "下发值为 1 / 0"
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            value = ps,
            onValueChange = { ps = it },
            label = "备注",
            hint = "可选"
        )

        Spacer(Modifier.height(Space.md))
        Text(
            "节点选择",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Space.xs))
        HintText(
            if (editing) {
                "服务端按节点 id 做差分：本次没提交的旧节点会被置为未分组。" +
                    "下面只列出了在线节点，若分组里有离线节点，它会在保存时被移出分组 —— " +
                    "离线节点的 sid 服务端没有接口可查，只能先把节点恢复在线。"
            } else {
                "候选节点来自 group_id=0 的未分组在线节点。真正决定分组归属的是节点 id（sid），" +
                    "IP 等字段只是冗余存下来供回显。"
            },
            danger = true
        )
        Spacer(Modifier.height(Space.sm))
        LoadingBar(vm.loading)
        if (vm.groupNodes.isEmpty()) {
            Text(
                "没有查到候选节点。服务端只返回在线节点，" +
                    "请确认集群里有已上线的节点，或先恢复节点在线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            vm.groupNodes.forEach { n ->
                val sid = wmSid(n)
                if (sid.isBlank()) return@forEach
                val on = sid in picked
                val ex = extra[sid] ?: WmNodeExtra()
                WafCard(contentPadding = rowCardPadding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                picked = if (checked) picked + sid else picked - sid
                            }
                        )
                        Spacer(Modifier.width(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                pickString(n, "remark").ifBlank { sid },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "sid $sid" + if (n["itself"].toBoolState()) " · 本机" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    if (on) {
                        Spacer(Modifier.height(Space.sm))
                        LabeledField(
                            value = ex.ip,
                            onValueChange = { v -> extra = extra + (sid to ex.copy(ip = v)) },
                            label = "节点 IP",
                            hint = "仅作回显，归属靠 sid",
                            keyboardType = KeyboardType.Text
                        )
                        Spacer(Modifier.height(Space.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Box(Modifier.weight(1f)) {
                                LabeledField(
                                    value = ex.weight,
                                    onValueChange = { v -> extra = extra + (sid to ex.copy(weight = v)) },
                                    label = "权重",
                                    hint = "1",
                                    keyboardType = KeyboardType.Number
                                )
                            }
                            Spacer(Modifier.width(Space.sm))
                            Box(Modifier.weight(1f)) {
                                LabeledField(
                                    value = ex.region,
                                    onValueChange = { v -> extra = extra + (sid to ex.copy(region = v)) },
                                    label = "区域",
                                    hint = "区域分流时用"
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.xs))
                        SwitchRow(
                            title = "该节点启用",
                            checked = ex.status == 1,
                            onChange = { c -> extra = extra + (sid to ex.copy(status = if (c) 1 else 0)) },
                            description = "下发值为 1 / 0"
                        )
                    }
                }
                Spacer(Modifier.height(Space.xs))
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * 工具
 * ══════════════════════════════════════════════════════════════════ */

/** 已授权的厂商标识集合。 */
private fun wmAuthorizedVendors(vm: WafMasterViewModel): List<String> =
    vm.dnsList.map { wmVendorOf(it) }.filter { it.isNotBlank() }.distinct()

/** 从 get_dns_list / get_dns_domain_list 的行里取厂商标识。 */
private fun wmVendorOf(row: Map<String, Any?>): String =
    pickString(row, "dns_name", "types", "name").ifBlank { row["value"].asString() }

/** 厂商标识转中文标签。未知值原样返回，不猜测。 */
private fun wmVendorLabel(vendor: String): String = when (vendor) {
    VENDOR_TENCENT -> "腾讯云"
    VENDOR_ALIYUN -> "阿里云"
    "" -> "不设置"
    else -> vendor
}

/** get_dns_manufacturer_list 里的 method 元素。 */
private fun wmMethodLabel(m: Map<String, Any?>): String =
    pickString(m, "chinese_name", "name")

/**
 * 记录状态归一：把接口返回的各种形态统一成布尔。
 *
 * 这是本文件最容易出错的地方 —— 同一件事在不同接口里是三种写法：
 * - get_dns_record 与 get_aliyun_dns_record 返回腾讯云原生枚举 ENABLE / DISABLE；
 * - get_dns_domain_list 的 parse_list 元素返回 1 / 0 整数；
 * - DNSPod 原始记录里还有 Disable 字段（0 表示启用）。
 * 提交时统一由 ViewModel 转回 ENABLE / DISABLE，这里只负责读。
 */
private fun wmToEnabled(raw: Any?): Boolean {
    val s = raw.asString().trim()
    return when {
        s.isBlank() -> true
        s.equals("ENABLE", true) -> true
        s.equals("DISABLE", true) -> false
        s.equals("true", true) -> true
        else -> s == "1"
    }
}

/** 记录 ID。两个云厂商的列表字段名不同，RecordId 还可能是字符串。 */
private fun wmRecordId(r: Map<String, Any?>): String =
    pickString(r, "RecordId", "record_id", "Id")

/** 列表 key：记录 ID 缺失时退化用主机头加类型，避免 LazyColumn key 重复。 */
private fun wmRecordKey(r: Map<String, Any?>): String {
    val id = wmRecordId(r)
    if (id.isNotBlank()) return id
    return pickString(r, "Name", "RR", "name", "rr") + "|" + pickString(r, "Type", "type")
}

/**
 * 记录 ID 转数字串。
 * 阿里云列表返回的是字符串，而服务端结构体里是 uint64，直接回填会被类型校验拒绝。
 */
private fun wmToNumericId(raw: String): String = raw.trim().filter { it.isDigit() }

/** 集群节点 sid。 */
private fun wmSid(n: Map<String, Any?>): String = pickString(n, "sid", "id")

/**
 * 组装 nodes 数组。
 *
 * 真正决定分组归属的是 id（节点 sid），其余字段是冗余回显。
 * 这里只遍历「当前查到的节点」再按勾选过滤，所以离线节点不可能被带进来 ——
 * 这也正是需要在界面上警示的原因。
 */
private fun wmBuildNodes(
    pool: List<Map<String, Any?>>,
    picked: Set<String>,
    extra: Map<String, WmNodeExtra>
): List<Map<String, Any?>> = pool.mapNotNull { n ->
    val sid = wmSid(n)
    if (sid.isBlank() || sid !in picked) return@mapNotNull null
    val ex = extra[sid] ?: WmNodeExtra()
    // 权重必须包一层括号：中缀 to 的优先级高于 ?:,不括起来会被解析成 (权重 to null) ?: 1
    mapOf<String, Any?>(
        "id" to sid,
        "ip" to ex.ip.trim(),
        "weight" to (ex.weight.trim().toIntOrNull() ?: 1),
        "region" to ex.region.trim(),
        "status" to ex.status,
        "ps" to pickString(n, "remark"),
        "is_parse" to false,
        "create_time" to 0
    )
}

/** 多行 / 逗号 / 空格分隔的输入统一拆成数组。 */
private fun wmSplitList(text: String): List<String> =
    text.split('\n', ',', '，', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** pickString 的可空版本，用于「新增时为 null」的编辑表单。 */
private fun wmPick(map: Map<String, Any?>?, vararg keys: String): String =
    if (map == null) "" else pickString(map, *keys)

/**
 * 错误文案清洗。
 *
 * 两件事：
 * 1. 服务端部分文案里带 HTML 换行标签，直接塞进 Text 会被当字面量显示；
 * 2. 全部项都失败时服务端会返回空文案，此时要给出兜底而不是显示空白。
 * 注意不能对文案做中文匹配 —— 英文语言下服务端会把错误替换成字面量 Fail。
 */
private fun wmCleanText(msg: String?): String {
    val raw = msg.orEmpty()
    if (raw.isBlank()) return "操作失败，服务端未返回原因"
    return raw.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "操作失败，服务端未返回原因" }
}

/** create_time 可能是时间戳也可能是字符串，能认就格式化，认不出就原样显示。 */
private fun wmTimeText(v: Any?): String {
    val s = v.asString().trim()
    if (s.isBlank()) return "-"
    val ms = s.toLongOrNull() ?: return s
    return if (ms > 0) formatTime(ms * 1000) else s
}

/** 后端 snake_case 字段名转可读标签。 */
private fun wmHumanKey(k: String): String = when (k) {
    "DomainId" -> "域名 ID"
    "Name" -> "域名"
    "Status" -> "状态"
    "DNSStatus" -> "解析状态"
    "Grade" -> "套餐等级"
    "GradeName" -> "套餐名称"
    "RecordCount" -> "记录数"
    "CreatedOn" -> "创建时间"
    "UpdatedOn" -> "更新时间"
    "RecordId" -> "记录 ID"
    "TTL" -> "TTL"
    "MX" -> "邮件交换"
    "Lock" -> "锁定状态"
    "Vcode" -> "验证码"
    "Registry" -> "注册商"
    "CreateTime" -> "创建时间"
    "ExpireTime" -> "到期时间"
    "OutOfDate" -> "是否过期"
    "RegistrarName" -> "注册商名称"
    else -> k
}

/** 列表 / 对象值统一渲染。 */
private fun wmRenderAny(v: Any?): String = when (v) {
    null -> "-"
    is List<*> -> v.joinToString(" · ") { wmRenderAny(it) }.ifBlank { "-" }
    is Map<*, *> -> v.entries.joinToString(" · ") { "${wmHumanKey(it.key.toString())}=${wmRenderAny(it.value)}" }
        .ifBlank { "-" }

    is Boolean -> if (v) "是" else "否"
    else -> v.toString().ifBlank { "-" }
}
