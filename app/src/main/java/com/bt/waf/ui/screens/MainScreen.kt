package com.bt.waf.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.SectionHeader
import com.bt.waf.ui.model.ManagedLists
import com.bt.waf.ui.theme.Space
import kotlinx.coroutines.launch

/** 一级导航（底部 4 栏）。 */
private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val title: String
) {
    DASH("dashboard", "概览", Icons.Filled.Dashboard, "概览"),
    SITES("sites", "防护", Icons.Filled.Shield, "防护网站"),
    LOGS("attack_logs", "日志", Icons.AutoMirrored.Filled.ListAlt, "日志中心"),
    SYSTEM("system", "系统", Icons.Filled.Settings, "系统管理")
}

/** 二级入口分组。 */
private data class DrawerGroup(val title: String, val items: List<DrawerItem>)
private data class DrawerItem(val route: String, val label: String, val icon: ImageVector)

private val DRAWER_GROUPS = listOf(
    DrawerGroup("攻击分析", listOf(
        DrawerItem("report", "攻击报表", Icons.Filled.Assessment),
        DrawerItem("list/attack_logs_full", "拦截日志", Icons.Filled.GppBad),
        DrawerItem("list/ip_block_log", "IP 封禁记录", Icons.Filled.Block),
        DrawerItem("list/cc_events", "CC 攻击事件", Icons.Filled.Bolt),
        DrawerItem("list/rule_hit", "规则命中日志", Icons.Filled.Bolt),
        DrawerItem("list/opt_logs", "操作日志", Icons.Filled.History)
    )),
    DrawerGroup("防护策略", listOf(
        DrawerItem("rules", "防护规则总览", Icons.Filled.VerifiedUser),
        DrawerItem("customize", "自定义规则", Icons.AutoMirrored.Filled.Rule),
        DrawerItem("list/regional", "区域拦截", Icons.Filled.Public),
        DrawerItem("list/exclusive", "专属规则", Icons.AutoMirrored.Filled.OpenInNew),
        DrawerItem("replace", "内容替换", Icons.Filled.FindReplace),
        DrawerItem("list/http_method", "HTTP 方法限制", Icons.Filled.SwapHoriz),
        DrawerItem("list/http_header_len", "请求头长度限制", Icons.Filled.Compress),
        DrawerItem("list/user_limit", "单 IP 访问限制", Icons.Filled.PersonOutline),
        DrawerItem("list/spider", "搜索引擎爬虫", Icons.Filled.TravelExplore)
    )),
    DrawerGroup("黑白名单", listOf(
        DrawerItem("ip_group", "IP 分组", Icons.Filled.Folder),
        DrawerItem("list/ip_black", "IP 黑名单", Icons.Filled.Block),
        DrawerItem("list/ip_white", "IP 白名单", Icons.Filled.CheckCircle),
        DrawerItem("list/url_black", "URL 黑名单", Icons.Filled.Link),
        DrawerItem("list/url_white", "URL 白名单", Icons.Filled.Link),
        DrawerItem("list/ua_black", "UA 黑名单", Icons.Filled.Fingerprint),
        DrawerItem("list/ua_white", "UA 白名单", Icons.Filled.Fingerprint),
        DrawerItem("list/malicious_ip", "恶意 IP 库", Icons.Filled.Security)
    )),
    DrawerGroup("访问控制", listOf(
        DrawerItem("list/speed", "访问限速", Icons.Filled.Speed),
        DrawerItem("list/manm", "人机验证", Icons.Filled.SmartToy)
    )),
    DrawerGroup("证书与集群", listOf(
        DrawerItem("cert", "SSL 证书", Icons.Filled.Lock),
        DrawerItem("nginx", "反向代理", Icons.Filled.Dns),
        DrawerItem("waf_master", "DNS 集群", Icons.Filled.Hub),
        DrawerItem("list/load_balance", "负载均衡分组", Icons.AutoMirrored.Filled.CallSplit),
        DrawerItem("list/nodes", "集群节点", Icons.Filled.Lan)
    )),
    DrawerGroup("用户与系统", listOf(
        DrawerItem("list/users", "用户管理", Icons.Filled.Person),
        DrawerItem("list/roles", "角色权限", Icons.Filled.AdminPanelSettings),
        DrawerItem("notifications", "通知设置", Icons.Filled.NotificationsActive),
        DrawerItem("settings", "系统设置", Icons.Filled.Tune),
        DrawerItem("sysinfo", "系统信息", Icons.Filled.MonitorHeart),
        DrawerItem("profile", "账号信息", Icons.Filled.Badge),
        DrawerItem("about", "关于作者", Icons.Filled.Info)
    ))
)

