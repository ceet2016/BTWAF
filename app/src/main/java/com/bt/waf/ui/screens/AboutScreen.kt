package com.bt.waf.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bt.waf.ui.components.SectionHeader
import com.bt.waf.ui.components.WafCard
import com.bt.waf.ui.theme.Space

/** 作者信息集中在这里，改版时只动这一处。 */
private const val AUTHOR_NAME = "雨落秋垣"
private const val AUTHOR_SITE = "https://uniomo.com"
private const val REPO_SITE = "https://cnb.cool"

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
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(44.dp)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    "版本 1.0.0",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
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