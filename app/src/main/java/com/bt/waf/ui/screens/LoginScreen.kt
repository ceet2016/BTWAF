package com.bt.waf.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.BitmapFactory
import android.util.Base64
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.ErrorText
import com.bt.waf.ui.components.LabeledField
import com.bt.waf.ui.components.PrimaryButton
import com.bt.waf.viewmodel.AuthViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

/** 把服务端返回的 data:image/png;base64,... 解码为 Bitmap；失败返回 null。 */
private fun decodeBase64Image(dataUrl: String) = runCatching {
    val raw = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
    val bytes = Base64.decode(raw, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

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

            // 图形验证码：服务端在同一 IP 登录失败后强制要求
            vm.captchaImage?.let { img ->
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bmp = remember(img) { decodeBase64Image(img) }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "点击刷新验证码",
                            modifier = Modifier
                                .size(width = 140.dp, height = 56.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { vm.refreshCaptcha() }
                        )
                    } else {
                        Box(
                            Modifier
                                .size(width = 140.dp, height = 56.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { vm.refreshCaptcha() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("点击刷新", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = vm.captchaInput,
                        onValueChange = { vm.captchaInput = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
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
