package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.bt.waf.WafApplication
import com.bt.waf.ui.model.ManagedLists
import com.bt.waf.viewmodel.WafViewModelFactory
import kotlinx.coroutines.launch

private data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val NAV_ITEMS = listOf(
    NavItem("dashboard", "概览", Icons.Filled.Dashboard),
    NavItem("sites", "防护网站", Icons.Filled.Web),
    NavItem("attack_logs", "拦截记录", Icons.Filled.Shield),
    NavItem("list/ip_black", "IP 黑名单", Icons.Filled.Lock),
    NavItem("list/ip_white", "IP 白名单", Icons.Filled.Lock),
    NavItem("list/url_black", "URL 黑名单", Icons.Filled.Link),
    NavItem("list/url_white", "URL 白名单", Icons.Filled.Link),
    NavItem("list/ua_black", "UA 黑名单", Icons.Filled.Flag),
    NavItem("list/ua_white", "UA 白名单", Icons.Filled.Flag),
    NavItem("list/opt_logs", "操作日志", Icons.Filled.History),
    NavItem("list/customize", "自定义规则", Icons.AutoMirrored.Filled.Rule),
    NavItem("list/speed", "访问控制", Icons.Filled.Speed),
    NavItem("list/manm", "人机验证", Icons.Filled.Security),
    NavItem("list/regional", "区域拦截", Icons.Filled.Public),
    NavItem("rules", "防护规则", Icons.Filled.VerifiedUser),
    NavItem("notifications", "通知", Icons.Filled.Notifications),
    NavItem("settings", "系统设置", Icons.Filled.Settings),
    NavItem("profile", "账号 / 重启", Icons.Filled.Person)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as WafApplication
    val repo = app.repository
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(Unit) { runCatching { repo.bootstrap() } }

    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route ?: "dashboard"
    val title = NAV_ITEMS.firstOrNull { currentRoute.startsWith(it.route) }?.label ?: "堡塔云WAF"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("堡塔云WAF", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider()
                NAV_ITEMS.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = currentRoute.startsWith(item.route),
                        onClick = {
                            navController.navigate(item.route) { launchSingleTop = true }
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(item.icon, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("退出登录") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        scope.launch { runCatching { repo.logout() }.getOrDefault(Unit) }
                        onLogout()
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "菜单")
                        }
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController,
                startDestination = "dashboard",
                Modifier.fillMaxSize().padding(padding)
            ) {
                composable("dashboard") { DashboardScreen() }
                composable("sites") { SitesScreen() }
                composable("attack_logs") { AttackLogsScreen() }
                composable("rules") { RulesScreen() }
                composable("settings") { SettingsScreen() }
                composable("profile") { ProfileScreen() }
                composable("notifications") { NotificationsScreen() }
                composable(
                    "list/{key}",
                    arguments = listOf(navArgument("key") { type = NavType.StringType })
                ) { back ->
                    val key = back.arguments?.getString("key") ?: ""
                    val cfg = ManagedLists.byKey(key)
                    if (cfg != null) ManagedListScreen(cfg) else Text("未找到配置：$key")
                }
            }
        }
    }
}
