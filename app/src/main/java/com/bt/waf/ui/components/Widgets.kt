package com.bt.waf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bt.waf.ui.theme.Space
import com.bt.waf.util.asBool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ══════════════════ 基础容器 ══════════════════ */

/** 全站统一的卡片形状与边框，卡片外观只由这一处决定。 */
private val CardShape = RoundedCornerShape(16.dp)
private val CardBorder = @Composable { BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) }

/* ══════════════════ 卡片内边距标准 ══════════════════ */

/**
 * 内边距只有两种语义，不允许页面自己写第三种。
 *
 * 之前全站出现过 6 种 contentPadding 写法，其中「列表行卡片」的垂直内边距
 * 同时存在 `vertical = Space.xs`（4dp）和 `vertical = Space.sm`（8dp）两种值：
 * Nginx 页面的站点行是 8dp，规则/自定义/报表页的同类行是 4dp。
 * 视觉后果是——从 Nginx 切到防护规则，同一张卡片的高度差了一倍，
 * 列表节奏被打乱，这就是用户说的「有的地方不对称」。
 *
 * 现在把语义收敛到这两个常量：
 *  - [cardPadding]信息卡：标题、说明、键值表、图表。上下左右都放宽。
 *  - [rowCardPadding]  列表行卡：内部只放一个 [ListRow] / [CompactNavRow]。
 *                        ListRow 自身已有 vertical = Space.sm 的内边距，
 *                        这里水平收进到 Space.md，让图标与相邻卡片的图标对齐。
 */
val cardPadding: PaddingValues
    @Composable get() = PaddingValues(Space.lg)

val rowCardPadding: PaddingValues
    @Composable get() = PaddingValues(horizontal = Space.md, vertical = Space.xs)

/* ══════════════════ 子页面 Scaffold ══════════════════ */

/**
 * 子页面统一外壳，**只留底部内边距，顶部与左右一律交给 [contentPadding]**。
 *
 * 修的是「子页面顶部空出一大块」这个视觉缺陷。
 *
 * 成因是嵌套：MainScreen 已经有 Scaffold + TopAppBar + NavigationBar，
 * 子页面在 NavHost 里**又套一层 Scaffold**。M3 的 Scaffold 默认带
 * `contentWindowInsets = ScaffoldDefaults.contentWindowInsets`
 * （会消费状态栏与导航栏 inset），于是内层又算一遍顶部 inset，
 * 叠加 LazyColumn 自己的 `contentPadding = cardPadding`，
 * 顶部就多出将近一整块空白。
 *
 * 实测量化（1080x2412）：
 *  - 系统管理首页（无内层 Scaffold）顶部栏底 273→ 首张卡片 314，空白 41px ✅
 *  - 通知设置（内层 Scaffold）  顶部栏底 273 → 首张卡片 566，空白 293px ❌
 *
 * 所以内层必须 `contentWindowInsets = WindowInsets(0, 0, 0, 0)`，
 * 再把 padding 只取 bottom（留给 FAB / snackbar / 底部导航）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageScaffold(
    modifier: Modifier = Modifier,
    snackbarHost: SnackbarHostState? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { snackbarHost?.let { SnackbarHost(it) } },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        //只保留底部：顶部栏由 MainScreen 负责，左右由 contentPadding 负责。
        val bottomOnly = PaddingValues(bottom = padding.calculateBottomPadding())
        content(bottomOnly)
    }
}

/* ══════════════════ 搜索工具条 ══════════════════ */

