package com.bt.waf.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.ErrorText
import com.bt.waf.ui.components.LabeledField
import com.bt.waf.ui.components.PrimaryButton
import com.bt.waf.viewmodel.AuthViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: AuthViewModel = viewModel(factory = WafViewModelFactory(repo))

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text("堡塔云WAF", style = MaterialTheme.typography.headlineMedium)
            Text("安卓管理端", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(24.dp))

            LabeledField(vm.webUrl, { vm.webUrl = it }, "服务器地址", "https://ip:port/入口路径")
            Spacer(Modifier.height(12.dp))
            LabeledField(vm.username, { vm.username = it }, "账号")
            Spacer(Modifier.height(12.dp))
            LabeledField(vm.password, { vm.password = it }, "密码", isPassword = true)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.trust, onCheckedChange = { vm.trust = it })
                Text("信任自签名证书（开发/测试环境）", style = MaterialTheme.typography.bodySmall)
            }

            ErrorText(vm.error)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = if (vm.loading) "登录中…" else "登录",
                onClick = { vm.login(onLoggedIn) },
                enabled = !vm.loading
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "说明：首次连接会自动访问后台入口路径以建立会话 Cookie；登录后所有请求携带 JWT。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
