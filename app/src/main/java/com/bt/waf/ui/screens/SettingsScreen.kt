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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import com.bt.waf.util.asString
import com.bt.waf.viewmodel.SettingsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private enum class Panel { NONE, BASIC, PWD, AUTH, API, ADMIN, PORT }

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
    val bf = remember {
        mutableStateMapOf("title" to "", "domain" to "", "lan" to "zh", "timeout" to "120", "ips" to "")
    }
    val af = remember { mutableStateMapOf("user" to "", "pwd" to "") }
    val pf = remember { mutableStateMapOf("pwd" to "") }
    val apiLimit = remember { mutableStateOf("") }
    val adminPath = remember { mutableStateOf("") }
    val portValue = remember { mutableStateOf("") }
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
        )
    )

    fun open(p: Panel) {
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
            Panel.NONE -> Unit
        }
        panel = p
    }

    fun submit(action: () -> Unit) {
        saving = true
        action()
        panel = Panel.NONE
        saving = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Space.lg),
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
                WafCard(onClick = { open(it.panel) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(it.tint.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(it.icon, null, tint = it.tint, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(it.title, style = MaterialTheme.typography.titleSmall)
                                if (it.dangerous) {
                                    Spacer(Modifier.width(Space.sm))
                                    StatusChip("高危", ChipTone.DANGER)
                                }
                            }
                            Text(
                                it.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                it.value,
                                style = MaterialTheme.typography.labelMedium,
                                color = it.tint,
                                maxLines = 1
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.outline,
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
            onDismiss = { panel = Panel.NONE },
            onConfirm = {
                submit {
                    vm.setTitle(bf["title"].orEmpty()) {}
                    vm.setDomain(bf["domain"].orEmpty()) {}
                    vm.setLanguage(bf["lan"].orEmpty()) {}
                    vm.setSessionTimeout(bf["timeout"].orEmpty()) {}
                    vm.setAcceptIp(bf["ips"].orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }) {}
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
            onDismiss = { panel = Panel.NONE },
            onConfirm = { submit { vm.updatePassword(pf["pwd"].orEmpty()) {} } }
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
            onDismiss = { panel = Panel.NONE },
            onConfirm = {
                submit { vm.setBasicAuth(true, af["user"].orEmpty(), af["pwd"].orEmpty()) {} }
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
            onDismiss = { panel = Panel.NONE },
            onConfirm = { submit { vm.setOpenApi(3, apiLimit.value) {} } }
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
                    modifier = Modifier.weight(1f)
                ) { submit { vm.setOpenApi(2) {} } }
                SmallButton(
                    text = "重置 Token",
                    tone = ChipTone.WARNING,
                    modifier = Modifier.weight(1f)
                ) { submit { vm.setOpenApi(1) {} } }
            }
        }

        Panel.ADMIN -> SettingsDialog(
            title = "修改安全入口",
            saving = saving,
            onDismiss = { panel = Panel.NONE },
            onConfirm = { submit { vm.setAdminPath(adminPath.value) {} } }
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
            onDismiss = { panel = Panel.NONE },
            onConfirm = { submit { vm.setPort(portValue.value) {} } }
        ) {
            LabeledField(
                portValue.value, { portValue.value = it }, "端口（1-65535）",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            )
            Spacer(Modifier.height(Space.sm))
            HintText("修改端口后 App 需要用新端口重新连接，请确认防火墙已放行。", danger = true)
        }

        Panel.NONE -> Unit
    }
}

/** 统一风格的表单弹窗。 */
@Composable
private fun SettingsDialog(
    title: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Settings, null) },
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) { content() }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 弹窗内的说明文字。 */
@Composable
private fun HintText(text: String, danger: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (danger) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(Space.sm)
    ) {
        Icon(
            if (danger) Icons.Filled.Warning else Icons.Filled.Info,
            null,
            tint = if (danger) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun boolText(v: Any?): String = when (v?.toString()?.lowercase()) {
    "1", "true" -> "已开启"
    "0", "false" -> "已关闭"
    else -> "未知"
}
