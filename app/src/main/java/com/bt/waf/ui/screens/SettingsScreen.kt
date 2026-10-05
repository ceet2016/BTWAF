package com.bt.waf.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asBool
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import com.bt.waf.viewmodel.SettingsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private enum class Panel {
    NONE, BASIC, PWD, AUTH, API, ADMIN, PORT,
    CERT, TWO_AUTH, SYSLOG, INTERCEPT, TIME, POLICY
}

/** 一个设置项：点击打开对应编辑面板。 */
private data class SetItem(
    val title: String,
    val desc: String,
    val value: String,
    val icon: ImageVector,
    val tint: Color,
    val panel: Panel,
    val dangerous: Boolean = false
)

/**
 * 系统设置。参数严格对齐 aawaf 后端 config 模块与 user 模块：
 *  - config/set_title { title, logo }
 *  - config/set_domain { accept_domain }
 *  - config/set_timeout { session_timeout }
 *  - config/set_language { lan }
 *  - config/set_ip { accept_ip }（逗号分隔字符串）
 *  - config/set_basic_auth { open, basic_user, basic_pwd }
 *  - config/set_open_api { type: 1重置Token 2开关 3限制地址, limit_addr }
 *  - config/set_admin_path { admin_path }
 *  - config/set_port { port }
 *  - user/update_profile { password }
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: SettingsViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var panel by remember { mutableStateOf(Panel.NONE) }
    var panelError by remember { mutableStateOf<String?>(null) }
    val bf = remember {
        mutableStateMapOf("title" to "", "domain" to "", "lan" to "zh", "timeout" to "120", "ips" to "")
    }
    val af = remember { mutableStateMapOf("user" to "", "pwd" to "") }
    val pf = remember { mutableStateMapOf("pwd" to "") }
    val apiLimit = remember { mutableStateOf("") }
    val adminPath = remember { mutableStateOf("") }
    val portValue = remember { mutableStateOf("") }
    // 以下状态对应 config 模块剩余接口的面板
    val certPem = remember { mutableStateOf("") }
    val certKey = remember { mutableStateOf("") }
    val passcode = remember { mutableStateOf("") }
    val sysHost = remember { mutableStateOf("") }
    val sysPort = remember { mutableStateOf("514") }
    val interceptText = remember { mutableStateOf("") }
    val pwdExpire = remember { mutableStateOf("0") }
    // 这三个开关用 by 委托，下游可以直接当 Boolean 用，不需要 .value
    var ntpOn by remember { mutableStateOf(false) }
    var warnOn by remember { mutableStateOf(false) }
    var complexityOn by remember { mutableStateOf(false) }
    var sysOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            scope.launch { snackbar.showSnackbar(it) }
        }
    }

    // 当前状态摘要
    val items = listOf(
        SetItem(
            "后台标题与入口", "站点标题、允许访问域名、会话超时、语言",
            vm.config["title"].asString().ifBlank { "未设置" },
            Icons.Filled.Badge, Color(0xFF2563EB), Panel.BASIC
        ),
        SetItem(
            "访问白名单", "仅允许指定 IP 访问后台（逗号分隔）",
            vm.config["accept_ip"].asList().joinToString(", ") { it.asString() }.ifBlank { "不限制" },
            Icons.Filled.Public, Color(0xFF0891B2), Panel.BASIC
        ),
        SetItem(
            "修改登录密码", "修改当前账号的管理员密码",
            "—", Icons.Filled.Lock, Color(0xFF7C3AED), Panel.PWD
        ),
        SetItem(
            "Basic 认证", "在后台前再加一层 HTTP Basic 认证",
            boolText(vm.basicAuth["open"]),
            Icons.Filled.Security, Color(0xFF16A34A), Panel.AUTH
        ),
        SetItem(
            "开放 API", "对外提供接口，可限制调用来源 IP",
            boolText(vm.apiInfo["open"]),
            Icons.Filled.Api, Color(0xFFF97316), Panel.API
        ),
        SetItem(
            "安全入口", "修改后台访问路径（至少 8 位）",
            "/" + vm.config["admin_path"].asString().trimStart('/'),
            Icons.Filled.AltRoute, Color(0xFFEA580C), Panel.ADMIN, dangerous = true
        ),
        SetItem(
            "后台端口", "修改后需同步更新客户端服务器地址",
            vm.port.ifBlank { "—" },
            Icons.Filled.Lan, Color(0xFFDC2626), Panel.PORT, dangerous = true
        ),
        SetItem(
            "控制台证书", "查看/替换后台 HTTPS 证书，或在线重新申请自签证书",
            if (vm.certPem.isBlank()) "未读取" else "已加载 ${vm.certPem.length} 字符",
            Icons.Filled.VpnKey, Color(0xFF0D9488), Panel.CERT
        ),
        SetItem(
            "动态口令", "登录时叠加一层 TOTP 动态口令（Google Authenticator 一类）",
            boolText(vm.twoStepAuth),
            Icons.Filled.PhoneAndroid, Color(0xFF7C3AED), Panel.TWO_AUTH
        ),
        SetItem(
            "Syslog 转发", "把 WAF 日志实时转发到外部 syslog 服务器",
            if (vm.syslog["open"].asBool()) {
                "已开启 → " + vm.syslog["host"].asString().ifBlank { "未设置地址" }
            } else {
                "已关闭"
            },
            Icons.Filled.SwapVert, Color(0xFF2563EB), Panel.SYSLOG
        ),
        SetItem(
            "拦截页文案", "自定义被拦截时页面上的说明文字",
            vm.config["interceptPage"].asString().ifBlank { "使用默认" }.take(24),
            Icons.Filled.WebAsset, Color(0xFFEA580C), Panel.INTERCEPT
        ),
        SetItem(
            "时间与告警", "NTP 自动校时、手动同步服务器时间、全局告警开关",
            "NTP " + boolText(vm.config["ntptime"]) + " · 告警 " +
                boolText(vm.config["warning_open"]),
            Icons.Filled.Schedule, Color(0xFF16A34A), Panel.TIME
        ),
        SetItem(
            "密码策略", "密码复杂度要求与过期时间",
            "复杂度 " + boolText(vm.config["password_complexity"]) +
                " · 过期 " + vm.config["password_expire"].asString().ifBlank { "0" } + " 天",
            Icons.Filled.Policy, Color(0xFFDC2626), Panel.POLICY
        )
    )

    fun open(p: Panel) {
        panelError = null
        when (p) {
            Panel.BASIC -> {
                bf["title"] = vm.config["title"].asString()
                bf["domain"] = vm.config["accept_domain"].asString()
                bf["lan"] = vm.config["language"].asString().ifBlank { "zh" }
                bf["timeout"] = vm.config["session_timeout"].asString().ifBlank { "120" }
                bf["ips"] = vm.config["accept_ip"].asList().joinToString(",") { it.asString() }
            }
            Panel.AUTH -> {
                af["user"] = vm.basicAuth["basic_user"].asString()
                af["pwd"] = ""
            }
            Panel.API -> apiLimit.value = vm.apiInfo["limit_addr"].asList().joinToString("\n") { it.asString() }
            Panel.ADMIN -> adminPath.value = vm.config["admin_path"].asString().trimStart('/')
            Panel.PORT -> portValue.value = vm.port
            Panel.PWD -> pf["pwd"] = ""
            Panel.CERT -> {
                certPem.value = ""
                certKey.value = ""
                // 回调给的是可空串，读取失败时后端返回的是错误而不是空内容
                vm.loadCert { c, k ->
                    certPem.value = c.orEmpty()
                    certKey.value = k.orEmpty()
                }
            }

            Panel.TWO_AUTH -> {
                passcode.value = ""
                vm.loadTwoAuth()
            }

            Panel.SYSLOG -> {
                sysOpen = vm.syslog["open"].asBool()
                sysHost.value = vm.syslog["host"].asString()
                sysPort.value = vm.syslog["port"].asString().ifBlank { "514" }
            }

            Panel.INTERCEPT -> interceptText.value = vm.config["interceptPage"].asString()
            Panel.TIME -> {
                pwdExpire.value = vm.config["password_expire"].asString().ifBlank { "0" }
                ntpOn = vm.config["ntptime"].asBool()
                warnOn = vm.config["warning_open"].asBool()
            }

            Panel.POLICY -> {
                pwdExpire.value = vm.config["password_expire"].asString().ifBlank { "0" }
                complexityOn = vm.config["password_complexity"].asBool()
            }

            Panel.NONE -> Unit
        }
        panel = p
    }

    /**
     * 提交一次写操作。
     *
     * aawaf 的错误语义是「HTTP 非 2xx + 响应体 res 里写原因」，所以必须等回调回来
     * 才能判断成败：成功才关弹窗，失败留在原地并把原因显示出来。
     */
    fun submit(action: ((String?) -> Unit) -> Unit) {
        saving = true
        panelError = null
        action { err ->
            saving = false
            if (err == null) {
                panel = Panel.NONE
                scope.launch { snackbar.showSnackbar("设置已保存") }
            } else {
                panelError = err
            }
        }
    }

    // SubPageScaffold 而不是 Scaffold：内层 Scaffold 会再消费一遍
    // windowInsets，顶部多出约 250px 空白（详见 SubPageScaffold 注释）。
    SubPageScaffold(snackbarHost = snackbar) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = cardPadding,
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(
                        "系统设置",
                        subtitle = "所有修改立即生效，请谨慎操作高危项",
                        modifier = Modifier.weight(1f)
                    )
                    SmallButton("刷新") { vm.reload() }
                }
            }

            item {
                WafCard {
                    SectionHeader("当前状态", subtitle = "config/get_config")
                    Spacer(Modifier.height(Space.sm))
                    DataRowDivider("后台端口", vm.port.ifBlank { "-" })
                    DataRowDivider("安全入口", "/" + vm.config["admin_path"].asString().trimStart('/'))
                    DataRowDivider("Basic 认证", boolText(vm.basicAuth["open"]))
                    DataRowDivider("开放 API", boolText(vm.apiInfo["open"]))
                    DataRowDivider(
                        "API 限制地址",
                        vm.apiInfo["limit_addr"].asList().joinToString(", ") { it.asString() }
                            .ifBlank { "不限制" }
                    )
                    DataRowDivider("会话超时", vm.config["session_timeout"].asString().ifBlank { "-" })
                }
            }

            item { SectionHeader("可修改项", subtitle = "${items.size} 项") }

            items(items, key = { it.panel.name + it.title }) { it ->
                WafCard(
                    onClick = { open(it.panel) },
                    contentPadding = rowCardPadding
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(icon = it.icon, tint = it.tint, size = 36.dp, corner = 10.dp)
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    it.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (it.dangerous) {
                                    Spacer(Modifier.width(Space.sm))
                                    StatusChip("高危", ChipTone.DANGER)
                                }
                            }
                            Spacer(Modifier.height(1.dp))
                            Text(
                                it.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(Space.xs))
                            Text(
                                it.value.ifBlank { "未设置" },
                                style = MaterialTheme.typography.labelMedium,
                                color = it.tint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(Space.sm))
                        Icon(
                            Icons.Filled.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }

    // ── 编辑面板 ──
    when (panel) {
        Panel.BASIC -> SettingsDialog(
            title = "后台基本信息",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = {
                // 这五项是五个独立接口，串行执行：任一失败即中断并把原因显示在弹窗内
                val steps: List<((String?) -> Unit) -> Unit> = listOf(
                    { cb -> vm.setTitle(bf["title"].orEmpty(), cb) },
                    { cb -> vm.setDomain(bf["domain"].orEmpty(), cb) },
                    { cb -> vm.setLanguage(bf["lan"].orEmpty(), cb) },
                    { cb -> vm.setSessionTimeout(bf["timeout"].orEmpty(), cb) },
                    { cb ->
                        vm.setAcceptIp(
                            bf["ips"].orEmpty()
                                .split(",", "\n", " ").map { it.trim() }.filter { it.isNotEmpty() },
                            cb
                        )
                    }
                )
                var idx = 0
                fun next(cb: (String?) -> Unit) {
                    if (idx >= steps.size) { cb(null); return }
                    steps[idx++](cb)
                }
                submit { cb ->
                    next { err ->
                        if (err != null) cb(err) else next(cb)
                    }
                }
            }
        ) {
            LabeledField(bf["title"].orEmpty(), { bf["title"] = it }, "后台标题")
            Spacer(Modifier.height(Space.sm))
            LabeledField(bf["domain"].orEmpty(), { bf["domain"] = it }, "允许访问域名", "留空表示不限制")
            Spacer(Modifier.height(Space.sm))
            DropdownField(
                value = bf["lan"].orEmpty(),
                onValueChange = { bf["lan"] = it },
                label = "后台语言",
                options = listOf("简体中文" to "zh", "English" to "en")
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                bf["timeout"].orEmpty(), { bf["timeout"] = it }, "会话超时（秒）",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                bf["ips"].orEmpty(), { bf["ips"] = it }, "允许访问 IP",
                hint = "多个用英文逗号分隔，留空表示不限制", singleLine = false
            )
        }

        Panel.PWD -> SettingsDialog(
            title = "修改登录密码",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = { submit { cb -> vm.updatePassword(pf["pwd"].orEmpty(), cb) } }
        ) {
            LabeledField(
                pf["pwd"].orEmpty(), { pf["pwd"] = it }, "新密码",
                isPassword = true
            )
            Spacer(Modifier.height(Space.sm))
            HintText("密码需满足后台复杂度要求，修改后立即生效，下一次登录请使用新密码。")
        }

        Panel.AUTH -> SettingsDialog(
            title = "Basic 认证",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = {
                submit { cb -> vm.setBasicAuth(true, af["user"].orEmpty(), af["pwd"].orEmpty(), cb) }
            }
        ) {
            LabeledField(af["user"].orEmpty(), { af["user"] = it }, "认证用户名")
            Spacer(Modifier.height(Space.sm))
            LabeledField(af["pwd"].orEmpty(), { af["pwd"] = it }, "认证密码", isPassword = true)
            Spacer(Modifier.height(Space.sm))
            HintText("开启后访问后台需先通过 HTTP Basic 认证。请务必牢记用户名与密码。")
        }

        Panel.API -> SettingsDialog(
            title = "开放 API",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = { submit { cb -> vm.setOpenApi(3, apiLimit.value, cb) } }
        ) {
            LabeledField(
                apiLimit.value, { apiLimit.value = it }, "限制地址",
                hint = "每行一个 IP，留空表示不限制", singleLine = false
            )
            Spacer(Modifier.height(Space.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SmallButton(
                    text = "切换 API 开关",
                    tone = ChipTone.INFO,
                    modifier = Modifier.weight(1f),
                    enabled = !saving
                ) { submit { cb -> vm.setOpenApi(2, "", cb) } }
                SmallButton(
                    text = "重置 Token",
                    tone = ChipTone.WARNING,
                    modifier = Modifier.weight(1f),
                    enabled = !saving
                ) { submit { cb -> vm.setOpenApi(1, "", cb) } }
            }
        }

        Panel.ADMIN -> SettingsDialog(
            title = "修改安全入口",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = { submit { cb -> vm.setAdminPath(adminPath.value, cb) } }
        ) {
            LabeledField(
                adminPath.value, { adminPath.value = it }, "安全入口路径",
                hint = "至少 8 位字母或数字，不要带开头斜杠"
            )
            Spacer(Modifier.height(Space.sm))
            HintText("修改后所有客户端都需要把服务器地址更新为 域名/新入口，否则无法登录。", danger = true)
        }

        Panel.PORT -> SettingsDialog(
            title = "修改后台端口",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = { submit { cb -> vm.setPort(portValue.value, cb) } }
        ) {
            LabeledField(
                portValue.value, { portValue.value = it }, "端口（1-65535）",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            HintText("修改端口后 App 需要用新端口重新连接，请确认防火墙已放行。", danger = true)
        }

        // ── 控制台自签证书 ──
        Panel.CERT -> SettingsDialog(
            title = "控制台 HTTPS 证书",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = {
                submit { cb -> vm.setCert(certPem.value, certKey.value, cb) }
            }
        ) {
            if (vm.certLoading) {
                LoadingBar(true)
                Spacer(Modifier.height(Space.sm))
            }
            if (!vm.certError.isNullOrBlank()) {
                ErrorBanner(vm.certError)
                Spacer(Modifier.height(Space.sm))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                SmallButton("读取当前证书", enabled = !vm.certLoading) { vm.loadCert() }
                SmallButton(
                    text = "在线重新申请",
                    tone = ChipTone.WARNING,
                    enabled = !saving
                ) {
                    panelError = null
                    saving = true
                    vm.regenerateCertificate { err ->
                        saving = false
                        if (err != null) panelError = err
                        else {
                            vm.loadCert()
                            scope.launch { snackbar.showSnackbar("已重新申请，证书已更新") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.md))
            LabeledField(
                value = certPem.value,
                onValueChange = { certPem.value = it },
                label = "证书内容（PEM）",
                hint = "以 -----BEGIN CERTIFICATE----- 开头",
                singleLine = false
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = certKey.value,
                onValueChange = { certKey.value = it },
                label = "私钥内容（PEM）",
                hint = "以 -----BEGIN PRIVATE KEY----- 开头",
                singleLine = false
            )
            Spacer(Modifier.height(Space.sm))
            HintText(
                "保存后服务端会重启控制台进程，期间后台短暂无响应。" +
                    "证书与私钥都不能为空，否则后台 HTTPS 会立刻失效。",
                danger = true
            )
        }

        // ── 动态口令 ──
        Panel.TWO_AUTH -> {
            // get_config 只给 two_step_auth 布尔，get_two_auth 才带用户名；
            // 后者失败时用前者兜底，至少开关状态是对的。
            val twoOpen = vm.twoStepAuth || vm.twoAuth["open"] == true ||
                vm.twoAuth["open"].asBool()
            SettingsDialog(
                title = "动态口令（TOTP）",
                saving = saving,
                error = panelError,
                onDismiss = { panel = Panel.NONE; panelError = null },
                // 语义是「切换」：按当前状态取反，未开启时点保存就是开启
                onConfirm = { submit { cb -> vm.setTwoAuth(!twoOpen, cb) } }
            ) {
                DataRow("当前状态", if (twoOpen) "已开启" else "未开启")
                if (twoOpen) {
                    DataRowDivider(
                        "认证用户名",
                        vm.twoAuth["username"].asString().ifBlank { "-" }
                    )
                }
                Spacer(Modifier.height(Space.md))
                HintText(
                    if (twoOpen) {
                        "点「保存」将关闭动态口令。开启时服务端会自动生成 8 位随机用户名并重置密钥，" +
                            "务必提前准备好验证器应用，否则会锁在门外。"
                    } else {
                        "点「保存」将开启动态口令：登录时除账号密码外还需输入 30 秒轮换的 6 位口令。" +
                            "开启后请立刻用下方输入框验证一次。"
                    }
                )
                if (twoOpen) {
                    Spacer(Modifier.height(Space.md))
                    LabeledField(
                        value = passcode.value,
                        onValueChange = { passcode.value = it },
                        label = "验证一个动态口令",
                        hint = "6 位数字，用于确认验证器配置正确",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                    Spacer(Modifier.height(Space.sm))
                    SmallButton(
                        text = "校验口令",
                        tone = ChipTone.INFO,
                        enabled = passcode.value.isNotBlank() && !saving
                    ) {
                        vm.checkTwoAuth(passcode.value) { ok, err ->
                            panelError = if (ok) null else (err ?: "校验失败")
                            if (ok) scope.launch { snackbar.showSnackbar("动态口令校验通过") }
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        "未开启时该接口返回「动态口令认证未开启」而不是认证失败，" +
                            "所以不能拿它来判断是否已启用。",
                        danger = true
                    )
                }
            }
        }

        // ── Syslog ──
        Panel.SYSLOG -> SettingsDialog(
            title = "Syslog 转发",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = {
                submit { cb ->
                    vm.setSyslog(
                        sysOpen,
                        sysHost.value,
                        sysPort.value.toIntOrNull() ?: 0,
                        cb
                    )
                }
            }
        ) {
            SwitchRow(
                title = "开启转发",
                description = "关闭时会清空目标地址",
                checked = sysOpen,
                onChange = { sysOpen = it }
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = sysHost.value,
                onValueChange = { sysHost.value = it },
                label = "Syslog 服务器地址",
                hint = "IP 或域名，如 10.0.0.5"
            )
            Spacer(Modifier.height(Space.sm))
            LabeledField(
                value = sysPort.value,
                onValueChange = { sysPort.value = it },
                label = "端口（1-65535）",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            HintText("地址与端口都会被服务端强校验，留空或超范围会直接返回错误。")
        }

        // ── 拦截页文案 ──
        Panel.INTERCEPT -> SettingsDialog(
            title = "拦截页说明文案",
            saving = saving,
            error = panelError,
            onConfirm = { submit { cb -> vm.setInterceptPage(interceptText.value, false, cb) } },
            onDismiss = { panel = Panel.NONE; panelError = null }
        ) {
            LabeledField(
                value = interceptText.value,
                onValueChange = { interceptText.value = it },
                label = "说明文字",
                hint = "显示在被拦截页面的描述区域",
                singleLine = false
            )
            Spacer(Modifier.height(Space.sm))
            HintText(
                "内容会写进 black.html 的 desc 节点，支持 HTML 片段。" +
                    "免费版接口会返回「免费版不支持修改拦截页」且 code 为 0，属于假成功。",
                danger = true
            )
        }

        // ── 时间与告警 ──
        Panel.TIME -> SettingsDialog(
            title = "时间同步与告警",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = {
                val steps: List<((String?) -> Unit) -> Unit> = listOf(
                    { cb -> vm.setNtp(ntpOn, cb) },
                    { cb -> vm.setWarningOpen(warnOn, cb) }
                )
                var idx = 0
                fun next(cb: (String?) -> Unit) {
                    if (idx >= steps.size) { cb(null); return }
                    steps[idx++](cb)
                }
                submit { cb -> next { err -> if (err != null) cb(err) else next(cb) } }
            }
        ) {
            SwitchRow(
                title = "NTP 自动校时",
                description = "由 WAF 自行定时与时间服务器同步",
                checked = ntpOn,
                onChange = { ntpOn = it }
            )
            SwitchRow(
                title = "全局告警",
                description = "关闭后不再推送任何告警通知",
                checked = warnOn,
                onChange = { warnOn = it }
            )
            Spacer(Modifier.height(Space.md))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(Space.md))
            SmallButton(
                text = "立即同步服务器时间",
                tone = ChipTone.WARNING,
                enabled = !saving
            ) {
                panelError = null
                saving = true
                vm.syncDate { err ->
                    saving = false
                    if (err != null) panelError = err
                    else scope.launch { snackbar.showSnackbar("服务器时间已同步") }
                }
            }
            Spacer(Modifier.height(Space.sm))
            HintText("立即同步会直接改写系统时间，影响证书校验与日志时间戳。", danger = true)
        }

        // ── 密码策略 ──
        Panel.POLICY -> SettingsDialog(
            title = "密码策略",
            saving = saving,
            error = panelError,
            onDismiss = { panel = Panel.NONE; panelError = null },
            onConfirm = {
                val steps: List<((String?) -> Unit) -> Unit> = listOf(
                    { cb -> vm.setPasswordComplexity(complexityOn, cb) },
                    { cb ->
                        vm.setPasswordExpire(pwdExpire.value.toIntOrNull() ?: -1, cb)
                    }
                )
                var idx = 0
                fun next(cb: (String?) -> Unit) {
                    if (idx >= steps.size) { cb(null); return }
                    steps[idx++](cb)
                }
                submit { cb -> next { err -> if (err != null) cb(err) else next(cb) } }
            }
        ) {
            SwitchRow(
                title = "密码复杂度要求",
                description = "开启后新密码必须包含大小写字母、数字与符号",
                checked = complexityOn,
                onChange = { complexityOn = it }
            )
            Spacer(Modifier.height(Space.md))
            LabeledField(
                value = pwdExpire.value,
                onValueChange = { pwdExpire.value = it },
                label = "密码过期天数",
                hint = "0 表示按服务端默认值处理",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            HintText("负数会被服务端拒绝；过期时间会强制已有账号在到期后改密。", danger = true)
        }

        Panel.NONE -> Unit
    }
}

/**
 * 统一风格的表单弹窗。
 *
 * aawaf 的写接口失败时把原因放在响应体 `res` 字段（HTTP 400/401），
 * 若只用 Snackbar 承载，弹窗会把它完全遮住 —— 用户点「保存」后看不到任何反馈。
 * 因此错误直接渲染在弹窗内部。
 */
@Composable
private fun SettingsDialog(
    title: String,
    saving: Boolean,
    error: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        icon = { Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(Space.md))
                    ErrorBanner(error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !saving,
                shape = RoundedCornerShape(10.dp),
                // 与 FormDialog 的确认按钮保持同一内边距，
                // 否则两个弹窗的「保存」按钮宽度不同，看起来就不对称。
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(Space.sm))
                }
                Text("保存", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } }
    )
}

private fun boolText(v: Any?): String = when (v?.toString()?.lowercase()) {
    "1", "true" -> "已开启"
    "0", "false" -> "已关闭"
    else -> "未知"
}
