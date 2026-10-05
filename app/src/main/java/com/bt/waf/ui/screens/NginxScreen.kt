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
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import com.bt.waf.util.pickString
import com.bt.waf.util.s
import com.bt.waf.viewmodel.NginxViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ══════════════════════════════════════════════════════════════════
 * 宝塔云 WAF · 反向代理管理
 *
 * 对应 NginxViewModel 的全部接口，分五个 Tab：
 *   站点 / 域名 / 证书 / 负载均衡 / 设置
 *
 * 三条贯穿全文件的约定：
 *
 * 1. 写操作的错误一律渲染在 FormDialog 内部的 error 参数里。
 *    Snackbar 挂在 Scaffold 上而 AlertDialog 盖在其上，写在 Snackbar 里的
 *    错误 100% 不可见 —— 这是本项目已修过的坑，不要退回 Snackbar。
 *
 * 2. 后端多个写接口是「假成功」：吞掉错误仍返回 code=0。典型如
 *    delTcpLoadBalance 传不存在的名字也返回成功。所以写成功后的文案统一
 *    走 submitDone，由它重新拉取列表比对后再提示，而不是直接说「操作成功」。
 *
 * 3. 数组类参数由 ViewModel 封装，界面只管把 List 传下去。
 * ══════════════════════════════════════════════════════════════════ */

private enum class NgTab(val label: String) {
    SITES("站点"), DOMAIN("域名"), SSL("证书"), LB("负载均衡"), SETTINGS("设置")
}

/** 站点详情抽屉内的子 Tab。 */
private enum class SheetTab(val label: String) {
    INFO("基本信息"), SOURCE("回源"), DOMAIN("域名"), LOG("日志")
}

/** 后端把「通配所有域名」的站点占位成一个固定 site_id，不允许加删域名。 */
private const val WILDCARD_SITE = "default_wildcard_domain_server"

private const val VERIFY_NONE = 0
private const val VERIFY_SITE = 1
private const val VERIFY_LB = 2

private val PROTOCOL_OPTIONS = listOf("tcp" to "tcp", "udp" to "udp", "tcp/udp" to "tcp/udp")
private val LISTEN_ADDRESS_OPTIONS = listOf("127.0.0.1" to "127.0.0.1", "0.0.0.0" to "0.0.0.0")
private val POLLING_OPTIONS = listOf("ip_hash" to "ip_hash", "least_conn" to "least_conn", "random" to "random")
private val LOG_TYPE_OPTIONS = listOf("access" to "access（访问）", "error" to "error（错误）", "slow" to "slow（慢请求）")

/**
 * user_config 会拒绝的 lua 指令。
 * 提交前在本地先拦一次并指出具体命中的指令名，比让后端只回一句
 * 「用户配置文件中包含非法指令」要可用得多。
 */
private val BANNED_LUA_DIRECTIVES = listOf(
    "init_by_lua", "init_worker_by_lua", "ssl_certificate_by_lua", "set_by_lua",
    "rewrite_by_lua", "access_by_lua", "content_by_lua", "header_filter_by_lua",
    "body_filter_by_lua", "log_by_lua", "balancer_by_lua", "preread_by_lua"
)

/** 通用二次确认任务。用类承载是为了能把 lambda 一起存进 state。 */
private class ConfirmTask(
    val title: String,
    val message: String,
    val confirmText: String = "确认",
    val danger: Boolean = true,
    val warning: String? = null,
    val onConfirm: () -> Unit
)

/** 负载均衡 / 回源节点的编辑态。 */
private data class NodeDraft(
    val address: String = "",
    val port: String = "",
    val weight: String = "1",
    val maxFails: String = "3",
    val failTimeout: String = "10",
    val status: String = "1",
    val ps: String = "",
    val follow: Boolean = false
)

/* ══════════════════════════════════════════════════════════════════
 * 入口
 * ══════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NginxScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: NginxViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(NgTab.SITES) }
    var search by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var detail by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var confirm by remember { mutableStateOf<ConfirmTask?>(null) }

    // 域名 / 证书 / 设置三个 Tab 共用的当前站点
    var pickSiteId by remember { mutableStateOf("") }

    // 假成功校验：写操作提交后延迟比对列表，确认真实生效
    var verifyKind by remember { mutableStateOf(VERIFY_NONE) }
    var verifyName by remember { mutableStateOf("") }
    var verifyTick by remember { mutableStateOf(0) }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    /** 写操作成功后的统一文案：不承诺成功，只提示已重新拉取。 */
    fun submitDone(what: String) {
        toast("$what 已提交，列表已重新拉取，请确认结果是否变化")
    }

    fun askConfirm(task: ConfirmTask) {
        confirm = task
    }

    LaunchedEffect(Unit) { vm.getSites(page = 1, pageSize = 50, search = "") }

    // 站点列表回来后自动选一个，省得用户每次手动点下拉
    LaunchedEffect(vm.sites) {
        if (pickSiteId.isBlank()) {
            pickSiteId = vm.sites.firstOrNull()?.let { siteIdOf(it) }.orEmpty()
        }
    }

    // 假成功校验：给后端留出重载 nginx 与返回的时间再比对列表
    LaunchedEffect(verifyTick) {
        if (verifyTick == 0) return@LaunchedEffect
        delay(900)
        when (verifyKind) {
            VERIFY_SITE -> {
                val gone = vm.sites.none { siteIdOf(it) == verifyName }
                verifyKind = VERIFY_NONE
                toast(
                    if (gone) "站点已删除"
                    else "后端返回成功，但站点仍在列表中，请到服务器确认是否真的删除"
                )
            }

            VERIFY_LB -> {
                val gone = !vm.tcpLoadBalances.containsKey(verifyName)
                verifyKind = VERIFY_NONE
                toast(
                    if (gone) "端口转发已删除"
                    else "后端返回成功，但该转发配置仍存在，请检查 load_balance_name 是否正确"
                )
            }
        }
    }

    // SubPageScaffold 而不是 Scaffold：内层 Scaffold 会再消费一遍
    // windowInsets，顶部多出约 250px 空白（详见 SubPageScaffold 注释）。
    SubPageScaffold(snackbarHost = snackbar) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            WafTabRow(
                labels = NgTab.entries.map { it.label },
                selectedIndex = NgTab.entries.indexOf(tab),
                onSelect = { idx -> tab = NgTab.entries[idx] },
                containerColor = MaterialTheme.colorScheme.surface,
            )
            LoadingBar(vm.loading)
            ErrorBanner(vm.error) {
                when (tab) {
                    NgTab.SITES -> vm.getSites(page = page, pageSize = 50, search = search)
                    NgTab.DOMAIN -> vm.getAllDomain()
                    NgTab.SSL -> vm.getAllSsl()
                    NgTab.LB -> vm.getTcpLoadBalance()
                    NgTab.SETTINGS -> if (pickSiteId.isNotBlank()) vm.getSiteSetting(pickSiteId)
                }
            }

            when (tab) {
                NgTab.SITES -> SitesTab(
                    vm = vm,
                    search = search,
                    page = page,
                    onSearch = { search = it; page = 1; vm.getSites(page = 1, pageSize = 50, search = it) },
                    onPage = { p -> page = p; vm.getSites(page = p, pageSize = 50, search = search) },
                    onOpenDetail = { detail = it },
                    onConfirm = ::askConfirm,
                    toast = ::toast,
                    submitDone = ::submitDone,
                    onVerifySiteDelete = { id ->
                        verifyKind = VERIFY_SITE
                        verifyName = id
                        verifyTick++
                    }
                )

                NgTab.DOMAIN -> DomainsTab(vm, pickSiteId, { pickSiteId = it }, ::toast, ::submitDone, ::askConfirm)
                NgTab.SSL -> SslTab(vm, pickSiteId, { pickSiteId = it }, ::toast, ::submitDone, ::askConfirm)
                NgTab.LB -> LbTab(vm, ::toast, ::submitDone, ::askConfirm) { name ->
                    verifyKind = VERIFY_LB
                    verifyName = name
                    verifyTick++
                }

                NgTab.SETTINGS -> SettingsTab(vm, pickSiteId, { pickSiteId = it }, ::toast, ::submitDone, ::askConfirm)
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
                    tint = if (c.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(c.title) },
            text = {
                Column {
                    Text(c.message, style = MaterialTheme.typography.bodyMedium)
                    if (c.warning != null) {
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

    detail?.let { site ->
        SiteDetailSheet(
            vm = vm,
            site = site,
            onDismiss = { detail = null },
            onConfirm = ::askConfirm,
            toast = ::toast,
            submitDone = ::submitDone,
            onDeleted = { id ->
                detail = null
                verifyKind = VERIFY_SITE
                verifyName = id
                verifyTick++
            }
        )
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 1 · 站点
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun SitesTab(
    vm: NginxViewModel,
    search: String,
    page: Int,
    onSearch: (String) -> Unit,
    onPage: (Int) -> Unit,
    onOpenDetail: (Map<String, Any?>) -> Unit,
    onConfirm: (ConfirmTask) -> Unit,
    toast: (String) -> Unit,
    submitDone: (String) -> Unit,
    onVerifySiteDelete: (String) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf<Map<String, Any?>?>(null) }

    val totalPages = if (vm.siteTotal <= 0) 1 else (vm.siteTotal + 49) / 50

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
                        "反代站点",
                        subtitle = "共 ${vm.siteTotal} 个 · 当前第 $page / $totalPages 页",
                        action = {
                            SmallButton("刷新") { vm.getSites(page = page, pageSize = 50, search = search) }
                        }
                    )
                    Spacer(Modifier.height(Space.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabeledField(
                            value = search,
                            onValueChange = onSearch,
                            label = "搜索站点",
                            hint = "域名关键字，回车或点右侧搜索",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(Space.sm))
                        SmallButton("搜索", tone = ChipTone.INFO) { onSearch(search) }
                    }
                }
            }

            if (vm.sites.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Language,
                        title = "还没有反代站点",
                        description = "新建一个反代站点，WAF 会在流量回源前先做一次安全过滤",
                        actionText = "刷新列表",
                        onAction = { vm.getSites(page = 1, pageSize = 50, search = search) }
                    )
                }
            }

            items(vm.sites) { site ->
                WafCard(contentPadding = rowCardPadding) {
                    ListRow(
                        icon = Icons.Filled.Language,
                        title = siteNameOf(site),
                        subtitle = siteSubtitle(site),
                        iconTint = if (site["is_https"].toBoolState()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                        onClick = { onOpenDetail(site) },
                        trailing = {
                            if (site["is_https"].toBoolState()) {
                                StatusChip("HTTPS", ChipTone.SUCCESS)
                            } else {
                                StatusChip("HTTP", ChipTone.NEUTRAL)
                            }
                        }
                    )
                }
            }

            if (totalPages > 1) {
                item {
                    WafCard {
                        // 分页栏三段式：左按钮 / 中页码 / 右按钮。
                        // 之前用 Spacer(weight(1f)) 把「下一页」推到右边，
                        // 页码就紧贴在「上一页」右边而不是几何中心 ——
                        // 页码越宽偏移越明显，视觉上就是「不对称」。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallButton("上一页", enabled = page > 1) { onPage(page - 1) }
                            Box(
                                Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$page / $totalPages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SmallButton("下一页", enabled = page < totalPages) { onPage(page + 1) }
                        }
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "危险操作",
                        subtitle = "删除会连带清理反代配置与证书，并重载 nginx",
                        accent = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "删除全部站点不可撤销：后端会逐个删除且不中断，失败站点只会被跳过，" +
                            "同时不写操作日志、不备份 WAF 配置。",
                        danger = true
                    )
                    Spacer(Modifier.height(Space.md))
                    SmallButton(
                        "删除全部站点",
                        tone = ChipTone.DANGER,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        onConfirm(
                            ConfirmTask(
                                title = "删除全部站点",
                                message = "确认删除 WAF 下所有反代站点吗？此操作没有第二次确认入口。",
                                confirmText = "全部删除",
                                warning = "该操作不可撤销，也不会留下操作日志。建议先逐个删除你需要保留的站点。",
                                onConfirm = {
                                    vm.deleteAllSite { e ->
                                        if (e != null) vm.error = e else submitDone("删除全部站点")
                                    }
                                }
                            )
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "新增站点") }
    }

    if (showCreate) {
        CreateSiteDialog(
            vm = vm,
            onDismiss = { showCreate = false },
            onDone = { showCreate = false; toast("站点已创建") }
        )
    }

    showEdit?.let { site ->
        EditSiteDialog(
            vm = vm,
            site = site,
            onDismiss = { showEdit = null },
            onDone = { showEdit = null; submitDone("修改站点") }
        )
    }
}

@Composable
private fun CreateSiteDialog(vm: NginxViewModel, onDismiss: () -> Unit, onDone: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    val f = remember {
        mutableStateMapOf(
            "sitename" to "", "domain" to "", "ip_list" to "", "host" to "",
            "privkey" to "", "fullchain" to "", "polling_algorithm" to "ip_hash"
        )
    }
    var isHttps by remember { mutableStateOf(false) }
    var cdn by remember { mutableStateOf(false) }

    FormDialog(
        title = "新增反代站点",
        icon = Icons.Filled.AddLink,
        confirmText = "创建",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val sitename = f["sitename"].orEmpty().trim()
            val domains = splitList(f["domain"].orEmpty())
            val ips = splitList(f["ip_list"].orEmpty())
            val algo = f["polling_algorithm"].orEmpty().ifBlank { "ip_hash" }
            when {
                sitename.isBlank() -> err = "请填写站点名称"
                domains.isEmpty() -> err = "请至少填写一个域名"
                ips.isEmpty() -> err = "请至少填写一个回源 IP"
                // host 必传但允许空串，空串时后端自动填 $host
                algo.isBlank() -> err = "轮询算法不能为空"
                isHttps && (f["privkey"].isNullOrBlank() || f["fullchain"].isNullOrBlank()) ->
                    err = "开启 HTTPS 时必须同时提供私钥与证书链"

                else -> vm.createSite(
                    sitename = sitename,
                    domain = domains,
                    ipList = ips,
                    isHttps = isHttps,
                    privkey = f["privkey"].orEmpty(),
                    fullchain = f["fullchain"].orEmpty(),
                    cdn = cdn,
                    pollingAlgorithm = algo,
                    host = f["host"].orEmpty().trim()
                ) { e -> if (e == null) onDone() else err = e }
            }
            null
        }
    ) {
        HintText("域名与回源 IP 都会以 JSON 数组提交。站点名里含冒号时后端会额外放行该端口。")
        Spacer(Modifier.height(Space.md))
        LabeledField(f["sitename"].orEmpty(), { f["sitename"] = it; err = null }, "站点名称", "example.com")
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["domain"].orEmpty(), { f["domain"] = it }, "绑定域名",
            "每行一个，或用逗号 / 空格分隔", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["ip_list"].orEmpty(), { f["ip_list"] = it }, "回源 IP 列表",
            "每行一个 IP:port，如 10.0.0.5:8080", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = f["polling_algorithm"].orEmpty(),
            onValueChange = { f["polling_algorithm"] = it },
            label = "轮询算法",
            options = POLLING_OPTIONS
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["host"].orEmpty(), { f["host"] = it }, "回源 Host",
            "留空则由后端自动填 \$host"
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow("启用 HTTPS", isHttps, { isHttps = it }, description = "需要同时填写私钥与证书链")
        SwitchRow("接入 CDN", cdn, { cdn = it }, description = "开启后按 CDN 场景生成回源规则")
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["privkey"].orEmpty(), { f["privkey"] = it }, "私钥",
            "PEM 原文，不启用 HTTPS 可留空", singleLine = false, isPassword = true
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["fullchain"].orEmpty(), { f["fullchain"] = it }, "证书链",
            "PEM 原文，不启用 HTTPS 可留空", singleLine = false
        )
    }
}