/**
 * 搜索工具条：输入框 + 搜索键 + 可选清空键，**三者严格等高**。
 *
 * 修掉的是子页面（拦截日志、IP 封禁记录等全部 ManagedList 页面）的老问题：
 * 原代码给`OutlinedTextField` 写死了 `.height(52.dp)`，而 M3 的
 * TextField 自身最小高度就是 56dp —— 强行压到 52dp 会把占位文字
 * 「搜索…」的上沿**切掉一截**（实机可见），同时旁边的
 * `FilledTonalIconButton` 也是 52.dp，视觉重心比输入框高，看着不齐。
 *
 * 做法：**不写死高度**。TextField 用它自己的最小高度，
 * 外层 Row 加 `height(IntrinsicSize.Min)` 让按钮跟着输入框走，
 * 再用 `Modifier.height(IntrinsicSize.Min)` 把按钮统一到同一基准。
 * 这样以后改Material3 版本也不会再被裁。
 *
 * @param onSearch 触发搜索（点按钮或按键盘搜索键）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchToolbar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    onClearLogs: (() -> Unit)? = null,
    placeholder: String = "搜索…",
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    IconButton(onClick = { onKeywordChange(""); onSearch() }) {
                        Icon(Icons.Filled.Close, "清空", Modifier.size(16.dp))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide(); onSearch()
            }),
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            // **不要写死 height** —— M3 TextField 最小高度 56dp，
            // 压到 52dp 会裁掉文字上沿。宽度自适应即可。
            modifier = Modifier.weight(1f)
        )

        // 按钮不写死尺寸：Row 高度由 TextField 决定，图标按钮填满即可对齐。
        FilledTonalIconButton(
            onClick = { keyboard?.hide(); onSearch() },
            modifier = Modifier.size(ToolbarHeight)
        ) { Icon(Icons.Filled.Search, contentDescription = "搜索") }

        if (onClear != null) {
            FilledTonalIconButton(
                onClick = onClear,
                modifier = Modifier.size(ToolbarHeight),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Icon(Icons.Filled.Close, contentDescription = "清空条件") }
        }

        if (onClearLogs != null) {
            FilledTonalIconButton(
                onClick = onClearLogs,
                modifier = Modifier.size(ToolbarHeight),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Icon(Icons.Filled.DeleteSweep, contentDescription = "清空日志") }
        }
    }
}

/** 工具条里图标按钮的边长，与输入框视觉等高。 */
val ToolbarHeight = 52.dp

/* ══════════════════ 页内标签栏标准 ══════════════════ */
/**
 * 页内分段标签栏（全站唯一定义）。
 *
 * 修掉的是用户点名的那个「不对称」：原来 7 个页面各自写
 * `ScrollableTabRow(edgePadding = 0.dp)`，于是
 *  - 第一个标签的文字从屏幕 x=0 开始，而它下方的卡片左边距是 44px
 *    —— 标签和内容左边线差了 44px，一眼就能看出不齐；
 *  - 选中指示器（那条蓝线）也跟着从 x=0 起画，视觉上像是「漏了一截」。
 *
 * ## 为什么不用 ScrollableTabRow
 *
 * 两个原因，都是实测踩出来的：
 *  1. **它不保证选中项滚入视野**。标签多于 4 个时最后一个只露出一半
 *     （实机「爬虫与蜘蛛」被裁成「爬虫与蜘」）。它内部有滚动状态但不对外
 *     暴露，Compose BOM 2024.06.00 对应的 Material3 1.3.0 也没有
 *     `scrollState` 参数（1.4 起改名 PrimaryScrollableTabRow 才开放）——
 *     传了直接编译不过，别再试。
 *  2. `edgePadding` 和指示器宽度要分别调，两处参数散落在 7 个调用点。
 *
 * 现在用 [LazyRow] + [LazyListState] 自己实现：
 *  - 首尾各留 [Space.lg]，左边线与卡片内容严格对齐；
 *  - 选中项变化时 `animateScrollToItem` 把它完整滚进视野；
 *  - 蓝线宽度按标签文字估算，正好落在文字下方居中。
 *
 * @param labels 标签文本
 * @param selectedIndex 当前选中下标
 * @param onSelect 选中回调
 * @param containerColor 底色，调用方按所在卡片层选择 surface 或 background
 */
