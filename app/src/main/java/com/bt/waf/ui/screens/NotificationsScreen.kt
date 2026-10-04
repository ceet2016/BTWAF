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
import com.bt.waf.viewmodel.NotificationsViewModel
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

/** 各渠道的字段定义与展示元数据。 */
private data class ChannelMeta(
    val icon: ImageVector,
    val tint: Color,
    val desc: String,
    val fields: List<Pair<String, String>>
)

private val CHANNEL_META: Map<String, ChannelMeta> = mapOf(
    "email" to ChannelMeta(
        Icons.Filled.Email, Color(0xFF2563EB), "通过 SMTP 邮件发送攻击告警",
        listOf(
            "email" to "发件邮箱",
            "host" to "SMTP 地址",
            "port" to "SMTP 端口",
            "password" to "SMTP 密码 / 授权码",
            "receivers" to "收件人（逗号分隔）"
        )
    ),
    "dingding" to ChannelMeta(
        Icons.Filled.Chat, Color(0xFF2563EB), "钉钉群机器人 Webhook 推送",
        listOf("url" to "机器人 Webhook")
    ),
    "feishu" to ChannelMeta(
        Icons.Filled.Forum, Color(0xFF0891B2), "飞书群机器人 Webhook 推送",
        listOf("url" to "机器人 Webhook")
    ),
    "weixin" to ChannelMeta(
        Icons.Filled.ChatBubble, Color(0xFF16A34A), "企业微信群机器人 Webhook 推送",
        listOf("url" to "机器人 Webhook")
    )
)

private fun metaOf(type: String): ChannelMeta = CHANNEL_META[type] ?: ChannelMeta(
    Icons.Filled.Notifications, Color(0xFF64748B), "自定义通知渠道",
    listOf("url" to "Webhook / 地址")
)

/**
 * 告警通知渠道配置。
 *  - notification/list 返回渠道数组，每项 { type, name, is_configured, config }
 *  - notification/update 保存渠道参数
 *  - notification/clear  清除渠道配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen() {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: NotificationsViewModel = viewModel(factory = WafViewModelFactory(repo))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var clearTarget by remember { mutableStateOf<Map<String, Any?>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.message = null
        }
    }

    val configuredCount = vm.channels.count { it["is_configured"] == true }

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
                        "告警通知",
                        subtitle = "$configuredCount / ${vm.channels.size} 个渠道已配置",
                        modifier = Modifier.weight(1f)
                    )
                    SmallButton("刷新") { vm.load() }
                }
            }

            if (vm.loading && vm.channels.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(Space.xxl), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (vm.channels.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.NotificationsOff,
                        title = "没有可用的通知渠道",
                        description = "当前 WAF 版本未提供告警推送能力"
                    )
                }
            } else {
                items(vm.channels, key = { it["type"].asString() }) { ch ->
                    val type = ch["type"].asString()
                    val meta = metaOf(type)
                    val configured = ch["is_configured"] == true
                    val cfg = ch["config"].asMap()

                    WafCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(meta.tint.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(meta.icon, null, tint = meta.tint, modifier = Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(Space.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ch["name"].asString().ifBlank { type },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    meta.desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusChip(
                                if (configured) "已配置" else "未配置",
                                if (configured) ChipTone.SUCCESS else ChipTone.NEUTRAL
                            )
                        }

                        if (configured && cfg.isNotEmpty()) {
                            Spacer(Modifier.height(Space.sm))
                            cfg.entries.take(3).forEach { (k, v) ->
                                DataRow(
                                    label = k,
                                    value = when (v) {
                                        is List<*> -> v.joinToString(", ") { it.toString() }
                                        null -> "-"
                                        else -> v.toString()
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(Space.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            SmallButton(
                                text = if (configured) "修改配置" else "立即配置",
                                tone = ChipTone.INFO,
                                modifier = Modifier.weight(1f)
                            ) {
                                values.clear()
                                meta.fields.forEach { (k, _) ->
                                    values[k] = if (k == "receivers") {
                                        cfg["receivers"].asList().joinToString(",") { it.asString() }
                                    } else {
                                        cfg[k].asString()
                                    }
                                }
                                editing = ch
                            }
                            if (configured) {
                                SmallButton(
                                    text = "清除",
                                    tone = ChipTone.DANGER,
                                    modifier = Modifier.weight(1f)
                                ) { clearTarget = ch }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(Space.xl)) }
        }
    }

    // 配置弹窗
    editing?.let { ch ->
        val type = ch["type"].asString()
        val meta = metaOf(type)
        AlertDialog(
            onDismissRequest = { editing = null },
            icon = { Icon(meta.icon, null, tint = meta.tint) },
            title = { Text("配置 ${ch["name"].asString().ifBlank { type }}") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    meta.fields.forEach { (k, label) ->
                        Spacer(Modifier.height(Space.sm))
                        LabeledField(
                            value = values[k].orEmpty(),
                            onValueChange = { values[k] = it },
                            label = label,
                            isPassword = k == "password",
                            singleLine = k != "receivers"
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        val cfg = HashMap<String, Any?>()
                        meta.fields.forEach { (k, _) ->
                            cfg[k] = if (k == "receivers") {
                                values[k].orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
                            } else {
                                values[k].orEmpty()
                            }
                        }
                        vm.save(type, cfg) { e ->
                            saving = false
                            editing = null
                            if (e != null) vm.error = e
                        }
                    }
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }

    // 清除确认
    clearTarget?.let { ch ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("清除通知配置") },
            text = {
                Text(
                    "确认清除「${ch["name"].asString()}」的配置吗？清除后该渠道将不再接收告警。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = ch["type"].asString()
                    clearTarget = null
                    vm.clear(t) { e -> if (e != null) vm.error = e }
                }) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { clearTarget = null }) { Text("取消") } }
        )
    }
}
