package com.bt.waf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bt.waf.ui.theme.Space
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ══════════════════ 基础容器 ══════════════════ */

/** 统一卡片：白底 + 细边框 + 圆角，替代裸 Card。 */
@Composable
fun WafCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val base = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = base,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) { Column(Modifier.padding(Space.lg), content = content) }
    } else {
        Card(
            modifier = base,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) { Column(Modifier.padding(Space.lg), content = content) }
    }
}

/** 区块标题：左侧竖条 + 标题 + 可选副标题/操作。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(if (subtitle != null) 32.dp else 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        action?.invoke()
    }
}

/* ══════════════════ 数据展示 ══════════════════ */

/** 键值行：等宽标签列（140dp），值列自适应。 */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp)
        )
        Text(
            value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 键值行 + 分隔线，用于列表式详情。 */
@Composable
fun DataRowDivider(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        DataRow(label, value)
        HorizontalDivider(
            Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** 状态标签：圆角胶囊，success/danger/warning/info/neutral。 */
@Composable
fun StatusChip(
    text: String,
    tone: ChipTone = ChipTone.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (tone) {
        ChipTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.DANGER -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ChipTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChipTone.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

enum class ChipTone { SUCCESS, DANGER, WARNING, INFO, NEUTRAL }

/* ══════════════════ KPI 指标卡 ══════════════════ */

/**
 * 指标卡：图标 + 指标名 + 大数值 + 可选副文本/趋势。
 * 用于概览页顶部 2x2 网格。
 */
@Composable
fun MetricCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    hint: String? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(Space.sm))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(Space.sm))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/* ══════════════════ 图表（纯 Canvas 自绘，无第三方依赖）══════════════════ */

/** 折线图：用于攻击趋势。points 为归一化前的原始值。 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillTop: Color = MaterialTheme.colorScheme.primary,
    labels: List<String> = emptyList()
) {
    val track = MaterialTheme.colorScheme.outlineVariant
    if (values.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "暂无足够数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val maxV = values.max().coerceAtLeast(1f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)
        fun pt(i: Int) = Offset(stepX * i, h - (values[i] / maxV) * (h * 0.86f) - h * 0.07f)

        // 横向网格线
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(track, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        // 面积填充
        val area = Path().apply {
            moveTo(0f, h)
            values.indices.forEach { lineTo(pt(it).x, pt(it).y) }
            lineTo(w, h)
            close()
        }
        drawPath(area, fillTop.copy(alpha = 0.10f))
        // 折线
        val line = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until values.size) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(line, lineColor, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
        // 末端圆点 + 值
        values.indices.forEach { i ->
            drawCircle(lineColor, radius = 2.6f, center = pt(i))
        }
    }
    if (labels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = Space.xs)) {
            labels.forEachIndexed { i, t ->
                if (i == 0 || i == labels.lastIndex || i == labels.size / 2) {
                    Text(
                        t,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(if (i == 0) 1f else if (i == labels.lastIndex) 1f else 1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 横向条形图：用于攻击来源 Top N。 */
@Composable
fun BarChart(
    items: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    if (items.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "暂无数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val maxV = items.maxOf { it.second }.coerceAtLeast(1f)
    val muted = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        items.forEach { (name, v) ->
            Column {
                Row {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        formatNumber(v.toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val r = size.height / 2
                    drawRoundRect(
                        muted,
                        size = Size(size.width, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                    )
                    val w = (size.width * (v / maxV)).coerceAtLeast(size.height)
                    drawRoundRect(
                        barColor,
                        size = Size(w, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                    )
                }
            }
        }
    }
}

/** 环形进度：用于 CPU/内存占用。 */
@Composable
fun DonutChart(
    percent: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    centerText: String = "",
    subText: String = ""
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 9f
            val inset = sw / 2 + 2f
            val arcSize = Size(size.width - sw - 4, size.height - sw - 4)
            val topLeft = Offset(inset, inset)
            drawArc(
                trackColor, -90f, 360f, false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
            val p = percent.coerceIn(0f, 100f)
            if (p > 0f) {
                drawArc(
                    color, -90f, 360f * p / 100f, false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centerText.ifBlank { "${percent.toInt()}%" },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subText.isNotBlank()) {
                Text(
                    subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* ══════════════════ 状态占位 ══════════════════ */

/** 空状态：图标 + 标题 + 说明（可带操作按钮）。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(Space.md))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (description != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(Space.lg))
            OutlinedButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** 错误提示条（带重试）。 */
@Composable
fun ErrorBanner(msg: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    if (msg.isNullOrBlank()) return
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ErrorOutline, null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("重试", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 顶部细进度条 + 可选下拉刷新。 */
@Composable
fun LoadingBar(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    LinearProgressIndicator(
        modifier.fillMaxWidth().height(2.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
}

/* ══════════════════ 表单 ══════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String = "",
    isPassword: Boolean = false,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (hint.isBlank()) null else ({ Text(hint, style = MaterialTheme.typography.bodySmall) }),
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(10.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.second == value }?.first
        ?: options.firstOrNull()?.first
        ?: ""
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(10.dp),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, v) ->
                DropdownMenuItem(
                    text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
                    onClick = { onValueChange(v); expanded = false }
                )
            }
        }
    }
}

/** 开关行：标题 + 说明 + Switch。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(Space.sm))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/* ══════════════════ 按钮 ══════════════════ */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(Space.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 小号按钮（描边）。[onClick] 必须放在最后一位，
 * 这样调用方才能用尾随 lambda 语法 `SmallButton("删除", tone = ChipTone.DANGER) { ... }`。
 */
@Composable
fun SmallButton(
    text: String,
    tone: ChipTone = ChipTone.NEUTRAL,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = when (tone) {
        ChipTone.DANGER -> ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        )
        ChipTone.WARNING -> ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
        else -> ButtonDefaults.outlinedButtonColors()
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = colors,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/* ══════════════════ 表单弹窗 ══════════════════ */

/**
 * 统一表单弹窗。
 *
 * 存在的理由：aawaf 的写接口失败时把原因放在响应体的 `res` 字段里（HTTP 400/401），
 * 如果只用 Snackbar 承载错误，弹窗会把它完全遮住 —— 用户点「保存」后看不到任何反馈，
 * 只能反复重试。因此这里把错误直接渲染在弹窗内部，并保证内容可滚动。
 *
 * @param error        错误文案，为 null 时不显示提示条
 * @param onDismiss    点击遮罩 / 返回键
 * @param onConfirm    点击「保存」。约定：返回错误文案表示失败（留在弹窗内），返回 null 表示成功（调用方负责关闭）
 * @param busy         提交中，禁用按钮并显示 loading
 */
@Composable
fun FormDialog(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    onConfirm: () -> String?,
    confirmText: String = "保存",
    dismissText: String = "取消",
    busy: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(Space.md))
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline, null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm() },
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(Space.sm))
                }
                Text(confirmText, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(dismissText) }
        }
    )
}

/** 弹窗内的说明条（区别于 [ErrorBanner] 的错误语义）。 */
@Composable
fun HintText(text: String, danger: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (danger) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (danger) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = bg) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm)
        )
    }
}

/* ══════════════════ 工具 ══════════════════ */

fun formatNumber(v: Long): String = when {
    v >= 100_000_000 -> "%.2f亿".format(v / 100_000_000.0)
    v >= 10_000 -> "%.1f万".format(v / 10_000.0)
    else -> v.toString()
}

fun formatBytes(v: Long): String {
    if (v <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    var x = v.toDouble()
    while (x >= 1024 && i < units.lastIndex) { x /= 1024; i++ }
    return if (i == 0) "${x.toInt()} ${units[i]}" else "%.2f ${units[i]}".format(x)
}

fun formatUpTime(seconds: Long): String {
    if (seconds <= 0) return "-"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return buildString {
        if (d > 0) append("${d}天")
        if (h > 0 || d > 0) append("${h}小时")
        append("${m}分钟")
    }
}

fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

/** 把 0/1、"true"/"false"、bool 统一成布尔。 */
fun Any?.toBoolState(): Boolean = when (this) {
    null -> false
    is Boolean -> this
    is Number -> toInt() == 1
    else -> toString() == "1" || toString().equals("true", ignoreCase = true)
}