@Composable
private fun EditSiteDialog(
    vm: NginxViewModel,
    site: Map<String, Any?>,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    val f = remember(site) {
        mutableStateMapOf(
            "sitename" to siteNameOf(site),
            "domain" to domainTextOf(site),
            "ip_list" to ipListTextOf(site),
            "polling_algorithm" to (pickString(site, "polling_algorithm").ifBlank { "ip_hash" })
        )
    }
    var isHttps by remember(site) { mutableStateOf(site["is_https"].toBoolState()) }
    var cdn by remember(site) { mutableStateOf(site["cdn"].toBoolState() || site["is_cdn"].toBoolState()) }

    FormDialog(
        title = "修改站点",
        icon = Icons.Filled.Edit,
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val siteId = siteIdOf(site)
            val sitename = f["sitename"].orEmpty().trim()
            val domains = splitList(f["domain"].orEmpty())
            val ips = splitList(f["ip_list"].orEmpty())
            val algo = f["polling_algorithm"].orEmpty().ifBlank { "ip_hash" }
            when {
                siteId.isBlank() -> err = "该站点没有 site_id，无法修改"
                sitename.isBlank() -> err = "请填写站点名称"
                domains.isEmpty() -> err = "请至少保留一个域名"
                ips.isEmpty() -> err = "请至少保留一个回源 IP"
                !isHttps -> err = "modify_site 传 is_https=false 关不掉 SSL。请先在上方确认，再改用「移除站点 SSL」。"

                else -> vm.modifySite(
                    siteId = siteId,
                    sitename = sitename,
                    domain = domains,
                    ipList = ips,
                    isHttps = isHttps,
                    // 后端固定以空证书名调用 ModifySiteJson 且不读旧值，
                    // 所以这里必须原样回填，缺证书会把站点 HTTPS 配置清掉。
                    privkey = pickString(site, "privkey"),
                    fullchain = pickString(site, "fullchain"),
                    cdn = cdn,
                    pollingAlgorithm = algo
                ) { e -> if (e == null) onDone() else err = e }
            }
            null
        }
    ) {
        HintText("修改站点没有 host 参数；后端固定用空证书名调用，证书会被写到证书目录根部。")
        Spacer(Modifier.height(Space.sm))
        HintText("关不掉 SSL：is_https 传 false 时后端不会改 IsSSL 字段，必须改用「移除站点 SSL」。", danger = true)
        Spacer(Modifier.height(Space.md))
        LabeledField(f["sitename"].orEmpty(), { f["sitename"] = it; err = null }, "站点名称")
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["domain"].orEmpty(), { f["domain"] = it }, "绑定域名", "每行一个", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            f["ip_list"].orEmpty(), { f["ip_list"] = it }, "回源 IP 列表", "每行一个 IP:port", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            value = f["polling_algorithm"].orEmpty(),
            onValueChange = { f["polling_algorithm"] = it },
            label = "轮询算法",
            options = POLLING_OPTIONS
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow("启用 HTTPS", isHttps, { isHttps = it }, description = "取消勾选无法关闭 SSL")
        SwitchRow("接入 CDN", cdn, { cdn = it })
    }
}