private val ALL_TITLES: Map<String, String> =
    (listOf(
        Tab.DASH.route to Tab.DASH.title,
        Tab.SITES.route to Tab.SITES.title,
        Tab.LOGS.route to Tab.LOGS.title,
        Tab.SYSTEM.route to Tab.SYSTEM.title,
        "report" to "攻击报表",
        "cert" to "SSL 证书",
        "sysinfo" to "系统信息",
        "replace" to "内容替换",
        "ip_group" to "IP 分组",
        "customize" to "自定义防护规则",
        "nginx" to "反向代理",
        "waf_master" to "DNS 集群"
    ) + DRAWER_GROUPS.flatMap { g -> g.items.map { it.route to it.label } }).toMap()

/** 哪些路由需要显示返回箭头（不在一级 Tab 里的页面）。 */
private fun isTopLevel(route: String): Boolean = Tab.entries.any { it.route == route }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WafApplication
    val repo = app.repository
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { runCatching { repo.bootstrap() } }

    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route ?: Tab.DASH.route

    // 标题查表。
    //
    // 列表类子页注册的是带参数的 "list/{key}"，而
    // `NavDestination.route` 返回的是**注册时的路由模板**，不是实际值
    // —— 也就是 "list/{key}"，永远查不到 ALL_TITLES。
    // 于是标题回落成默认的「堡塔云WAF」：这就是进子页后
    // 顶部标题变成品牌名的原因（实测「拦截日志」页确实如此）。
    //
    // 修法：拿到 backStackEntry 的实际参数 key，去 ManagedLists 取权威title。
    // 不硬编码映射表 —— 那样迟早跟 ListConfig 不同步。
    val listKey = navBackStack?.arguments?.getString("key")
    val title = remember(currentRoute, listKey) {
        ALL_TITLES[currentRoute]
            ?: listKey?.let { ManagedLists.byKey(it)?.title }
            ?: "堡塔云WAF"
    }
    val topLevel = isTopLevel(currentRoute)

    fun go(route: String) {
        navController.navigate(route) {
            launchSingleTop = true
            if (isTopLevel(route)) {
                popUpTo(Tab.DASH.route) { saveState = true }
                restoreState = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(292.dp)
            ) {
                // 品牌区：与下方列表共用同一套左右留白（Space.lg = 16dp），
                // 之前品牌区 16dp、列表项 10dp，抽屉左右视觉上没对齐。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = Space.lg, vertical = Space.lg)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "W",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 21.sp
                            )
                        }
                        Spacer(Modifier.width(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "堡塔云WAF",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "安卓管理端",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    DRAWER_GROUPS.forEach { group ->
                        item(key = "h_${group.title}") {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = Space.lg, end = Space.lg,
                                    top = Space.md, bottom = Space.xs
                                )
                            )
                        }
                        items(group.items.size, key = { group.items[it].route }) { i ->
                            val item = group.items[i]
                            val selected = currentRoute == item.route
                            // 选中态改为「左侧 3dp 指示条 + 淡底色」：
                            // M3 默认 NavigationDrawerItem 的整块 primaryContainer
                            // 实心底色在 292dp 宽的抽屉里过于厚重。
                            Row(
                                Modifier
                                    .padding(horizontal = Space.lg)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable {
                                        go(item.route)
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(start = Space.md, end = Space.md, top = 11.dp, bottom = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else Color.Transparent
                                        )
                                )
                                Spacer(Modifier.width(Space.md))
                                Icon(
                                    item.icon, null,
                                    modifier = Modifier.size(19.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(Space.md))
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    item {
                        HorizontalDivider(
                            Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                        )
                        Row(
                            Modifier
                                .padding(horizontal = Space.lg)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    scope.launch { drawerState.close() }
                                    scope.launch { runCatching { repo.logout() } }
                                    onLogout()
                                }
                                .padding(horizontal = Space.md, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 补一个 3dp 的透明指示条占位：导航项是
                                // 指示条 + Space.md + 图标，退出登录没有指示条，
                                // 不占位的话这一行的图标会比上面所有项左移 3dp + Space.md。
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                )
                                Spacer(Modifier.width(Space.md))
                                Icon(
                                    Icons.AutoMirrored.Filled.Logout, null,
                                    modifier = Modifier.size(19.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            Spacer(Modifier.width(Space.md))
                            Text(
                                "退出登录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(Space.xl))
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                TopAppBar(
                    title = {
                        // 这里**不再显示实例地址**。
                        // 顶部栏是每一页的公共框架，等于每张截图都会带上
                        // 「https://81.70.19.168:8379/d75e7a5401」——
                        // 里面含内网 IP 和后台入口路径（相当于半个凭据），
                        // 截图发出去就是信息泄露。用户明确要求去掉。
                        //
                        // 改用「连接正常 + 品牌」两行：第二行显示实例**状态**
                        // 而不是地址，既不泄露又保留了「我连的是哪台」的信息量。
                        // 具体地址仍可在「系统 → 账号信息」里查看，那是用户
                        // 主动去看自己配置的地方。
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (app.authStore.hasSession)
                                                MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                        )
                                )
                                Spacer(Modifier.width(Space.sm))
                                Text(
                                    if (app.authStore.hasSession) "已连接 · ${app.authStore.hostLabel}"
                                    else "未连接",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (topLevel) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "菜单")
                            }
                        } else {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            bottomBar = {
                if (topLevel) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        Tab.entries.forEach { tab ->
                            val selected = currentRoute.startsWith(tab.route)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { go(tab.route) },
                                icon = { Icon(tab.icon, null, modifier = Modifier.size(22.dp)) },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController,
                startDestination = Tab.DASH.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                composable(Tab.DASH.route) {
                    DashboardScreen(
                        onOpenLogs = { go("list/attack_logs_full") },
                        onOpenReport = { go("report") }
                    )
                }
                composable(Tab.SITES.route) { SitesScreen() }
                composable(Tab.LOGS.route) { LogCenterScreen(onOpen = { go(it) }) }
                composable(Tab.SYSTEM.route) { SystemHomeScreen(onOpen = { go(it) }) }

                composable("rules") { RulesScreen() }
                composable("settings") { SettingsScreen() }
                composable("profile") { ProfileScreen() }
                composable("about") { AboutScreen() }
                composable("notifications") { NotificationsScreen() }
                composable("report") { ReportScreen() }
                composable("cert") { CertScreen() }
                composable("sysinfo") { SysInfoScreen() }
                composable("ip_group") { IpGroupScreen() }
                composable("replace") { ReplaceScreen() }
                composable("customize") { CustomizeScreen() }
                composable("nginx") { NginxScreen() }
                composable("waf_master") { WafMasterScreen() }

                composable(
                    "list/{key}",
                    arguments = listOf(navArgument("key") { type = NavType.StringType })
                ) { back ->
                    val key = back.arguments?.getString("key") ?: ""
                    val cfg = ManagedLists.byKey(key)
                    if (cfg != null) {
                        ManagedListScreen(cfg)
                    } else {
                        Column(Modifier.fillMaxSize().padding(Space.lg)) {
                            SectionHeader("功能未接入", subtitle = "配置标识：$key")
                        }
                    }
                }
            }
        }
    }

    // token 过期重登录：aawaf 的 JWT ttl 默认 2 小时，过期后所有 /api 返回
    // "Token is expired"。这里统一拦一次，弹登录框让用户重新登录，
    // 而不是让每个页面各自显示错误、用户逐页点重试（重试也必然失败）。
    val needRelogin by repo.needRelogin.collectAsState()
    if (needRelogin) {
        AlertDialog(
            onDismissRequest = { /* 必须登录，不允许点外面关掉 */ },
            icon = { Icon(Icons.Filled.LockClock, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("登录已过期") },
            text = {
                //文案要短：AlertDialog 的文本区没有高度上限，
                // 字一多对话框就撑得很高、确认按钮被顶到屏幕下缘。
                Text(
                    "请重新登录后继续操作。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        repo.acknowledgeReloginPrompt()
                        onLogout()
                    },
                    shape = RoundedCornerShape(10.dp)
                ) { Text("去登录") }
            }
        )
    }
}
