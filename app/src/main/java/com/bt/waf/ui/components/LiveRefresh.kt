package com.bt.waf.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/* ══════════════════ 实时刷新 ══════════════════ */

/**
 * 页面级自动轮询。
 *
 * ## 为什么要这个
 *
 * 概览页标题写着「实时态势」，用户理所当然期望数字在跳；日志中心是
 * 「实时」攻击流。但原来全站**零轮询** —— 所有页面只在 `LaunchedEffect(Unit)`
 * 里load() 一次，之后再也不动，数据会一直停在打开那一刻。
 *
 * 之前概览页给了个「刷新」按钮让用户手动点，勉强能用，但：
 *  - 「请求趋势」卡片上的刷新按钮放在标题栏里，和「实时采样」的文案自相矛盾
 *    （既然是实时，为什么还要我手点？）；
 *  - 攻击类型分布、命中开关这些也在变，但没有任何刷新入口。
 *
 * ## 三个关键设计
 *
 * 1. **生命周期感知**：`repeatOnLifecycle(RESUMED)` —— 切到后台立刻停轮询，
 *    回到前台继续。不做的话，后台也会持续打接口。
 * 2. **首帧立即加载**：协程体开头先 `block()` 一次，不等第一个周期。
 * 3. **不覆盖正在进行的加载**：`isLoading` 为真时跳过本轮，避免用户正在
 *    翻页/筛选时数据被后台轮询冲掉。
 *
 * @param intervalMillis 轮询间隔。默认 5 秒：够实时，又不至于打爆实例
 * @param isLoading 正在加载中则跳过本轮
 * @param enabled 传 false 可临时停掉（如弹窗打开时）
 * @param block 每轮要执行的动作，一般是 `vm.load()`
 */
@Composable
fun AutoRefresh(
    intervalMillis: Long = 5_000L,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    block: suspend () -> Unit,
) {
    // 捕获最新 lambda，避免调用方每次重组都重启协程
    val current by rememberUpdatedState(block)
    val lifecycleOwner = LocalLifecycleOwner.current

    // 间隔非法或显式关闭时，**完全不注册**轮询协程。
    // 这样 ManagedListScreen 那种「按配置决定要不要轮询」的场景，
    // 可以直接把 intervalMillis 传成非正数来表达「这一页不轮询」，
    // 不必让调用方去拼 enabled 条件，也不会留下一条永不触发的死循环。
    val active = enabled && intervalMillis > 0

    LaunchedEffect(lifecycleOwner, intervalMillis, active) {
        if (!active) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // 首帧立即加载，不等第一个周期
            current()
            while (true) {
                delay(intervalMillis)
                // 上一轮还没加载完就跳过，别把用户正在看的数据冲掉
                if (!isLoading) current()
            }
        }
    }
}

/**
 * 「实时」标识。
 *
 * 一个 8dp 的呼吸圆点 + 文案。用在概览页「实时态势」这类标题旁，
 * 让用户知道数据在自动更新，而不是自己看漏了。
 *
 * @param label 文案，默认「实时」
 * @param active false 时显示灰点+「已暂停」，用于加载中或出错时
 */
@Composable
fun LiveBadge(
    modifier: Modifier = Modifier,
    label: String = "实时",
    active: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveAlpha"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    else MaterialTheme.colorScheme.outline
                )
        )
        androidx.compose.material3.Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 距上次刷新的秒数（用在「实时」标识旁边）。
 *
 * 每秒走一格，归零后重新计。让「实时」是可信的 —— 用户能看见它真的在动。
 */
@Composable
fun rememberTickSeconds(periodMillis: Long): Int {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMillis) {
        elapsed = 0L
        while (true) {
            delay(1000)
            elapsed = (elapsed + 1000) % periodMillis.coerceAtLeast(1000L)
        }
    }
    return (elapsed / 1000).toInt()
}
