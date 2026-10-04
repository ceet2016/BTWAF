package com.bt.waf.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.WafApplication
import com.bt.waf.ui.components.ErrorBannerFlush
import com.bt.waf.ui.components.PrimaryButton
import com.bt.waf.ui.components.WafCard
import com.bt.waf.ui.theme.Space
import com.bt.waf.viewmodel.AuthViewModel
import com.bt.waf.viewmodel.WafViewModelFactory

/** 把服务端返回的 data:image/png;base64,... 解码为 Bitmap；失败返回 null。 */
private fun decodeBase64Image(dataUrl: String) = runCatching {
    val raw = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
    val bytes = Base64.decode(raw, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * 登录页。
 * 鉴权是三步走：① 访问后台入口页面建立 SID 会话 Cookie
 * ② POST /api/user/login 换 JWT ③ 之后所有接口请求带 `Authorization: bt<token>` 头。
 * 同一 IP 登录失败 5 次会被锁 300 秒，之后强制要求图形验证码。
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as WafApplication).repository
    val vm: AuthViewModel = viewModel(factory = WafViewModelFactory(repo))
    var pwdVisible by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(72.dp))

            // 品牌区
            Box(
                Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }
            Spacer(Modifier.height(Space.lg))
            Text(
                "堡塔云 WAF",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                "安卓管理端",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Space.xxl))

            // 表单卡片
            WafCard {
                Text(
                    "连接你的 WAF",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "填写网页版后台的完整地址（含安全入口路径）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(Space.lg))
                OutlinedTextField(
                    value = vm.webUrl,
                    onValueChange = { vm.webUrl = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://ip:port/入口路径") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    label = { Text("账号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Space.sm))
                OutlinedTextField(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (pwdVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { pwdVisible = !pwdVisible }) {
                            Icon(
                                if (pwdVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (pwdVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )

                // 图形验证码：服务端在同一 IP 登录失败后强制要求
                vm.captchaImage?.let { img ->
                    Spacer(Modifier.height(Space.md))
                    Row(verticalAlignment = Alignment.Bottom) {
                        val bmp = remember(img) { decodeBase64Image(img) }
                        Box(
                            Modifier
                                .size(width = 128.dp, height = 56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { vm.refreshCaptcha() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "点击刷新验证码",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("点击刷新", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.width(Space.sm))
                        OutlinedTextField(
                            value = vm.captchaInput,
                            onValueChange = { vm.captchaInput = it },
                            label = { Text("验证码") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.refreshCaptcha() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新验证码")
                        }
                    }
                }

                Spacer(Modifier.height(Space.sm))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.trust = !vm.trust }
                        .padding(vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = vm.trust, onCheckedChange = { vm.trust = it })
                    Spacer(Modifier.width(Space.xs))
                    Column {
                        Text("信任自签名证书", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "仅在测试环境使用，生产环境请勿开启",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (vm.error != null) {
                    Spacer(Modifier.height(Space.md))
                    // 登录表单本身就是居中窄栏，再加水平留白会把提示条缩得更窄。
                    ErrorBannerFlush(vm.error)
                }

                Spacer(Modifier.height(Space.lg))
                PrimaryButton(
                    text = if (vm.loading) "登录中…" else "登录",
                    onClick = { vm.login(onLoggedIn) },
                    enabled = !vm.loading,
                    loading = vm.loading
                )
            }

            Spacer(Modifier.height(Space.lg))
            Text(
                // 这行之前是单行 Text，实测在 1080 宽屏上末尾被截断
                // （显示成「…携带令」少半个字）。原因是它落在了
                // 带水平 padding 的 Column 里，可用宽度比整屏窄，
                // 单行 Text 又不自动换行。改成显式两行 + 居中即可。
                "登录流程：访问安全入口建立会话 → 账号密码换取 JWT\n→ 后续请求携带令牌",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = MaterialTheme.typography.labelSmall.fontSize * 1.6
            )
            Spacer(Modifier.height(Space.xxl))
        }
    }
}