/* ══════════════════════════════════════════════════════════════════
 * 站点详情抽屉
 * ══════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteDetailSheet(
    vm: NginxViewModel,
    site: Map<String, Any?>,
    onDismiss: () -> Unit,
    onConfirm: (ConfirmTask) -> Unit,
    toast: (String) -> Unit,
    submitDone: (String) -> Unit,
    onDeleted: (String) -> Unit
) {
    val siteId = siteIdOf(site)
    var tab by remember { mutableStateOf(SheetTab.INFO) }
    var auth by remember { mutableStateOf<Boolean?>(null) }
    var showNode by remember { mutableStateOf<NodeForm?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var showDomain by remember { mutableStateOf(false) }
    var showPolling by remember { mutableStateOf(false) }

    LaunchedEffect(siteId) {
        if (siteId.isBlank()) return@LaunchedEffect
        vm.getReturnSource(siteId)
        vm.getDomain(siteId)
        vm.getSslInfo(siteId)
        vm.checkReturnSourceAuth { ok, _ -> auth = ok }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
            Text(
                siteNameOf(site),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (siteId.isNotBlank()) {
                Text(
                    "site_id: $siteId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(Space.md))

            WafTabRow(
                labels = SheetTab.entries.map { it.label },
                selectedIndex = SheetTab.entries.indexOf(tab),
                onSelect = { idx -> tab = SheetTab.entries[idx] },
                containerColor = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.height(Space.md))
            LoadingBar(vm.loading)

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (tab) {
                    SheetTab.INFO -> {
                        WafCard {
                            SectionHeader("站点配置", subtitle = "来自 get_sites")
                            Spacer(Modifier.height(Space.sm))
                            site.forEach { (k, v) -> DataRowDivider(ngHumanKey(k), renderAny(v)) }
                        }
                        Spacer(Modifier.height(Space.sm))
                        WafCard {
                            SectionHeader("操作", accent = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(Space.sm))
                            CompactNavRow(Icons.Filled.DriveFileRenameOutline, "仅修改站点名称", onClick = { showRename = true })
                            CompactNavRow(Icons.Filled.Edit, "修改站点配置", onClick = { showEdit = true })
                            CompactNavRow(Icons.Filled.DeleteForever, "删除站点", iconTint = MaterialTheme.colorScheme.error) {
                                onConfirm(
                                    ConfirmTask(
                                        title = "删除站点",
                                        message = "确认删除「${siteNameOf(site)}」吗？",
                                        confirmText = "确认删除",
                                        warning = "删除会连带清理反代配置与证书并重载 nginx，该站会立即从线上下线，且无法恢复。",
                                        onConfirm = {
                                            vm.deleteSite(siteId) { e ->
                                                if (e != null) vm.error = e else onDeleted(siteId)
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    }

                    SheetTab.SOURCE -> {
                        WafCard {
                            SectionHeader(
                                "回源配置",
                                subtitle = "get_return_source",
                                action = {
                                    if (auth != null) {
                                        StatusChip(if (auth == true) "已授权" else "未授权", if (auth == true) ChipTone.SUCCESS else ChipTone.WARNING)
                                    }
                                }
                            )
                            Spacer(Modifier.height(Space.sm))
                            DataRowDivider("upstream 名称", pickString(vm.returnSource, "name"))
                            DataRowDivider("轮询算法", pickString(vm.returnSource, "polling_algorithm"))
                            DataRowDivider("回源 Host", pickString(vm.returnSource, "host"))
                            DataRowDivider("备注", pickString(vm.returnSource, "enable_note"))
                            DataRowDivider("自动巡检解析", if (vm.returnSource["check_dns"].toBoolState()) "已开启" else "未开启")
                            Spacer(Modifier.height(Space.sm))
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                SmallButton("整体重设", tone = ChipTone.INFO) { showPolling = true }
                                SmallButton("添加节点", tone = ChipTone.INFO) {
                                    showNode = NodeForm(siteId = siteId, draft = NodeDraft(), editing = false)
                                }
                            }
                            Spacer(Modifier.height(Space.xs))
                            HintText("回源相关高级功能需要三版本授权，未授权时按钮仍可点但后端会拒绝。")
                        }
                        Spacer(Modifier.height(Space.sm))
                        val nodes = returnSourceNodes(vm.returnSource)
                        WafCard {
                            SectionHeader("回源节点", subtitle = "${nodes.size} 个")
                            Spacer(Modifier.height(Space.sm))
                            if (nodes.isEmpty()) {
                                Text(
                                    "该站点还没有回源节点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                nodes.forEach { n ->
                                    val sid = pickString(n, "source_id", "id")
                                    val addr = pickString(n, "address", "source_ip", "ip")
                                    Column(Modifier.padding(vertical = Space.xs)) {
                                        DataRowDivider(
                                            addr.ifBlank { "(未知地址)" },
                                            listOf(
                                                pickString(n, "port").let { if (it.isBlank()) "" else "端口 $it" },
                                                "权重 ${pickString(n, "weight").ifBlank { "1" }}",
                                                if (pickString(n, "status") == "0") "已停用" else "启用中"
                                            ).filter { it.isNotBlank() }.joinToString(" · ")
                                        )
                                        if (sid.isNotBlank()) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                                SmallButton("编辑", tone = ChipTone.INFO) {
                                                    showNode = NodeForm(
                                                        siteId = siteId,
                                                        draft = NodeDraft(
                                                            address = addr,
                                                            port = pickString(n, "port"),
                                                            weight = pickString(n, "weight").ifBlank { "1" },
                                                            maxFails = pickString(n, "max_fails").ifBlank { "3" },
                                                            failTimeout = pickString(n, "fail_timeout").ifBlank { "10" },
                                                            status = pickString(n, "status").ifBlank { "1" },
                                                            ps = pickString(n, "ps")
                                                        ),
                                                        editing = true,
                                                        sourceId = sid,
                                                        oldStatus = (n["status"]?.asString()?.toIntOrNull() ?: 1)
                                                    )
                                                }
                                                SmallButton("删除", tone = ChipTone.DANGER) {
                                                    onConfirm(
                                                        ConfirmTask(
                                                            title = "删除回源节点",
                                                            message = "确认从「${siteNameOf(site)}」删除回源节点 $addr 吗？",
                                                            warning = "删除后会重载 nginx，该节点上的流量将不再被转发。",
                                                            onConfirm = {
                                                                // source_ip 只用于写操作日志，但仍必须传，否则参数错误
                                                                vm.delReturnSourceIp(siteId, sid, addr) { e ->
                                                                    if (e != null) vm.error = e else submitDone("删除回源节点")
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

                    SheetTab.DOMAIN -> {
                        WafCard {
                            SectionHeader(
                                "站点域名",
                                subtitle = "${vm.siteDomains.size} 条 · get_domain",
                                action = {
                                    SmallButton("新增", tone = ChipTone.INFO) { showDomain = true }
                                }
                            )
                            Spacer(Modifier.height(Space.sm))
                            if (vm.siteDomains.isEmpty()) {
                                Text(
                                    "该站点还没有绑定域名",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                vm.siteDomains.forEach { d ->
                                    DataRowDivider(
                                        pickString(d, "domain").ifBlank { "(空)" },
                                        pickString(d, "port").let { if (it.isBlank()) "-" else "端口 $it" }
                                    )
                                }
                            }
                        }
                    }

                    SheetTab.LOG -> {
                        LogPanel(vm = vm, siteId = siteId, onConfirm = onConfirm, submitDone = submitDone)
                    }
                }
                Spacer(Modifier.height(Space.lg))
            }
        }
    }

    showNode?.let { form ->
        ReturnSourceNodeDialog(
            vm = vm,
            form = form,
            onDismiss = { showNode = null },
            onDone = { showNode = null; submitDone(if (form.editing) "修改回源节点" else "添加回源节点") }
        )
    }

    if (showPolling && siteId.isNotBlank() && vm.returnSource["polling_algorithm"].asString().isNotBlank()) {
        PollingDialog(
            vm = vm,
            siteId = siteId,
            current = vm.returnSource["polling_algorithm"].asString(),
            currentHost = vm.returnSource["host"].asString(),
            onDismiss = { showPolling = false },
            onDone = { showPolling = false; submitDone("重设回源") }
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(siteNameOf(site)) }
        var err by remember { mutableStateOf<String?>(null) }
        FormDialog(
            title = "修改站点名称",
            icon = Icons.Filled.DriveFileRenameOutline,
            busy = vm.saving,
            error = err,
            onDismiss = { showRename = false },
            onConfirm = {
                err = null
                if (name.isBlank()) err = "请填写站点名称"
                else vm.modifySiteInfo(siteId, name.trim()) { e ->
                    if (e == null) { showRename = false; toast("站点名称已更新") } else err = e
                }
                null
            }
        ) {
            HintText("只改站点名，不动域名与回源配置。")
            Spacer(Modifier.height(Space.md))
            LabeledField(name, { name = it; err = null }, "站点名称")
        }
    }

    if (showEdit) {
        EditSiteDialog(
            vm = vm,
            site = site,
            onDismiss = { showEdit = false },
            onDone = { showEdit = false; submitDone("修改站点") }
        )
    }

    if (showDomain) {
        AddDomainDialog(
            vm = vm,
            siteId = siteId,
            onDismiss = { showDomain = false },
            onDone = { showDomain = false; submitDone("新增域名") }
        )
    }
}

private class NodeForm(
    val siteId: String,
    val draft: NodeDraft,
    val editing: Boolean,
    val sourceId: String = "",
    val oldStatus: Int = 1
)

@Composable
private fun ReturnSourceNodeDialog(
    vm: NginxViewModel,
    form: NodeForm,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    var d by remember { mutableStateOf(form.draft) }

    FormDialog(
        title = if (form.editing) "修改回源节点" else "添加回源节点",
        icon = Icons.Filled.Lan,
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val addr = d.address.trim()
            when {
                form.siteId.isBlank() -> err = "缺少 site_id"
                addr.isBlank() -> err = "请填写源站地址"
                // 这四个字段后端按字符串解析，传非数字会 panic
                d.weight.toIntOrNull() == null -> err = "权重必须是非负整数"
                d.maxFails.toIntOrNull() == null -> err = "最大失败次数必须是非负整数"
                d.failTimeout.toIntOrNull() == null -> err = "失败超时必须是非负整数"
                d.status != "0" && d.status != "1" -> err = "状态只能是 0 或 1"

                else -> if (form.editing) {
                    vm.modifyReturnSourceIp(
                        siteId = form.siteId,
                        sourceId = form.sourceId,
                        sourceIp = addr,
                        pollingAlgorithm = "ip_hash",
                        host = "",
                        maxFails = d.maxFails,
                        failTimeout = d.failTimeout,
                        weight = d.weight,
                        status = d.status.toInt(),
                        oldStatus = form.oldStatus,
                        ps = d.ps
                    ) { e -> if (e == null) onDone() else err = e }
                } else {
                    vm.addReturnSourceIp(
                        siteId = form.siteId,
                        sourceIp = addr,
                        pollingAlgorithm = "ip_hash",
                        host = "",
                        maxFails = d.maxFails,
                        failTimeout = d.failTimeout,
                        weight = d.weight,
                        status = d.status.toInt(),
                        ps = d.ps
                    ) { e -> if (e == null) onDone() else err = e }
                }
            }
            null
        }
    ) {
        HintText("weight / max_fails / fail_timeout 必须是字符串，status 必须是整数，传错类型后端会 panic。")
        Spacer(Modifier.height(Space.md))
        LabeledField(d.address, { d = d.copy(address = it); err = null }, "源站地址", "10.0.0.5 或 10.0.0.5:8080")
        Spacer(Modifier.height(Space.sm))
        NodeParamFields(d) { d = it }
    }
}

@Composable
private fun PollingDialog(
    vm: NginxViewModel,
    siteId: String,
    current: String,
    currentHost: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    var algo by remember { mutableStateOf(current) }
    var host by remember { mutableStateOf(currentHost) }

    FormDialog(
        title = "整体重设回源",
        icon = Icons.Filled.SettingsEthernet,
        confirmText = "重设",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            if (algo.isBlank()) {
                // 后端对空轮询算法直接返回参数错误，不接受空串
                err = "轮询算法不能为空"
            } else {
                vm.returnSource(siteId, algo, host.trim()) { e -> if (e == null) onDone() else err = e }
            }
            null
        }
    ) {
        HintText("整体重写回源配置并重载 nginx，失败时后端会尝试回滚。轮询算法不能为空。")
        Spacer(Modifier.height(Space.md))
        DropdownField(algo, { algo = it }, "轮询算法", POLLING_OPTIONS)
        Spacer(Modifier.height(Space.sm))
        LabeledField(host, { host = it }, "回源 Host", "留空则沿用默认")
    }
}

/** 回源节点与 TCP 节点共用的参数编辑区。 */
@Composable
private fun NodeParamFields(d: NodeDraft, onChange: (NodeDraft) -> Unit) {
    LabeledField(
        d.port, { onChange(d.copy(port = it)) }, "端口（可选）",
        "TCP 负载均衡必填，回源节点可留空", keyboardType = KeyboardType.Number
    )
    Spacer(Modifier.height(Space.sm))
    GridRow(gap = Space.sm) {
        GridCell {
            LabeledField(
                d.weight, { onChange(d.copy(weight = it)) }, "权重",
                "1", keyboardType = KeyboardType.Number
            )
        }
        GridCell {
            LabeledField(
                d.maxFails, { onChange(d.copy(maxFails = it)) }, "最大失败",
                "3", keyboardType = KeyboardType.Number
            )
        }
        GridCell {
            LabeledField(
                d.failTimeout, { onChange(d.copy(failTimeout = it)) }, "失败超时",
                "10", keyboardType = KeyboardType.Number
            )
        }
    }
    Spacer(Modifier.height(Space.sm))
    DropdownField(
        d.status, { onChange(d.copy(status = it)) }, "状态",
        listOf("1" to "启用", "0" to "停用")
    )
    Spacer(Modifier.height(Space.sm))
    LabeledField(d.ps, { onChange(d.copy(ps = it)) }, "备注", "可选")
    SwitchRow("地址跟随", d.follow, { onChange(d.copy(follow = it)) }, description = "跟随 301/302 跳转到源站 IP")
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 2 · 域名
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun DomainsTab(
    vm: NginxViewModel,
    siteId: String,
    onPickSite: (String) -> Unit,
    toast: (String) -> Unit,
    submitDone: (String) -> Unit,
    onConfirm: (ConfirmTask) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var showProbe by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (vm.allDomains.isEmpty()) vm.getAllDomain() }
    LaunchedEffect(siteId) { if (siteId.isNotBlank()) vm.getDomain(siteId) }

    val wildcard = siteId == WILDCARD_SITE

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
                        "选择站点",
                        subtitle = "共 ${vm.allDomains.size} 个站点 · get_all_domain",
                        action = { SmallButton("刷新") { vm.getAllDomain() } }
                    )
                    Spacer(Modifier.height(Space.md))
                    if (vm.allDomains.isEmpty()) {
                        HintText("站点列表为空，请先到「站点」页新建一个反代站点。", danger = true)
                    } else {
                        DropdownField(
                            value = siteId,
                            onValueChange = onPickSite,
                            label = "当前站点",
                            options = vm.allDomains.map {
                                val sid = pickString(it, "site_id", "id")
                                val label = pickString(it, "site_name", "name").ifBlank { sid }
                                val t = pickString(it, "create_time")
                                (if (t.isBlank()) label else "$label · $t") to sid
                            }
                        )
                    }
                }
            }

            if (wildcard) {
                item {
                    HintText(
                        "当前是通配所有域名的占位站点，后端拒绝在它上面加删域名。请先新建具体站点。",
                        danger = true
                    )
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "现有域名",
                        subtitle = if (siteId.isBlank()) "未选择站点" else "${vm.siteDomains.size} 条",
                        action = {
                            Row {
                                SmallButton("探测", tone = ChipTone.INFO, enabled = siteId.isNotBlank()) { showProbe = true }
                                Spacer(Modifier.width(Space.xs))
                                SmallButton("新增", tone = ChipTone.INFO, enabled = siteId.isNotBlank() && !wildcard) { showAdd = true }
                            }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    when {
                        siteId.isBlank() -> Text(
                            "请先选择站点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        vm.siteDomains.isEmpty() -> Text(
                            "该站点还没有绑定域名",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> vm.siteDomains.forEach { d ->
                            val domain = pickString(d, "domain")
                            val port = pickString(d, "port")
                            Column(Modifier.padding(vertical = Space.xs)) {
                                DataRowDivider(
                                    domain.ifBlank { "(空)" },
                                    if (port.isBlank()) "-" else "端口 $port"
                                )
                                if (!wildcard) {
                                    SmallButton("删除域名", tone = ChipTone.DANGER) {
                                        onConfirm(
                                            ConfirmTask(
                                                title = "删除域名",
                                                message = "确认从当前站点删除 $domain 吗？",
                                                confirmText = "确认删除",
                                                warning = "同一域名可能因多端口存在多条记录，删除时会连同端口一起提交。",
                                                onConfirm = {
                                                    // 端口必须一起带上，否则删的不是这一条
                                                    val payload = if (port.isBlank()) domain else "$domain:$port"
                                                    vm.delDomain(siteId, listOf(payload)) { e ->
                                                        if (e != null) vm.error = e else submitDone("删除域名")
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

            item {
                WafCard {
                    SectionHeader("关于新增域名", accent = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "新增前的探测接口（get_add_domain_info）只返回探测结果，不含默认值，" +
                            "所以端口要按是否 HTTPS 自行推导：http 为 80，https 为 443。"
                    )
                }
            }
        }

        FloatingActionButton(
            // M3 的 FloatingActionButton 没有 enabled 参数，禁用态用点击后给提示代替
            onClick = {
                when {
                    siteId.isBlank() -> vm.error = "请先选择站点"
                    wildcard -> vm.error = "通配站点不允许新增域名，请新建具体站点"
                    else -> showAdd = true
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "新增域名") }
    }

    if (showAdd) {
        AddDomainDialog(
            vm = vm,
            siteId = siteId,
            onDismiss = { showAdd = false },
            onDone = { showAdd = false; submitDone("新增域名") }
        )
    }

    if (showProbe) {
        DomainProbeDialog(vm = vm, siteId = siteId, onDismiss = { showProbe = false })
    }
}

@Composable
private fun AddDomainDialog(
    vm: NginxViewModel,
    siteId: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }

    FormDialog(
        title = "新增域名",
        icon = Icons.Filled.AddLink,
        confirmText = "确认添加",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val domains = splitList(text)
            when {
                siteId == WILDCARD_SITE -> err = "通配站点不允许新增域名，请新建具体站点"
                domains.isEmpty() -> err = "请至少填写一个域名"
                else -> vm.addDomain(siteId, domains) { e -> if (e == null) onDone() else err = e }
            }
            null
        }
    ) {
        HintText("建议先探测再添加：探测只发请求，不改任何配置。")
        Spacer(Modifier.height(Space.md))
        LabeledField(
            text, { text = it; err = null }, "域名",
            "每行一个，或用逗号 / 空格分隔", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        SmallButton(
            if (probing) "探测中…" else "先探测",
            tone = ChipTone.INFO,
            enabled = !probing && splitList(text).isNotEmpty()
        ) {
            probing = true
            err = null
            vm.getAddDomainInfo(splitList(text)) { e ->
                probing = false
                if (e != null) err = e
            }
        }
        if (vm.addDomainInfo.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            SectionHeader("探测结果", subtitle = "响应里不含默认值，端口需自行推导")
            Spacer(Modifier.height(Space.xs))
            vm.addDomainInfo.forEach { row ->
                val name = pickString(row, "domain", "name").ifBlank { "未命名" }
                val isHttps = row["is_https"].toBoolState() || pickString(row, "port") == "443"
                val port = pickString(row, "port").ifBlank { if (isHttps) "443（推导）" else "80（推导）" }
                val ip = pickString(row, "ip", "address")
                DataRowDivider(name, listOf(ip, "端口 $port").filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
    }
}

@Composable
private fun DomainProbeDialog(vm: NginxViewModel, siteId: String, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var intranet by remember { mutableStateOf("") }
    var extranet by remember { mutableStateOf("") }
    var httpProto by remember { mutableStateOf("http") }
    var forceHttps by remember { mutableStateOf(false) }
    var isSsl by remember { mutableStateOf(false) }
    var sslCheck by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<Map<String, Any?>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(siteId) {
        if (url.isBlank()) {
            vm.siteDomains.firstOrNull()?.let { d ->
                val dom = pickString(d, "domain")
                val port = pickString(d, "port")
                if (dom.isNotBlank()) {
                    url = if (port == "443") "https://$dom" else "http://$dom"
                }
            }
        }
    }

    FormDialog(
        title = "域名源站探测",
        icon = Icons.Filled.NetworkCheck,
        confirmText = "开始探测",
        busy = running,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            if (url.isBlank()) {
                err = "请填写探测 URL"
            } else {
                running = true
                result = emptyMap()
                // 这个接口失败时 res 仍是对象，ViewModel 已手工拆包，
                // 所以成功与否都要把 data 渲染出来，不能只弹错误。
                vm.getDomainResponseStatus(
                    url = url.trim(),
                    intranetIpList = splitList(intranet),
                    extranetIpList = splitList(extranet),
                    isForceHttps = forceHttps,
                    httpProtocol = httpProto,
                    isSsl = isSsl,
                    isSslCheck = sslCheck
                ) { data, e ->
                    running = false
                    result = data
                    if (e != null) err = e
                }
            }
            null
        }
    ) {
        HintText("探测会阻塞最多约 9 秒并跟随一次重定向。两个 IP 列表都留空时后端仍会发真实 HTTP 探测。")
        Spacer(Modifier.height(Space.md))
        LabeledField(url, { url = it; err = null }, "探测 URL", "http://example.com")
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            intranet, { intranet = it }, "内网 IP 列表",
            "每行一个，可留空", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            extranet, { extranet = it }, "外网 IP 列表",
            "每行一个，可留空", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        DropdownField(
            httpProto, { httpProto = it }, "源站协议",
            listOf("http" to "http", "https" to "https")
        )
        Spacer(Modifier.height(Space.sm))
        SwitchRow("源站已开强制 HTTPS", forceHttps, { forceHttps = it })
        SwitchRow("源站已启用 SSL", isSsl, { isSsl = it })
        SwitchRow("检查证书有效性", sslCheck, { sslCheck = it })

        if (result.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            val ok = result["status"] == true
            SectionHeader(
                "探测结果",
                subtitle = if (ok) "通过" else "未通过",
                accent = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(Space.xs))
            DataRowDivider("status", if (ok) "通过" else "未通过")
            DataRowDivider("status_code", pickString(result, "status_code").ifBlank { "-" })
            DataRowDivider("resolver", pickString(result, "resolver").ifBlank { "-" })
            val msg = pickString(result, "error")
            if (msg.isNotBlank()) {
                Spacer(Modifier.height(Space.sm))
                HintText(msg, danger = true)
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 3 · 证书
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun SslTab(
    vm: NginxViewModel,
    siteId: String,
    onPickSite: (String) -> Unit,
    toast: (String) -> Unit,
    submitDone: (String) -> Unit,
    onConfirm: (ConfirmTask) -> Unit
) {
    var showDeploy by remember { mutableStateOf(false) }
    var showInstall by remember { mutableStateOf(false) }
    var showProto by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (vm.allSsl.isEmpty()) vm.getAllSsl() }
    LaunchedEffect(siteId) { if (siteId.isNotBlank()) vm.getSslInfo(siteId) }

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
                        "选择站点",
                        subtitle = "证书相关操作都作用于单个站点",
                        action = { SmallButton("刷新") { vm.getAllSsl() } }
                    )
                    Spacer(Modifier.height(Space.md))
                    if (vm.sites.isEmpty()) {
                        HintText("站点列表为空，请先到「站点」页新建站点。", danger = true)
                    } else {
                        DropdownField(
                            value = siteId,
                            onValueChange = onPickSite,
                            label = "当前站点",
                            options = vm.sites.map {
                                val sid = siteIdOf(it)
                                (siteNameOf(it).ifBlank { sid }) to sid
                            }
                        )
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader(
                        "站点证书",
                        subtitle = if (siteId.isBlank()) "未选择站点" else "get_ssl_info",
                        action = {
                            Row {
                                SmallButton("部署", tone = ChipTone.INFO, enabled = siteId.isNotBlank()) { showDeploy = true }
                                Spacer(Modifier.width(Space.xs))
                                SmallButton("上传", tone = ChipTone.INFO, enabled = siteId.isNotBlank()) { showInstall = true }
                            }
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    if (siteId.isBlank()) {
                        Text(
                            "请先选择站点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val info = vm.siteSslInfo
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(
                                if (info["is_https"].toBoolState()) "HTTPS 已开启" else "HTTPS 未开启",
                                if (info["is_https"].toBoolState()) ChipTone.SUCCESS else ChipTone.NEUTRAL
                            )
                            Spacer(Modifier.width(Space.sm))
                            StatusChip(
                                if (info["force_https"].toBoolState()) "强制跳转" else "不强制跳转",
                                if (info["force_https"].toBoolState()) ChipTone.INFO else ChipTone.NEUTRAL
                            )
                        }
                        Spacer(Modifier.height(Space.sm))
                        DataRowDivider("证书名", pickString(info, "ssl_name", "name").ifBlank { "未部署证书" })
                        DataRowDivider("颁发机构", pickString(info, "brand"))
                        DataRowDivider("到期时间", pickString(info, "not_after"))
                        DataRowDivider("覆盖域名", (info["Domains"] as? List<*>)?.joinToString("  ·  ").orEmpty())
                        DataRowDivider("协议", pickString(info, "ssl_protocols"))
                        DataRowDivider("加密套件", pickString(info, "ssl_ciphers"))
                        Spacer(Modifier.height(Space.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SmallButton("协议与套件", tone = ChipTone.INFO, enabled = siteId.isNotBlank()) { showProto = true }
                            SmallButton("下载证书", enabled = siteId.isNotBlank()) {
                                // 后端返回二进制 zip 流，Repository 尚不支持二进制下载
                                vm.downloadSsl(siteId) { e -> toast(e ?: "暂不支持下载") }
                            }
                            SmallButton("移除 SSL", tone = ChipTone.DANGER, enabled = siteId.isNotBlank()) {
                                onConfirm(
                                    ConfirmTask(
                                        title = "移除站点 SSL",
                                        message = "确认移除「${siteNameOf(vm.sites.firstOrNull { siteIdOf(it) == siteId }.orEmpty())}」的证书吗？",
                                        confirmText = "确认移除",
                                        warning = "这是关闭站点 SSL 的唯一正确入口。移除后该站 HTTPS 会立即不可用。",
                                        onConfirm = {
                                            vm.removeSiteSsl(siteId) { e ->
                                                if (e != null) vm.error = e else submitDone("移除站点 SSL")
                                            }
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item {
                WafCard {
                    SectionHeader("证书库", subtitle = "${vm.allSsl.size} 张 · get_all_ssl")
                    Spacer(Modifier.height(Space.sm))
                    if (vm.allSsl.isEmpty()) {
                        Text(
                            "证书夹里还没有证书",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        vm.allSsl.forEach { c ->
                            val name = pickString(c, "ssl_name", "name").ifBlank { "(未命名)" }
                            val domains = (c["Domains"] as? List<*>)?.joinToString("  ·  ").orEmpty()
                            Column(Modifier.padding(vertical = Space.xs)) {
                                DataRowDivider(
                                    name,
                                    listOf(pickString(c, "brand"), pickString(c, "not_after"))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                )
                                if (domains.isNotBlank()) {
                                    Text(
                                        domains,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                    SmallButton("部署到当前站点", tone = ChipTone.INFO, enabled = siteId.isNotBlank()) {
                                        showDeploy = true
                                    }
                                    SmallButton("删除", tone = ChipTone.DANGER) {
                                        onConfirm(
                                            ConfirmTask(
                                                title = "删除证书",
                                                message = "确认从证书夹删除「$name」吗？",
                                                confirmText = "确认删除",
                                                warning = "删除的是证书实体，任何引用它的站点都会受影响。",
                                                onConfirm = {
                                                    vm.deleteSsl(name) { e ->
                                                        if (e != null) vm.error = e else submitDone("删除证书")
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

        FloatingActionButton(
            onClick = {
                if (siteId.isBlank()) vm.error = "请先选择站点" else showInstall = true
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "上传证书") }
    }

    if (showDeploy) {
        DeployCertDialog(
            vm = vm,
            siteId = siteId,
            onDismiss = { showDeploy = false },
            onDone = { showDeploy = false; submitDone("部署证书") }
        )
    }

    if (showInstall) {
        InstallCertDialog(
            vm = vm,
            siteId = siteId,
            onDismiss = { showInstall = false }
        )
    }

    if (showProto) {
        ProtocolCiphersDialog(
            vm = vm,
            siteId = siteId,
            onDismiss = { showProto = false },
            onDone = { showProto = false; submitDone("修改协议与套件") }
        )
    }
}

@Composable
private fun DeployCertDialog(
    vm: NginxViewModel,
    siteId: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (vm.allSsl.isEmpty()) vm.getAllSsl() }

    FormDialog(
        title = "部署证书到站点",
        icon = Icons.Filled.Lock,
        confirmText = "部署",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            if (name.isBlank()) err = "请选择证书"
            else vm.deployCert(siteId, name) { e -> if (e == null) onDone() else err = e }
            null
        }
    ) {
        HintText("部署会重写站点的 HTTPS 配置并重载 nginx，直接影响线上流量。")
        Spacer(Modifier.height(Space.md))
        if (vm.allSsl.isEmpty()) {
            HintText("证书夹为空，请先上传或申请证书。", danger = true)
        } else {
            DropdownField(
                name, { name = it }, "选择证书",
                vm.allSsl.map { c ->
                    val n = pickString(c, "ssl_name", "name")
                    val brand = pickString(c, "brand")
                    (if (brand.isBlank()) n else "$n · $brand") to n
                }
            )
        }
    }
}

@Composable
private fun InstallCertDialog(vm: NginxViewModel, siteId: String, onDismiss: () -> Unit) {
    var err by remember { mutableStateOf<String?>(null) }
    var privkey by remember { mutableStateOf("") }
    var fullchain by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf(siteId)) }
    var outcome by remember { mutableStateOf<String?>(null) }

    FormDialog(
        title = "上传证书到站点",
        icon = Icons.Filled.Upload,
        confirmText = "安装",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            outcome = null
            when {
                picked.isEmpty() -> err = "请至少选择一个站点"
                privkey.isBlank() || fullchain.isBlank() -> err = "私钥与证书链不能为空"
                // site_id 走 JSON 数组；但 ParamsCheck 会对数组值查 site_info 表，
                // 数组站点 id 有被判为不存在的风险，所以先给一个警告。
                picked.size > 1 -> err = "多站点批量安装存在被判 site_id 不存在的风险，建议先只选一个站点验证"

                else -> vm.installCert(picked.toList(), privkey, fullchain) { ok, e ->
                    if (e != null) {
                        err = e
                    } else {
                        // 部分失败也返回 code=0，ViewModel 拿不到 res 原文，
                        // 只能按「可能部分成功」处理并要求用户逐站核对。
                        outcome = "接口已受理。该接口只要有一个站点成功就返回成功，" +
                            "请逐个站点核对证书是否真的换成了新证书。"
                    }
                }
            }
            null
        }
    ) {
        HintText("site_id 以 JSON 数组提交。单个站点安装更稳，后端会逐站写证书并重载 nginx。")
        Spacer(Modifier.height(Space.md))
        SectionHeader("目标站点", subtitle = "已选 ${picked.size} 个")
        Spacer(Modifier.height(Space.xs))
        if (vm.sites.isEmpty()) {
            HintText("没有可选站点，请先到「站点」页新建站点。", danger = true)
        } else {
            vm.sites.forEach { s ->
                val sid = siteIdOf(s)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            picked = if (picked.contains(sid)) picked - sid else picked + sid
                        }
                        .padding(vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = picked.contains(sid), onCheckedChange = { on ->
                        picked = if (on) picked + sid else picked - sid
                    })
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        siteNameOf(s).ifBlank { sid },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        LabeledField(privkey, { privkey = it }, "私钥", "PEM 原文", singleLine = false, isPassword = true)
        Spacer(Modifier.height(Space.sm))
        LabeledField(fullchain, { fullchain = it }, "证书链", "PEM 原文", singleLine = false)
        if (outcome != null) {
            Spacer(Modifier.height(Space.md))
            HintText(outcome!!, danger = true)
        }
    }
}

@Composable
private fun ProtocolCiphersDialog(
    vm: NginxViewModel,
    siteId: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ciphers by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(siteId) {
        if (siteId.isNotBlank()) vm.getSslProtocolsAndCiphers(siteId)
    }
    LaunchedEffect(vm.sslProtocolsAndCiphers) {
        if (loaded) return@LaunchedEffect
        val cur = currentProtocols(vm.sslProtocolsAndCiphers)
        if (cur.isNotEmpty()) {
            selected = cur
            ciphers = currentCiphers(vm.sslProtocolsAndCiphers)
            loaded = true
        }
    }

    val options = sslProtocolOptions(vm.sslProtocolsAndCiphers)

    FormDialog(
        title = "协议与加密套件",
        icon = Icons.Filled.Security,
        confirmText = "保存",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            when {
                // ssl_protocols 必须是数组，ssl_ciphers 必须是冒号分隔字符串
                selected.isEmpty() -> err = "请至少选择一个安全协议"
                ciphers.isBlank() -> err = "加密套件不能为空"
                else -> vm.setSslProtocolsOrCiphers(siteId, selected.toList(), ciphers.trim()) { e ->
                    if (e == null) onDone() else err = e
                }
            }
            null
        }
    ) {
        HintText("保存后立即重载 nginx 并影响线上握手。只支持 TLSv1.2 / TLSv1.3。")
        Spacer(Modifier.height(Space.md))
        SectionHeader("安全协议", subtitle = "数组参数，至少选一个")
        Spacer(Modifier.height(Space.xs))
        if (options.isEmpty()) {
            Text(
                "正在读取可选值…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            options.forEach { p ->
                val on = selected.contains(p)
                ListRow(
                    icon = if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    title = p,
                    iconSize = 32.dp,
                    iconTint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    onClick = { selected = if (on) selected - p else selected + p }
                )
            }
        }
        Spacer(Modifier.height(Space.md))
        SectionHeader("加密套件", subtitle = "冒号分隔字符串")
        Spacer(Modifier.height(Space.xs))
        LabeledField(
            ciphers, { ciphers = it; err = null }, "套件列表",
            "TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384", singleLine = false
        )
        Spacer(Modifier.height(Space.sm))
        val available = cipherOptions(vm.sslProtocolsAndCiphers)
        if (available.isNotEmpty()) {
            HintText("系统可选值：$available")
            Spacer(Modifier.height(Space.sm))
            SmallButton("填入当前生效值") {
                ciphers = cipherOptions(vm.sslProtocolsAndCiphers)
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * Tab 4 · 负载均衡
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun LbTab(
    vm: NginxViewModel,
    toast: (String) -> Unit,
    submitDone: (String) -> Unit,
    onConfirm: (ConfirmTask) -> Unit,
    onVerifyDelete: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (vm.tcpLoadBalances.isEmpty()) vm.getTcpLoadBalance() }

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
                        "TCP 端口转发",
                        subtitle = "${vm.tcpLoadBalances.size} 条 · get_tcp_load_balance",
                        action = { SmallButton("刷新") { vm.getTcpLoadBalance() } }
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText("新增走 node_ 前缀的键名，修改走无前缀键名，两者不一致；修改也无法新增节点。")
                }
            }

            if (vm.tcpLoadBalances.isEmpty() && !vm.loading) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        title = "还没有端口转发配置",
                        description = "新增一条 TCP/UDP 转发，把外部端口流量分摊到多个后端节点",
                        actionText = "刷新",
                        onAction = { vm.getTcpLoadBalance() }
                    )
                }
            }

            items(vm.tcpLoadBalances.entries.toList()) { (name, cfg) ->
                WafCard {
                    ListRow(
                        icon = Icons.AutoMirrored.Filled.CallSplit,
                        title = name,
                        subtitle = listOf(
                            pickString(cfg, "protocol").uppercase().ifBlank { "TCP" },
                            "${pickString(cfg, "listen_address").ifBlank { "0.0.0.0" }}:${pickString(cfg, "listen_port")}",
                            "${cfg["node_address_map"].asMap().size} 个节点"
                        ).joinToString(" · "),
                        onClick = { editName = name },
                        trailing = { StatusChip("点击管理", ChipTone.INFO) }
                    )
                    val nodes = lbNodes(cfg)
                    if (nodes.isNotEmpty()) {
                        Spacer(Modifier.height(Space.xs))
                        nodes.forEach { n ->
                            DataRowDivider(
                                "${n.address}:${n.port}",
                                "权重 ${n.weight} · ${if (n.status == "0") "已停用" else "启用中"}"
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        SmallButton("编辑", tone = ChipTone.INFO) { editName = name }
                        SmallButton("清零计数") {
                            onConfirm(
                                ConfirmTask(
                                    title = "清零连接计数",
                                    message = "确认把「$name」的连接计数清零吗？",
                                    danger = false,
                                    confirmText = "清零",
                                    onConfirm = {
                                        vm.clearTcpLoadBalanceCount(name) { e ->
                                            if (e != null) vm.error = e else toast("计数已清零")
                                        }
                                    }
                                )
                            )
                        }
                        SmallButton("删除", tone = ChipTone.DANGER) {
                            onConfirm(
                                ConfirmTask(
                                    title = "删除端口转发",
                                    message = "确认删除「$name」吗？",
                                    confirmText = "确认删除",
                                    warning = "删除会 kill 监听进程、删防火墙放行、删 tcp.conf 并重载 nginx，" +
                                        "该端口业务立即中断。后端对不存在的名字也会返回成功，提交后请确认列表变化。",
                                    onConfirm = {
                                        vm.delTcpLoadBalance(name, pickString(cfg, "listen_port")) { e ->
                                            if (e != null) vm.error = e else onVerifyDelete(name)
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
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) { Icon(Icons.Filled.Add, contentDescription = "新增转发") }
    }

    if (showAdd) {
        TcpLbDialog(
            vm = vm,
            existing = null,
            onDismiss = { showAdd = false },
            onDone = { showAdd = false; submitDone("新增端口转发") }
        )
    }

    if (editName.isNotBlank()) {
        val cfg = vm.tcpLoadBalances[editName]
        if (cfg != null) {
            TcpLbDialog(
                vm = vm,
                existing = editName to cfg,
                onDismiss = { editName = "" },
                onDone = { editName = ""; submitDone("修改端口转发") }
            )
        } else {
            // 配置刚被删掉，退出编辑态避免对着空配置提交
            LaunchedEffect(editName) { editName = "" }
        }
    }
}

@Composable
private fun TcpLbDialog(
    vm: NginxViewModel,
    existing: Pair<String, Map<String, Any?>>?,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    val editing = existing != null
    val cfg = existing?.second.orEmpty()

    var protocol by remember { mutableStateOf(pickString(cfg, "protocol").ifBlank { "tcp" }) }
    var listenAddr by remember { mutableStateOf(pickString(cfg, "listen_address").ifBlank { "0.0.0.0" }) }
    var listenPort by remember { mutableStateOf(pickString(cfg, "listen_port")) }
    var maxTimeout by remember { mutableStateOf(pickString(cfg, "max_timeout").ifBlank { "60" }) }
    var notTimeout by remember { mutableStateOf(pickString(cfg, "not_timeout").ifBlank { "60" }) }
    var ps by remember { mutableStateOf(pickString(cfg, "ps")) }
    var nodes by remember { mutableStateOf(if (editing) lbNodes(cfg) else listOf(NodeDraft())) }

    // 修改接口的 load_info 不含 listen_port，端口取的是已存配置，响应文案也是旧端口
    val portLocked = editing

    FormDialog(
        title = if (editing) "编辑端口转发" else "新增端口转发",
        icon = Icons.AutoMirrored.Filled.CallSplit,
        confirmText = if (editing) "保存" else "创建",
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            val name = existing?.first.orEmpty()
            // 严格按后端校验顺序报错，用户看到的提示和后端一致
            val bad = when {
                protocol !in setOf("tcp", "udp", "tcp/udp") -> "协议类型不正确"
                maxTimeout.toIntOrNull() == null -> "最大超时时间不正确"
                notTimeout.toIntOrNull() == null -> "非超时时间不正确"
                listenAddr !in setOf("127.0.0.1", "0.0.0.0") -> "监听地址不正确"
                nodes.isEmpty() -> "请至少配置一个后端节点"
                nodes.any { it.address.isBlank() || it.port.isBlank() } -> "每个节点都必须填写地址与端口"
                nodes.any { it.weight.toIntOrNull() == null || it.maxFails.toIntOrNull() == null || it.failTimeout.toIntOrNull() == null } ->
                    "节点的权重 / 最大失败 / 失败超时必须是非负整数"
                // validate.IsPort 只接受字符串形式的合法端口
                !portLocked && !isValidPort(listenPort) -> "waf 服务端口不正确"
                else -> null
            }
            if (bad != null) {
                err = bad
            } else if (editing) {
                // 修改只能改已存在的节点，node_address_map 的键就是节点地址
                val map = nodes.associate { n ->
                    n.address to vm.loadInfoNode(
                        address = n.address,
                        port = n.port,
                        weight = n.weight,
                        maxFails = n.maxFails,
                        failTimeout = n.failTimeout,
                        status = n.status,
                        ps = n.ps,
                        addressFollow = n.follow
                    )
                }
                vm.modifyTcpLoadBalance(
                    loadBalanceName = name,
                    protocol = protocol,
                    listenAddress = listenAddr,
                    maxTimeout = maxTimeout,
                    notTimeout = notTimeout,
                    ps = ps,
                    nodeAddressMap = map
                ) { e -> if (e == null) onDone() else err = e }
            } else {
                val list = nodes.map { n ->
                    // 新增用的是 node_ 前缀的键名，与修改不同
                    vm.tcpNode(
                        address = n.address,
                        port = n.port,
                        weight = n.weight,
                        maxFails = n.maxFails,
                        failTimeout = n.failTimeout,
                        status = n.status,
                        ps = n.ps,
                        addressFollow = n.follow
                    )
                }
                vm.addTcpLoadBalance(
                    protocol = protocol,
                    listenAddress = listenAddr,
                    listenPort = listenPort,
                    maxTimeout = maxTimeout,
                    notTimeout = notTimeout,
                    ps = ps,
                    nodeInfo = list
                ) { e -> if (e == null) onDone() else err = e }
            }
            null
        }
    ) {
        HintText("所有端口与超时都按字符串提交。端口被占用时后端会直接拒绝。")
        if (editing) {
            Spacer(Modifier.height(Space.sm))
            HintText("修改接口无法改监听端口，也无法新增节点。需要加节点请用「新增」重建配置。", danger = true)
        }
        Spacer(Modifier.height(Space.md))
        DropdownField(protocol, { protocol = it }, "协议类型", PROTOCOL_OPTIONS)
        Spacer(Modifier.height(Space.sm))
        DropdownField(listenAddr, { listenAddr = it }, "监听地址", LISTEN_ADDRESS_OPTIONS)
        Spacer(Modifier.height(Space.sm))
        LabeledField(
            listenPort, { listenPort = it }, "监听端口",
            if (portLocked) "编辑时不可改，取已存配置" else "如 8080",
            enabled = !portLocked,
            keyboardType = KeyboardType.Number
        )
        Spacer(Modifier.height(Space.sm))
        GridRow(gap = Space.sm) {
            GridCell {
                LabeledField(maxTimeout, { maxTimeout = it }, "最大超时", "60", keyboardType = KeyboardType.Number)
            }
            GridCell {
                LabeledField(notTimeout, { notTimeout = it }, "非超时", "60", keyboardType = KeyboardType.Number)
            }
        }
        Spacer(Modifier.height(Space.sm))
        LabeledField(ps, { ps = it }, "备注", "可选")
        Spacer(Modifier.height(Space.md))
        SectionHeader("后端节点", subtitle = "${nodes.size} 个")
        Spacer(Modifier.height(Space.xs))
        nodes.forEachIndexed { i, n ->
            WafCard(contentPadding = cardPadding) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "节点 ${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (nodes.size > 1) {
                        SmallButton("移除", tone = ChipTone.DANGER) {
                            nodes = nodes.filterIndexed { idx, _ -> idx != i }
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                LabeledField(n.address, { v -> nodes = nodes.replaceAt(i) { it.copy(address = v) } }, "节点地址", "10.0.0.5")
                Spacer(Modifier.height(Space.sm))
                NodeParamFields(n) { updated -> nodes = nodes.replaceAt(i) { updated } }
            }
            Spacer(Modifier.height(Space.sm))
        }
        if (!editing) {
            SmallButton("添加节点", tone = ChipTone.INFO) { nodes = nodes + NodeDraft() }
        }
    }
}

private fun <T> List<T>.replaceAt(i: Int, block: (T) -> T): List<T> =
    mapIndexed { idx, v -> if (idx == i) block(v) else v }

/* ══════════════════════════════════════════════════════════════════
 * Tab 5 · 设置
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun SettingsTab(
    vm: NginxViewModel,
    siteId: String,
    onPickSite: (String) -> Unit,
    toast: (String) -> Unit,
    submitDone: (String) -> Unit,
    onConfirm: (ConfirmTask) -> Unit
) {
    var showProxy by remember { mutableStateOf(false) }
    var luaDraft by remember { mutableStateOf<String?>(null) }
    var luaErr by remember { mutableStateOf<String?>(null) }
    var resolverInterval by remember { mutableStateOf("60") }

    LaunchedEffect(siteId) {
        if (siteId.isNotBlank()) {
            vm.getSiteSetting(siteId)
            vm.getReturnSource(siteId)
            vm.getUserConfig(siteId)
            vm.getReplaceOpen(siteId)
            vm.getSslInfo(siteId)
            vm.getRealTimeData()
        }
    }

    LazyColumn(
        contentPadding = cardPadding,
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        item {
            WafCard {
                SectionHeader(
                    "选择站点",
                    subtitle = "代理、解析、HTTPS、IPv6、lua 配置都按站点生效",
                    action = {
                        SmallButton("刷新") {
                            if (siteId.isNotBlank()) {
                                vm.getSiteSetting(siteId)
                                vm.getReturnSource(siteId)
                                vm.getUserConfig(siteId)
                                vm.getRealTimeData()
                            }
                        }
                    }
                )
                Spacer(Modifier.height(Space.md))
                if (vm.sites.isEmpty()) {
                    HintText("站点列表为空，请先到「站点」页新建站点。", danger = true)
                } else {
                    DropdownField(
                        value = siteId,
                        onValueChange = onPickSite,
                        label = "当前站点",
                        options = vm.sites.map {
                            val sid = siteIdOf(it)
                            (siteNameOf(it).ifBlank { sid }) to sid
                        }
                    )
                }
            }
        }

        if (siteId.isBlank()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Tune,
                    title = "请先选择站点",
                    description = "选择站点后可配置代理、解析、强制 HTTPS、IPv6 与 lua 指令"
                )
            }
            return@LazyColumn
        }

        /* ---------- 代理信息 ---------- */
        item {
            WafCard {
                SectionHeader(
                    "代理信息",
                    subtitle = "get_site_setting / modify_proxyinfo",
                    action = { SmallButton("编辑", tone = ChipTone.INFO) { showProxy = true } }
                )
                Spacer(Modifier.height(Space.sm))
                val s = vm.siteSetting
                DataRowDivider("连接超时", settingVal(s, "proxy_connect_timeout", "connect_timeout"))
                DataRowDivider("发送超时", settingVal(s, "proxy_send_timeout", "send_timeout"))
                DataRowDivider("读取超时", settingVal(s, "proxy_read_timeout", "read_timeout"))
                DataRowDivider("请求体上限", settingVal(s, "client_max_body_size", "max_body_size"))
                Spacer(Modifier.height(Space.sm))
                HintText("这些参数漏传会被零值覆盖，把超时和包体限制清空，所以保存时必须全部填写。")
            }
        }

        /* ---------- DNS 解析 ---------- */
        item {
            val hasSource = returnSourceNodes(vm.returnSource).isNotEmpty()
            WafCard {
                SectionHeader("DNS 解析", subtitle = "modify_resolver", accent = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(Space.sm))
                SwitchRow(
                    title = "自动巡检回源域名解析变化",
                    checked = vm.returnSource["check_dns"].toBoolState(),
                    onChange = { want ->
                        if (want && !hasSource) {
                            vm.error = "此站点无回源域名，无法开启自动巡检回源域名解析变化"
                        } else {
                            onConfirm(
                                ConfirmTask(
                                    title = if (want) "开启自动巡检" else "关闭自动巡检",
                                    message = if (want) "将按固定间隔重新解析回源域名并更新 upstream。"
                                    else "关闭后回源域名 IP 变化不会再被自动感知。",
                                    danger = false,
                                    confirmText = if (want) "开启" else "关闭",
                                    onConfirm = {
                                        val t = resolverInterval.toIntOrNull() ?: 60
                                        vm.modifyResolver(siteId, if (want) 1 else 0, t) { e ->
                                            if (e != null) vm.error = e else submitDone("修改解析巡检")
                                        }
                                    }
                                )
                            )
                        }
                    },
                    description = "巡检间隔（秒）：$resolverInterval"
                )
                Spacer(Modifier.height(Space.sm))
                LabeledField(
                    resolverInterval, { resolverInterval = it }, "巡检间隔（秒）",
                    "60", keyboardType = KeyboardType.Number
                )
                if (!hasSource) {
                    Spacer(Modifier.height(Space.sm))
                    HintText("该站点当前没有回源域名，开启巡检会被后端直接拒绝。", danger = true)
                }
            }
        }

        /* ---------- 强制 HTTPS ---------- */
        item {
            WafCard {
                SectionHeader("强制 HTTPS", subtitle = "set_force_https", accent = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(Space.sm))
                SwitchRow(
                    title = "HTTP 访问 301 跳转到 HTTPS",
                    checked = vm.siteSslInfo["force_https"].toBoolState(),
                    onChange = { want ->
                        onConfirm(
                            ConfirmTask(
                                title = if (want) "开启强制 HTTPS" else "关闭强制 HTTPS",
                                message = if (want) "开启后所有 HTTP 访问都会被 301 到 HTTPS，未配置证书的路径会直接失败。"
                                else "关闭后 HTTP 访问不再跳转。",
                                danger = want,
                                confirmText = if (want) "开启" else "关闭",
                                warning = if (want) "该站点必须已部署有效证书，否则开启后 HTTP 入口会不可用。" else null,
                                onConfirm = {
                                    vm.setForceHttps(siteId, want) { e ->
                                        if (e != null) vm.error = e else submitDone("强制 HTTPS")
                                    }
                                }
                            )
                        )
                    }
                )
            }
        }

        /* ---------- IPv6 监听 ---------- */
        item {
            WafCard {
                SectionHeader("IPv6 监听", subtitle = "set_listen_ipv_six_by_site", accent = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(Space.sm))
                SwitchRow(
                    title = "监听 IPv6",
                    checked = settingVal(vm.siteSetting, "listen_ipv6", "listen_ipv6_status").toBoolState(),
                    onChange = { want ->
                        onConfirm(
                            ConfirmTask(
                                title = if (want) "开启 IPv6 监听" else "关闭 IPv6 监听",
                                message = "修改会直接改写站点配置文件并重载 nginx。",
                                danger = false,
                                confirmText = if (want) "开启" else "关闭",
                                warning = "重载 nginx 失败时后端不会回滚，配置已落盘。提交后请手动确认站点是否正常。",
                                onConfirm = {
                                    vm.setListenIpvSixBySite(siteId, want) { e ->
                                        if (e != null) vm.error = e else submitDone("IPv6 监听")
                                    }
                                }
                            )
                        )
                    }
                )
            }
        }

        /* ---------- lua 指令配置 ---------- */
        item {
            WafCard {
                SectionHeader("lua 指令配置", subtitle = "get_user_config / user_config", accent = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(Space.sm))
                val draft = luaDraft ?: vm.userConfig
                OutlinedTextField(
                    value = draft,
                    onValueChange = { luaDraft = it; luaErr = null },
                    label = { Text("自定义配置内容") },
                    placeholder = { Text("留空表示清空该站点的自定义配置", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                if (luaErr != null) {
                    Spacer(Modifier.height(Space.sm))
                    HintText(luaErr!!, danger = true)
                }
                Spacer(Modifier.height(Space.sm))
                HintText("以下指令会被后端直接拒绝：${BANNED_LUA_DIRECTIVES.joinToString("、")}")
                Spacer(Modifier.height(Space.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SmallButton("重新读取") {
                        luaDraft = null
                        luaErr = null
                        vm.getUserConfig(siteId)
                    }
                    SmallButton("保存", tone = ChipTone.INFO) {
                        val hit = BANNED_LUA_DIRECTIVES.firstOrNull { draft.contains(it) }
                        if (hit != null) {
                            luaErr = "配置中包含非法指令：$hit"
                        } else {
                            vm.userConfig(siteId, draft) { e ->
                                if (e != null) luaErr = e else {
                                    luaDraft = null
                                    submitDone("保存 lua 配置")
                                }
                            }
                        }
                    }
                }
            }
        }

        /* ---------- 站点详情补充 ---------- */
        item {
            WafCard {
                SectionHeader("其他站点标记", accent = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(Space.sm))
                DataRowDivider("替换响应", if (vm.replaceOpen) "已开启" else "未开启")
                DataRowDivider("站点名", siteNameOf(vm.sites.firstOrNull { siteIdOf(it) == siteId }.orEmpty()))
                DataRowDivider("HTTPS", if (vm.siteSslInfo["is_https"].toBoolState()) "已开启" else "未开启")
            }
        }

        /* ---------- 实时数据 ---------- */
        item {
            val access = vm.realTimeData.sumOf { it["access_num"].asString().toLongOrNull() ?: 0L }
            val blocked = vm.realTimeData.sumOf { it["interception_num"].asString().toLongOrNull() ?: 0L }
            val send = vm.realTimeData.sumOf { it["real_time_send"].asString().toLongOrNull() ?: 0L }
            val recv = vm.realTimeData.sumOf { it["real_time_recv"].asString().toLongOrNull() ?: 0L }

            WafCard {
                SectionHeader(
                    "实时数据",
                    subtitle = "全站汇总 · get_real_time_data",
                    action = { SmallButton("刷新") { vm.getRealTimeData() } }
                )
                Spacer(Modifier.height(Space.md))
                GridRow(gap = Space.sm) {
                    GridCell { MetricCard(Icons.Filled.Visibility, "访问数", formatNumber(access)) }
                }
                Spacer(Modifier.height(Space.sm))
                GridRow(gap = Space.sm) {
                    GridCell { MetricCard(Icons.Filled.GppBad, "拦截数", formatNumber(blocked), accent = MaterialTheme.colorScheme.error) }
                    GridCell { MetricCard(Icons.Filled.Upload, "发送", formatBytes(send)) }
                    GridCell { MetricCard(Icons.Filled.Download, "接收", formatBytes(recv)) }
                }
                Spacer(Modifier.height(Space.md))
                if (vm.realTimeData.isEmpty()) {
                    Text(
                        "暂无实时数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    vm.realTimeData.forEach { d ->
                        val a = d["access_num"].asString().toLongOrNull() ?: 0L
                        val b = d["interception_num"].asString().toLongOrNull() ?: 0L
                        val rate = if (a > 0) "%.1f%%".format(b * 100.0 / a) else "-"
                        DataRowDivider(
                            pickString(d, "site_id"),
                            "访问 ${formatNumber(a)} · 拦截 ${formatNumber(b)} · $rate"
                        )
                    }
                }
            }
        }
    }

    if (showProxy) {
        ProxyDialog(
            vm = vm,
            siteId = siteId,
            onDismiss = { showProxy = false },
            onDone = { showProxy = false; submitDone("修改代理信息") }
        )
    }
}

@Composable
private fun ProxyDialog(
    vm: NginxViewModel,
    siteId: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit
) {
    var err by remember { mutableStateOf<String?>(null) }
    val s = vm.siteSetting
    var connect by remember { mutableStateOf(settingVal(s, "proxy_connect_timeout", "connect_timeout")) }
    var send by remember { mutableStateOf(settingVal(s, "proxy_send_timeout", "send_timeout")) }
    var read by remember { mutableStateOf(settingVal(s, "proxy_read_timeout", "read_timeout")) }
    var body by remember { mutableStateOf(settingVal(s, "client_max_body_size", "max_body_size")) }

    FormDialog(
        title = "修改代理信息",
        icon = Icons.Filled.Speed,
        busy = vm.saving,
        error = err,
        onDismiss = { onDismiss() },
        onConfirm = {
            err = null
            when {
                // 这四项都不在参数校验里，漏传会被零值覆盖，所以必须非空
                connect.isBlank() || send.isBlank() || read.isBlank() || body.isBlank() ->
                    err = "四项参数都不能留空，漏传会被零值覆盖"

                else -> vm.modifyProxyinfo(siteId, connect.trim(), send.trim(), read.trim(), body.trim()) { e ->
                    if (e == null) onDone() else err = e
                }
            }
            null
        }
    ) {
        HintText("后端不校验这些键，缺失就是零值覆盖，保存前请全部填写。")
        Spacer(Modifier.height(Space.sm))
        HintText("请求体缓冲大小后端接收但从不写入配置，所以这里不提供该输入项。")
        Spacer(Modifier.height(Space.md))
        LabeledField(connect, { connect = it; err = null }, "连接超时", "60", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(Space.sm))
        LabeledField(send, { send = it }, "发送超时", "60", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(Space.sm))
        LabeledField(read, { read = it }, "读取超时", "60", keyboardType = KeyboardType.Number)
        Spacer(Modifier.height(Space.sm))
        LabeledField(body, { body = it }, "请求体上限", "如 50m / 1024k")
    }
}

/* ══════════════════════════════════════════════════════════════════
 * 日志面板（站点详情内复用）
 * ══════════════════════════════════════════════════════════════════ */

@Composable
private fun LogPanel(
    vm: NginxViewModel,
    siteId: String,
    onConfirm: (ConfirmTask) -> Unit,
    submitDone: (String) -> Unit
) {
    var type by remember { mutableStateOf("access") }

    // get_log_list 只接受 access / error，没有 slow；get_site_log 才有 slow
    val listType = if (type == "slow") "access" else type

    LaunchedEffect(siteId, listType) {
        if (siteId.isBlank()) return@LaunchedEffect
        vm.getLogList(siteId, listType)
        vm.getSiteLog(siteId, type)
    }

    Column {
        WafCard {
            SectionHeader("访问日志", subtitle = "尾部最多 1000 行")
            Spacer(Modifier.height(Space.sm))
            DropdownField(type, { type = it }, "日志类型", LOG_TYPE_OPTIONS)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SmallButton("刷新") {
                    vm.getLogList(siteId, listType)
                    vm.getSiteLog(siteId, type)
                }
                SmallButton("清空日志", tone = ChipTone.DANGER) {
                    onConfirm(
                        ConfirmTask(
                            title = "清空日志",
                            message = "确认清空该站点 $type 日志吗？",
                            confirmText = "确认清空",
                            warning = "清空后不可恢复。",
                            onConfirm = {
                                vm.clearSiteLog(siteId, type) { e ->
                                    if (e != null) vm.error = e else submitDone("清空日志")
                                }
                            }
                        )
                    )
                }
            }
            if (type == "slow") {
                Spacer(Modifier.height(Space.sm))
                HintText("慢请求日志与访问日志实际同源，都是读站点 .log 主文件。")
            }
        }
        Spacer(Modifier.height(Space.sm))
        WafCard {
            SectionHeader("历史日志文件", subtitle = "${vm.logFiles.size} 个 · 仅 access / error")
            Spacer(Modifier.height(Space.sm))
            if (vm.logFiles.isEmpty()) {
                Text(
                    "没有历史日志文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                vm.logFiles.forEach { f ->
                    val name = pickString(f, "name", "filename")
                    Column(Modifier.padding(vertical = Space.xs)) {
                        DataRowDivider(
                            name.ifBlank { "(未命名)" },
                            listOf(formatBytes(pickString(f, "size").toLongOrNull() ?: 0L), fmtStamp(f["timestamp"]))
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SmallButton("下载", enabled = false) { }
                            SmallButton("删除", tone = ChipTone.DANGER) {
                                onConfirm(
                                    ConfirmTask(
                                        title = "删除日志文件",
                                        message = "确认删除 $name 吗？",
                                        confirmText = "确认删除",
                                        warning = "删除历史日志不可恢复。后端删除判断写反了，两种情况都返回成功，" +
                                            "提交后请确认文件确实消失。",
                                        onConfirm = {
                                            // filename 是全小写，与直觉的 fileName 不同
                                            vm.deleteLog(siteId, listType, name) { e ->
                                                if (e != null) vm.error = e else submitDone("删除日志文件")
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
        Spacer(Modifier.height(Space.sm))
        WafCard {
            SectionHeader("日志正文", subtitle = if (vm.siteLog.isBlank()) "暂无内容" else "${vm.siteLog.lines().size} 行")
            Spacer(Modifier.height(Space.sm))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(Space.sm)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    vm.siteLog.ifBlank { "暂无日志内容" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════
 * 取值辅助
 * ══════════════════════════════════════════════════════════════════ */

private fun siteIdOf(site: Map<String, Any?>): String =
    pickString(site, "site_id", "id", "name", "sitename", "site_name")

private fun siteNameOf(site: Map<String, Any?>): String =
    pickString(site, "sitename", "site_name", "name", "domain").ifBlank { "未命名站点" }

/** 站点域名，可能在 sitename 里，也可能单独存在 domain_list。 */
private fun domainTextOf(site: Map<String, Any?>): String {
    val list = (site["domain_list"] as? List<*>)?.map { it.asString() }.orEmpty()
    if (list.isNotEmpty()) return list.joinToString("\n")
    return pickString(site, "domain")
}

private fun ipListTextOf(site: Map<String, Any?>): String {
    val list = (site["ip_list"] as? List<*>)?.map { it.asString() }.orEmpty()
    if (list.isNotEmpty()) return list.joinToString("\n")
    return pickString(site, "upstream", "server")
}

/** 列表副标题：回源个数与端口。 */
private fun siteSubtitle(site: Map<String, Any?>): String {
    val n = listOf("ip_list", "server", "server_list", "upstream", "proxy")
        .firstNotNullOfOrNull { k -> (site[k] as? List<*>) }
        ?.size ?: 0
    val port = pickString(site, "port").ifBlank {
        (site["listen_port"] as? List<*>)?.joinToString("/") { it.asString() }.orEmpty()
    }.ifBlank { "80" }
    return "回源 $n 个 · 端口 $port"
}

/** 把可能为数组或标量的字段统一成字符串列表。 */
private fun asTextList(v: Any?): List<String> = when (v) {
    is List<*> -> v.map { it.asString() }.filter { it.isNotBlank() }
    null -> emptyList()
    else -> v.asString().takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
}

/** 兼容换行 / 逗号 / 空格三种分隔。 */
private fun splitList(text: String): List<String> =
    text.split('\n', ',', '，', ' ', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** 回源节点：优先取结构化的 server_new，退化时按 server 里的纯地址串展示。 */
private fun returnSourceNodes(src: Map<String, Any?>): List<Map<String, Any?>> {
    val nw = src["server_new"].asList()
    if (nw.isNotEmpty()) return nw
    return asTextList(src["server"]).map { mapOf("address" to it) }
}

/** 端口转发里的节点列表，node_address_map 的键就是节点地址。 */
private fun lbNodes(cfg: Map<String, Any?>): List<NodeDraft> =
    cfg["node_address_map"].asMap().map { (addr, v) ->
        val n = v.asMap()
        NodeDraft(
            address = addr,
            port = pickString(n, "node_port", "port"),
            weight = pickString(n, "weight", "node_weight").ifBlank { "1" },
            maxFails = pickString(n, "max_fails", "node_max_fails").ifBlank { "3" },
            failTimeout = pickString(n, "fail_timeout", "node_fail_timeout").ifBlank { "10" },
            status = pickString(n, "status", "node_status").ifBlank { "1" },
            ps = pickString(n, "ps"),
            follow = n["node_address_follow"].toBoolState()
        )
    }

/** get_site_setting 里可能平铺也可能嵌在 proxy 下，两处都找。 */
private fun settingVal(s: Map<String, Any?>, vararg keys: String): String {
    for (k in keys) {
        val v = s.s(k)
        if (v.isNotBlank()) return v
    }
    val sub = s["proxy"].asMap()
    for (k in keys) {
        val v = sub.s(k)
        if (v.isNotBlank()) return v
    }
    return ""
}

private fun isValidPort(p: String): Boolean {
    val n = p.trim().toIntOrNull() ?: return false
    return n in 1..65535
}

private fun currentProtocols(m: Map<String, Any?>): Set<String> {
    val cur = asTextList(m["current_ssl_protocols"])
    if (cur.isNotEmpty()) return cur.toSet()
    return asTextList(m["ssl_protocols"]).take(2).toSet()
}

private fun currentCiphers(m: Map<String, Any?>): String = when (val c = m["current_ssl_ciphers"]) {
    null -> asTextList(m["ssl_ciphers"]).firstOrNull().orEmpty()
    is List<*> -> c.joinToString(":") { it.asString() }
    else -> c.asString()
}

/** 可选协议固定按 TLSv1.2 / TLSv1.3 呈现，后端返回什么顺序都不影响。 */
private fun sslProtocolOptions(m: Map<String, Any?>): List<String> {
    val fromApi = asTextList(m["ssl_protocols"])
    val known = listOf("TLSv1.2", "TLSv1.3")
    return (known + fromApi).distinct()
}

private fun cipherOptions(m: Map<String, Any?>): String = when (val c = m["ssl_ciphers"]) {
    null -> ""
    is List<*> -> c.joinToString(":") { it.asString() }
    else -> c.asString()
}

/** 时间戳可能是秒或毫秒，也可能是已格式化字符串。 */
private fun fmtStamp(v: Any?): String {
    if (v == null) return ""
    if (v is Number) {
        val ms = if (v.toLong() < 100_000_000_000L) v.toLong() * 1000 else v.toLong()
        return formatTime(ms)
    }
    val s = v.asString()
    val n = s.toLongOrNull() ?: return s
    val ms = if (n < 100_000_000_000L) n * 1000 else n
    return formatTime(ms)
}

private fun ngHumanKey(k: String): String = when (k) {
    "site_id" -> "站点 ID"
    "sitename" -> "站点名称"
    "site_name" -> "站点名称"
    "domain" -> "域名"
    "domain_list" -> "域名列表"
    "ip_list" -> "回源 IP"
    "is_https" -> "启用 HTTPS"
    "cdn" -> "CDN 加速"
    "is_cdn" -> "CDN 加速"
    "polling_algorithm" -> "轮询算法"
    "port" -> "端口"
    "listen_port" -> "监听端口"
    "listen_ipv6" -> "IPv6 监听"
    "status" -> "状态"
    "create_time" -> "创建时间"
    "server" -> "配置"
    else -> k
}

private fun renderAny(v: Any?): String = when (v) {
    null -> "-"
    is List<*> -> v.joinToString(", ") { it.toString() }.ifBlank { "-" }
    is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifBlank { "-" }
    is Boolean -> if (v) "是" else "否"
    else -> v.toString().ifBlank { "-" }
}
