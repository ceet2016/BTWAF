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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asString
import com.bt.waf.util.pickString
import com.bt.waf.viewmodel.CertViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: CertViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }

    var showApply by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var deployTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val form = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) { vm.load(); vm.checkBtAccount() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    form.clear()
                    form["site_id"] = ""
                    form["domain"] = ""
                    showApply = true
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("申请证书") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LoadingBar(vm.loading)
            ErrorBanner(vm.error) { vm.load() }
            when {
                vm.loading && vm.certs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                vm.certs.isEmpty() -> EmptyState(
                    icon = Icons.Filled.LockOpen,
                    title = "暂无 SSL 证书",
                    description = "点击右下角「申请证书」通过 Let's Encrypt 签发",
                    actionText = "刷新",
                    onAction = { vm.load() }
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(Space.lg),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    item {
                        WafCard {
                            SectionHeader(
                                "证书库",
                                subtitle = "${vm.certs.size} 张证书",
                                action = { SmallButton("刷新") { vm.load() } }
                            )
                        }
                    }
                    items(vm.certs) { cert ->
                        val name = pickString(cert, "ssl_name", "name")
                        val brand = pickString(cert, "brand")
                        val domains = (cert["Domains"] as? List<*>)
                            ?.map { it.asString() }?.filter { it.isNotBlank() } ?: emptyList()
                        val notAfter = pickString(cert, "not_after")
                        val days = daysLeft(notAfter)
                        WafCard {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconBadge(
                                            icon = Icons.Filled.Lock,
                                            tint = MaterialTheme.colorScheme.primary,

                                            size = 36.dp,
                                            corner = 9.dp
                                        )
                                    Spacer(Modifier.width(Space.md))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            name.ifBlank { "(未命名)" },
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            brand.ifBlank { "未知颁发机构" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (days != null) {
                                        StatusChip(
                                            if (days <= 0) "已过期" else "剩 $days 天",
                                            if (days <= 0) ChipTone.DANGER
                                            else if (days <= 7) ChipTone.WARNING
                                            else ChipTone.SUCCESS
                                        )
                                    }
                                }
                                if (domains.isNotEmpty()) {
                                    Spacer(Modifier.height(Space.sm))
                                    Text(
                                        domains.joinToString("  ·  "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (notAfter.isNotBlank()) {
                                    Spacer(Modifier.height(Space.xs))
                                    Text(
                                        "到期时间 $notAfter",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Spacer(Modifier.height(Space.sm))
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                    SmallButton("部署到站点", tone = ChipTone.INFO) { deployTarget = cert }
                                    SmallButton("续签", tone = ChipTone.WARNING) {
                                        val did = domains.firstOrNull().orEmpty()
                                        if (did.isBlank()) {
                                            vm.error = "该证书没有可用域名，无法续签"
                                        } else {
                                            form["domain"] = did
                                            form["site_id"] = ""
                                            showApply = true
                                        }
                                    }
                                    SmallButton("删除", tone = ChipTone.DANGER) { deleteTarget = cert }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /* ── 申请 / 续签 ── */
    if (showApply) {
        FormDialog(
            title = "申请 Let's Encrypt 证书",
            icon = Icons.Filled.Lock,
            confirmText = "提交",
            busy = vm.saving,
            error = formError,
            onDismiss = { showApply = false; formError = null },
            onConfirm = {
                // 兼容换行 / 逗号两种分隔
                val domains = form["domain"].orEmpty()
                    .split('\n', ',', ' ')
                    .map { it.trim() }.filter { it.isNotEmpty() }
                if (domains.isEmpty()) {
                    formError = "请填写至少一个域名"
                } else {
                    val siteId = form["site_id"].orEmpty().trim()
                    vm.applyCert(domains, siteId) { e ->
                        if (e == null) { showApply = false; formError = null }
                        else formError = e
                    }
                }
                null
            }
        ) {
            if (vm.btAccountOk == false) {
                HintText("未检测到宝塔账号登录，签发可能失败。可先在服务器执行 bt 命令登录。", danger = true)
                Spacer(Modifier.height(Space.sm))
            }
            LabeledField(
                form["domain"].orEmpty(), { form["domain"] = it; formError = null },
                "域名", "每行一个，如 example.com",
                singleLine = false
            )
            Spacer(Modifier.height(Space.sm))
            if (vm.sites.isNotEmpty()) {
                DropdownField(
                    value = form["site_id"].orEmpty(),
                    onValueChange = { form["site_id"] = it },
                    label = "绑定站点（可选）",
                    options = listOf("不绑定" to "") + vm.sites.map {
                        val sid = it["site_id"].asString()
                        (it["site_name"].asString().ifBlank { sid }) to sid
                    }
                )
            } else {
                LabeledField(
                    form["site_id"].orEmpty(), { form["site_id"] = it },
                    "站点 ID（可选）", "site_info 表的 site_id"
                )
            }
        }
    }

    /* ── 删除确认 ── */
    deleteTarget?.let { cert ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    val n = pickString(cert, "ssl_name", "name")
                    deleteTarget = null
                    vm.delete(n) { e -> if (e != null) vm.error = e }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
            title = { Text("删除证书") },
            text = {
                Text(
                    "确认删除「${pickString(cert, "ssl_name", "name")}」吗？已使用该证书的站点将无法提供 HTTPS。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) }
        )
    }

    /* ── 部署到站点 ── */
    deployTarget?.let { cert ->
        var selected by remember(cert) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deployTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    val sslName = pickString(cert, "ssl_name", "name")
                    val site = vm.sites.firstOrNull { it["site_id"].asString() == selected }
                    if (site == null) {
                        vm.error = "请先选择站点"
                    } else {
                        deployTarget = null
                        vm.deploy(
                            sslName, selected, site["site_name"].asString()
                        ) { e -> if (e != null) vm.error = e }
                    }
                }) { Text("部署") }
            },
            dismissButton = { TextButton(onClick = { deployTarget = null }) { Text("取消") } },
            title = { Text("部署证书到站点") },
            text = {
                if (vm.sites.isEmpty()) {
                    Text("当前没有可绑定的防护网站。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        vm.sites.forEach { s ->
                            val sid = s["site_id"].asString()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selected = sid }
                                    .padding(vertical = Space.sm, horizontal = Space.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected == sid, { selected = sid })
                                Text(
                                    s["site_name"].asString().ifBlank { sid },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}

/** 解析 not_after（RFC3339）并计算剩余天数，解析失败返回 null。 */
private fun daysLeft(notAfter: String): Int? = runCatching {
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd HH:mm:ss"
    )
    val fmt = SimpleDateFormat(patterns.first(), Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    fmt.isLenient = false
    val date = patterns.firstNotNullOfOrNull { p ->
        runCatching {
            SimpleDateFormat(p, Locale.US).apply {
                if (p.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
            }.parse(notAfter)
        }.getOrNull()
    } ?: return null
    ((date.time - System.currentTimeMillis()) / 86400_000L).toInt()
}.getOrNull()
