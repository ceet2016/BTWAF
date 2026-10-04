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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bt.waf.ui.components.*
import com.bt.waf.ui.theme.Space

/**
 * 系统 tab 首页：用分组卡片罗列全部二级入口，点击回抛路由给 MainScreen 导航。
 * 相比平铺菜单，这里有明确的信息架构（分组 + 说明 + 计数徽标）。
 */
private data class SysEntry(
    val route: String,
    val title: String,
    val desc: String,
    val icon: ImageVector
)

private data class SysGroup(
    val title: String,
    val entries: List<SysEntry>
)

private val SYS_GROUPS = listOf(
    SysGroup("防护策略", listOf(
        SysEntry("rules", "防护规则总览", "各防护模块的开关与运行模式", Icons.Filled.VerifiedUser),
        SysEntry("list/regional", "区域拦截", "按国家/地区封禁来访", Icons.Filled.Public),
        SysEntry("list/exclusive", "专属规则", "对特定站点放宽验证", Icons.Filled.OpenInNew),
        SysEntry("replace", "内容替换", "自动替换响应中的关键词", Icons.Filled.FindReplace),
        SysEntry("list/http_method", "HTTP 方法限制", "禁用危险请求方法", Icons.Filled.SwapHoriz),
        SysEntry("list/http_header_len", "请求头长度限制", "防御超长头部攻击", Icons.Filled.Compress),
        SysEntry("list/user_limit", "单 IP 访问限制", "限制单 IP 请求频次", Icons.Filled.PersonOutline),
        SysEntry("list/spider", "搜索引擎爬虫", "放行或拦截搜索引擎", Icons.Filled.TravelExplore)
    )),
    SysGroup("黑白名单", listOf(
        SysEntry("ip_group", "IP 分组", "把常用 IP 归组便于复用", Icons.Filled.Folder),
        SysEntry("list/ip_black", "IP 黑名单", "命中即拦截或封禁", Icons.Filled.Block),
        SysEntry("list/ip_white", "IP 白名单", "完全跳过所有检测", Icons.Filled.CheckCircle),
        SysEntry("list/url_black", "URL 黑名单", "拦截指定路径请求", Icons.Filled.Link),
        SysEntry("list/url_white", "URL 白名单", "放行指定路径请求", Icons.Filled.Link),
        SysEntry("list/ua_black", "UA 黑名单", "按 User-Agent 拦截", Icons.Filled.Fingerprint),
        SysEntry("list/ua_white", "UA 白名单", "按 User-Agent 放行", Icons.Filled.Fingerprint)
    )),
    SysGroup("访问控制", listOf(
        SysEntry("list/speed", "访问限速", "按站点限制带宽", Icons.Filled.Speed),
        SysEntry("list/manm", "人机验证", "CC 场景弹出验证", Icons.Filled.SmartToy),
        SysEntry("list/customize", "自定义防护规则", "自编写 WAF 表达式", Icons.Filled.Rule)
    )),
    SysGroup("证书与集群", listOf(
        SysEntry("cert", "SSL 证书管理", "申请 / 部署 / 到期提醒", Icons.Filled.Lock),
        SysEntry("list/load_balance", "负载均衡分组", "多节点流量分配", Icons.Filled.CallSplit),
        SysEntry("list/nodes", "集群节点", "查看各节点状态", Icons.Filled.Lan)
    )),
    SysGroup("用户与审计", listOf(
        SysEntry("list/users", "用户管理", "子账号与密码", Icons.Filled.Person),
        SysEntry("list/roles", "角色权限", "菜单级权限分配", Icons.Filled.AdminPanelSettings),
        SysEntry("list/opt_logs", "操作日志", "谁在什么时候改了什么", Icons.Filled.History),
        SysEntry("notifications", "通知设置", "告警推送渠道", Icons.Filled.NotificationsActive)
    )),
    SysGroup("系统", listOf(
        SysEntry("settings", "系统设置", "端口 / 入口 / 认证", Icons.Filled.Tune),
        SysEntry("sysinfo", "系统信息", "CPU / 内存 / 磁盘详情", Icons.Filled.MonitorHeart),
        SysEntry("profile", "账号信息", "修改密码与资料", Icons.Filled.Person)
    ))
)

@Composable
fun SystemHomeScreen(onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = cardPadding,
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        item {
            WafCard(contentPadding = rowCardPadding) {
                ListRow(
                    icon = Icons.Filled.Settings,
                    title = "系统管理",
                    subtitle = "策略、证书、用户与运维",
                    iconTint = MaterialTheme.colorScheme.primary,
                    iconSize = 40.dp
                )
            }
        }

        SYS_GROUPS.forEach { group ->
            item(key = "h_${group.title}") {
                SectionHeader(group.title, subtitle = "${group.entries.size} 项")
            }
            item(key = "g_${group.title}") {
                // 同一分组的入口收进一张卡片：分组之间留白 16dp，
                // 组内条目间距 2dp，视觉上「一组一块」而不是「一堆散卡」。
                WafCard(contentPadding = rowCardPadding) {
                    group.entries.forEachIndexed { i, entry ->
                        if (i > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        CompactNavRow(
                            icon = entry.icon,
                            title = entry.title,
                            subtitle = entry.desc,
                            onClick = { onOpen(entry.route) }
                        )
                    }
                }
            }
        }
    }
}
