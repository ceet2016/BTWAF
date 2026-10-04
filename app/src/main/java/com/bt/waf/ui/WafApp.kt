package com.bt.waf.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.bt.waf.BTWAFTheme
import com.bt.waf.WafApplication
import com.bt.waf.ui.screens.LoginScreen
import com.bt.waf.ui.screens.MainScreen

@Composable
fun WafApp() {
    val app = LocalContext.current.applicationContext as WafApplication
    var loggedIn by remember { mutableStateOf(app.authStore.hasSession) }
    BTWAFTheme {
        if (loggedIn) {
            MainScreen(onLogout = { loggedIn = false })
        } else {
            LoginScreen(onLoggedIn = { loggedIn = true })
        }
    }
}
