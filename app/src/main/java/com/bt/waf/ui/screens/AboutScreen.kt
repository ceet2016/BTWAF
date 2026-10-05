package com.bt.waf.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bt.waf.R
import com.bt.waf.data.update.AppUpdater
import com.bt.waf.ui.components.ChipTone
import com.bt.waf.ui.components.ErrorBanner
import com.bt.waf.ui.components.HintText
import com.bt.waf.ui.components.LoadingBar
import com.bt.waf.ui.components.PrimaryButton
import com.bt.waf.ui.components.SectionHeader
import com.bt.waf.ui.components.SmallButton
import com.bt.waf.ui.components.StatusChip
import com.bt.waf.ui.components.WafCard
import com.bt.waf.ui.theme.Space
import com.bt.waf.viewmodel.UpdateViewModel

/** 作者信息集中在这里，改版时只动这一处。 */
private const val AUTHOR_NAME = "雨落秋垣"
private const val AUTHOR_SITE = "https://uniomo.com"

/**
 * 源码仓库地址。
 *
 * 指向具体仓库而不是 cnb.cool 首页：在线更新的版本文件也从这个仓库取，
 * 两处必须是同一个地址，否则「关于」页点进去和「检查更新」查的不是一回事。
 */
private const val REPO_SITE = "https://cnb.cool/unicom/BTWAF"