@Composable
fun WafTabRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    val listState = rememberLazyListState()

    // 选中项滚入视野 —— 这正是 ScrollableTabRow 做不到的那部分。
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in labels.indices) {
            // 负offset 把目标项推到起始位置，左侧留出已看过的标签作上下文。
            val offset = -(labels.size * 40)
            runCatching { listState.animateScrollToItem(selectedIndex, offset) }
        }
    }

    Surface(modifier = modifier.fillMaxWidth(), color = containerColor) {
        LazyRow(
            state = listState,
            // 关键：首尾内边距让第一个标签左边线== 卡片内容左边线
            contentPadding = PaddingValues(horizontal = Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(labels) { i, label ->
                val selected = i == selectedIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .clickable { onSelect(i) }
                        .padding(horizontal = Space.xs, vertical = Space.sm),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        // 长标签不折行，宁可被裁也不要撑破布局
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 蓝线：宽度跟着标签走，正好在文字下方居中。
                    // 未选中时宽度 0，视觉上就是「线只出现在当前标签下」。
                    Box(
                        Modifier
                            .width(if (selected) tabIndicatorWidth(label) else 0.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

/**
 * 蓝线宽度估算：中文按全角、其余按半角。
 *
 * 不必精确 —— 只要与标签文字视觉等宽即可。真正的字宽测量要
 * `TextMeasurer`（实验 API）或 `onTextLayout` 回调，
 * 不值得为一条 2dp 的装饰线引入。
 */
@Composable
private fun tabIndicatorWidth(label: String): Dp {
    val density = LocalDensity.current
    val fontSize = MaterialTheme.typography.labelLarge.fontSize.value
    val cjk = label.count { it.code in 0x4E00..0x9FFF }
    val rest = label.length - cjk
    val px = cjk * fontSize + rest * fontSize * 0.55f
    return (px * density.density).dp
}

/**
 * 统一卡片。
 *
 * 内边距固定 [Space.lg]，全站所有卡片一致 —— 这是「对称」的基础：
 * 之前各页面临时改 padding，导致同类内容左右留白不一致。
 */
@Composable
fun WafCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Space.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = base,
            shape = CardShape,
            colors = colors,
            border = CardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) { Column(Modifier.padding(contentPadding), content = content) }
    } else {
        Card(
            modifier = base,
            shape = CardShape,
            colors = colors,
            border = CardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) { Column(Modifier.padding(contentPadding), content = content) }
    }
}

/**
 * 区块标题：左侧竖条 + 标题 + 可选副标题/操作。
 *
 * 竖条高度用 [IntrinsicSize.Min] + [fillMaxHeight] 让它**自动等于文字块高度**。
 * 之前写死 32.dp / 18.dp，遇上字号不同的标题就出现竖线短一截或长一截的错位。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(Space.sm + 2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
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

/** 列表区块的标题行：左侧标题 + 右侧操作，垂直居中且底部留白统一。 */
@Composable
fun ListHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 等宽栅格行。
 *
 * 之前各页面的 KPI / 统计区都是手写 `Row { Box(weight(1f)); Box(weight(1f)) }`，
 * 间距靠 Spacer 手调，很容易出现「左列比右列宽」或「两列间距不等」的错位。
 * 这里统一：列间距固定 [Space.sm]，所有列等宽。
 */
@Composable
fun GridRow(
    modifier: Modifier = Modifier,
    gap: Dp = Space.sm,
    content: @Composable RowScope.() -> Unit
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** 栅格中的单个格子，等宽。必须在 [GridRow] 的 RowScope 内调用。 */
@Composable
fun RowScope.GridCell(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.weight(1f), contentAlignment = Alignment.Center, content = content)
}

/**
 * 列表项：左侧图标 + 中间主副两行 + 右侧操作。
 *
 * 这是全站列表的统一骨架。之前每个页面各写一套 Row，导致图标大小、间距、
 * 文字基线全都不一致 —— 视觉上「东一块西一块」。
 */
@Composable
fun ListRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 38.dp,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val row = @Composable {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon = icon, tint = iconTint, size = iconSize)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(Space.sm))
                trailing()
            }
        }
    }
    if (onClick != null) {
        Box(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
        ) { row() }
    } else {
        Box(modifier.fillMaxWidth()) { row() }
    }
}

/**
 * 紧凑导航行：分组卡片内部的二级入口。
 *
 * 与 [ListRow] 的区别是图标更小（30dp）、垂直内边距更小，
 * 这样一组 8 个入口能收进一张卡片里，页面不会拉得过长。
 */
@Composable
fun CompactNavRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon = icon, tint = iconTint, size = 30.dp, corner = 9.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 圆角图标底板：全站统一尺寸与圆角，列表/卡片/入口共用。 */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    corner: Dp = 11.dp
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/* ══════════════════ 数据展示 ══════════════════ */

/**
 * 键值行：标签列与值列宽度按比例分配。
 *
 * 用 [weight] 而不是固定 dp 宽度：这样不同页面的 DataRow 在任何屏幕宽度下
 * 标签列占比都一致，长短不一的卡片之间也能对齐。此前写死 132.dp，
 * 在窄屏上会把值挤成两三行。
 */
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
            modifier = Modifier.weight(0.38f)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            value.ifBlank { "-" },
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(0.62f)
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

/** 状态标签：圆角胶囊，success/danger/warning/info/neutral。 */@Composable
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

/**
 * 图表下方的三段式轴标签：左 / 中 / 右。
 *
 * 之前用 `Spacer(weight(1f))` 在两段文字之间撑开，导致中间那段并没有落在
 * 真正的几何中心 —— 左边文字越长，中间标签就越往右偏，视觉上「不对称」。
 * 这里改用固定比例：左右各占 1，中间占 2 且居中，保证中段永远在正中。
 */
@Composable
fun AxisLabels(
    left: String,
    right: String,
    modifier: Modifier = Modifier,
    center: String? = null
) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            left,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (center != null) {
            Text(
                center,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(2f)
            )
        } else {
            Spacer(Modifier.weight(2f))
        }
        Text(
            right,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/* ══════════════════ KPI 指标卡 ══════════════════ */

/**
 * 指标卡：图标 + 指标名 + 大数值 + 可选副文本。
 * 用于概览页与各页的 2x2 栅格。
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
        // ⚠️ fillMaxWidth 不能少。
        // GridCell 用的是 weight(1f)，它只把分配到的宽度作为**约束**传下去，
        // 并不强制子组件填满。Card 默认按内容收缩，于是两列的实际宽度
        // 取决于各自文案长度：实测「规则总数」卡 256px、「已加载」卡 306px，
        // 左边距 158 / 右边距 131，肉眼一眼就能看出左窄右宽。
        // 加上 fillMaxWidth 后两列才真正等宽。
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = icon, tint = accent, size = 26.dp, corner = 8.dp)
                Spacer(Modifier.width(Space.sm + 2.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(Space.md))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hint != null) {
                Spacer(Modifier.height(3.dp))
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

/**
 * 错误提示条（带重试）。
 *
 * 注意这里自己带水平内边距：调用点分布在裸 Column 与 LazyColumn 的 item 里，
 * 它们的 contentPadding 各自不同（甚至没有）。如果 ErrorBanner 依赖调用方留白，
 * 一旦某个页面忘了加，红色条就顶到屏幕左右边缘 —— 这正是用户说的「不对称」。
 * 自己兜住留白，所有 20 个调用点一次性对称。
 */
@Composable
fun ErrorBanner(msg: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    ErrorBannerImpl(msg, modifier.padding(horizontal = Space.lg), onRetry)
}

/**
 * 错误提示条（不自带留白版）。
 *
 * 用于调用方已经在带 contentPadding 的LazyColumn 里、
 * 或者本来就要求错误条通栏（如全屏对话框内部）的场景。
 */
@Composable
fun ErrorBannerFlush(msg: String?, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    ErrorBannerImpl(msg, modifier, onRetry)
}

@Composable
private fun ErrorBannerImpl(msg: String?, modifier: Modifier, onRetry: (() -> Unit)?) {
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

/**
 * 把 0/1、"true"/"false"、bool 统一成布尔。
 *
 * 委托给 [asBool] 而不是自己再写一遍：这里的 `else -> toString() == "1"`
 * 与 util里的实现是同一套规则，两处各写一份迟早会分叉。
 */
fun Any?.toBoolState(): Boolean = asBool()