/**
 * 「关于作者」页。
 *
 * 三个可点击的外部链接都用系统浏览器打开，不走 Intent chooser ——
 * 这三个地址是固定的，用户点了就是要去看，装一个选择器只是多一步。
 * `LocalUriHandler` 也行，但那样拿不到失败反馈；用原生 Intent 可以在
 * 没有浏览器时明确告诉用户，而不是点了没反应。
 */
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val uv: UpdateViewModel = viewModel()

    // ViewModel 需要 Context 才能拉起安装器与落地下载文件。
    // 用 SideEffect 而不是直接赋值：直接赋值会在每次重组时都执行，
    // 属于「在组合过程中写状态」的副作用写法，容易和后续状态更新打架。
    LaunchedEffect(Unit) { uv.appContext = ctx }

    fun openUrl(url: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            // 没有可用浏览器时，Snackbar 之外无处提示；这里至少不崩。
            Toast_(ctx, "未找到可打开链接的应用")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Space.xl))

        // 品牌区：与登录页同一套视觉（渐变盾形底+ 主标题），
        // 让「关于」页和登录页看起来是一家的，而不是两个 App。
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(24.dp))
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
            // 与登录页同一张 launcher 前景图，不在这里另画一个盾牌，
            // 否则改了桌面图标这两处又会各走各的。
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(84.dp),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(Modifier.height(Space.lg))
        Text(
            "堡塔云 WAF",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "安卓管理端",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(Space.xl))

        // 作者
        SectionHeader("作者")
        WafCard {
            InfoRow(
                icon = Icons.Filled.Person,
                label = "作者",
                value = AUTHOR_NAME
            )
            LinkRow(
                icon = Icons.Filled.Language,
                label = "作者网站",
                value = AUTHOR_SITE,
                onClick = { openUrl(AUTHOR_SITE) }
            )
            LinkRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = "源码仓库",
                value = REPO_SITE,
                onClick = { openUrl(REPO_SITE) }
            )
        }

        Spacer(Modifier.height(Space.lg))

        // 说明
        SectionHeader("说明")
        WafCard {
            Text(
                "本应用是宝塔云 WAF 的第三方安卓客户端，" +
                    "用于在手机上查看与操作网页版后台。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.md))
            Text(
                "服务端为开源项目 aawaf，本客户端不含任何服务端代码，" +
                    "所有数据均来自你自己部署的实例。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.md))
            //版本号不在这里显示：下方「在线更新」区第一行就是当前版本，
            //两处都写会让人怀疑是不是有两套版本信息（而且改版本时
            // 必然出现只改一处的情况）。
        }

        Spacer(Modifier.height(Space.lg))

        // ── 在线更新 ──
        // 放在「关于」页而不是单独一个 Tab：更新是低频操作，不值得占主导航位；
        // 而「关于」页本来就是用户查版本 / 找仓库的地方，语义吻合。
        SectionHeader("在线更新")
        WafCard {
            // 当前版本始终展示（从包信息读，不写死）。
            // 写死过一次「版本 1.0.0」，结果每次升版本都得记得改这里，
            // 漏改时用户看到「已是最新 1.1.0」却显示着 1.0.0，
            // 反而会以为更新没生效。
            InfoRow(
                icon = Icons.Filled.SystemUpdate,
                label = "当前版本",
                value = "${AppUpdater.currentVersionName} (${AppUpdater.currentVersionCode})"
            )
            Spacer(Modifier.height(Space.xs))
            LoadingBar(uv.state is UpdateViewModel.State.Checking)

            when (val s = uv.state) {
                is UpdateViewModel.State.Idle -> Text(
                    "点击下方按钮查询是否有新版本。查询只下载一个几百字节的版本信息文件，" +
                        "不会下载安装包。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateViewModel.State.Checking -> Text(
                    "正在查询…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateViewModel.State.UpToDate -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        "已是最新版本（${s.current}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is UpdateViewModel.State.Available -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("有新版本", ChipTone.INFO)
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "${s.versionName} ← ${AppUpdater.currentVersionName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        buildString {
                            append("安装包 ${s.sizeText}")
                            if (s.publishedAt.isNotBlank()) append(" · ${s.publishedAt}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.sm))
                    HintText(
                        if (s.sha256.isNotBlank()) "下载后会校验完整性，校验不通过不会安装。"
                        else "远端未提供校验值，请确认来源可信后再更新。"
                    )
                    Spacer(Modifier.height(Space.md))
                    // ⚠️ 纵向排列，不要放进 Row：
                    // PrimaryButton 内部是 modifier.fillMaxWidth()，
                    // 塞进 Row 会把旁边的「取消」挤出屏幕外（而且不会
                    // 报错，只是看不见，属于最难发现的那类布局问题）。
                    PrimaryButton(text = "立即更新", onClick = { uv.download { } })
                    Spacer(Modifier.height(Space.xs))
                    SmallButton(
                        text = "暂不更新",
                        tone = ChipTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth()
                    ) { uv.cancel() }
                }

                is UpdateViewModel.State.Downloading -> Column {
                    val total = s.total
                    val pct = if (total > 0) ((s.read * 100) / total).toInt().coerceIn(0, 100) else null
                    Text(
                        if (pct != null) "正在下载 $pct%　（${fmtMB(s.read)} / ${fmtMB(total)}）"
                        else "正在下载　（已下载 ${fmtMB(s.read)}）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(Space.sm))
                    // 百分比已知才用确定型进度条：总量未知时用 0f 会一直显示空条，
                    // 用户会以为卡死了。
                    if (pct != null) {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "支持断点续传，中断后再次点击会接着下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.sm))
                    SmallButton("取消下载", tone = ChipTone.DANGER) { uv.cancel() }
                }

                is UpdateViewModel.State.Ready -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "下载完成，校验通过",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    PrimaryButton(text = "安装更新", onClick = { uv.install() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        "系统会弹出安装确认。安装时会短暂退出应用，属正常现象。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is UpdateViewModel.State.Failed -> Column {
                    ErrorBanner(s.reason)
                    Spacer(Modifier.height(Space.sm))
                    SmallButton("重试", tone = ChipTone.NEUTRAL) { uv.check() }
                }
            }

            Spacer(Modifier.height(Space.md))
            SmallButton(
                text = "重新检查",
                tone = ChipTone.NEUTRAL,
                enabled = uv.state !is UpdateViewModel.State.Checking
            ) { uv.check() }
        }

        Spacer(Modifier.height(Space.lg))

        // 问题反馈
        SectionHeader("反馈")
        WafCard {
            LinkRow(
                icon = Icons.Filled.BugReport,
                label = "问题反馈",
                value = "前往源码仓库提交 Issue",
                onClick = { openUrl(REPO_SITE) }
            )
        }

        Spacer(Modifier.height(Space.xxl))
        Text(
            "Made with Compose · Material 3",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(Space.xxl))
    }
}

/** 只读信息行：左图标 + 标签 + 值，值在右侧。 */
@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(Space.md))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 可点击的外部链接行：值右侧带一个「打开」箭头。 */
@Composable
private fun LinkRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 行首图标底板：36dp 圆角方+ 12% 主色。 */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 轻量 Toast（Composable 里不能直接用 android.widget.Toast 的 context 扩展）。 */
private fun Toast_(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/** 字节数转 MB 文本，下载进度用。 */
private fun fmtMB(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)