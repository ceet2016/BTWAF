package com.bt.waf.util

import kotlin.math.floor

/**
 * 从 WAF 动态响应对象中安全取出字符串。
 *
 * ⚠️ **Double 必须先归一化掉小数尾巴**，这不是洁癖，是真bug 的根因。
 *
 * WafResponse.res声明成 `Any?`，Gson 反序列化时无法预先知道某个字段
 * 将来会是什么类型，所以**所有 JSON 数字一律解成Double**，哪怕原值
 * 是整数 `1` —— 拿到的实际是 `1.0`。
 *
 * 于是 `asString()` 原来直接 `this.toString()` 得到的是 `"1.0"`，
 * 而各处判断写的是 `status == "1"` / `state == "0"` 这类字符串比较，
 * **永远为 false**。表现为：开关点「启用」→ 请求 200、后端状态确实改了
 * → 列表重新拉取仍显示「已停用」，用户怎么点都没用。
 * 实测就是 `wafmastersite` 的站点 `status` 字段（后端给的是 int 1/0）。
 *
 * 现在整值 Double 输出为 `"1"`，非整值仍输出 `"1.5"`，
 * 一处改动把全项目所有数字字段的比较一并修好。
 */
fun Any?.asString(): String = when (this) {
    null -> ""
    is String -> this
    is Double -> if (isFinite() && floor(this) == this &&
        this >= -9.223372036854776E18 && this <= 9.223372036854776E18
    ) {
        // toLong() 而不是 toInt()：LongSite 之类的字段可能超 32 位。
        // 越界值退回 toString()：Double.toLong() 对超范围值返回
        // Long.MIN_VALUE，静默变成 -9223372036854775808 是更糟的结果。
        toLong().toString()
    } else {
        toString()
    }
    is Float -> if (isFinite() && floor(this) == this &&
        this >= -9.223372036854776E18 && this <= 9.223372036854776E18
    ) {
        toLong().toString()
    } else {
        toString()
    }
    is Number -> this.toString()
    is Boolean -> this.toString()
    else -> this.toString()
}

fun Map<*, *>.s(key: String): String =
    (this[key] ?: this[key.lowercase()] ?: this[key.uppercase()])?.asString() ?: ""

// ⚠️ 不要直接 `toDouble().toInt()`：Double→Int 超出范围时**静默饱和**
// （返回 Int.MAX_VALUE / Int.MIN_VALUE），1e18 会变成 2147483647。
// 业务上这几乎不可能出现，但真出现时症状是「计数突然变成 21亿」，
// 排查成本极高，所以显式判一次Int 边界。
fun Map<*, *>.i(key: String): Int {
    val v = this[key] ?: return 0
    if (v is Number) {
        val d = v.toDouble()
        if (!d.isFinite() || d > Int.MAX_VALUE || d < Int.MIN_VALUE) return 0
        return d.toInt()
    }
    return v.toString().trim().toIntOrNull() ?: 0
}

fun Map<*, *>.l(key: String): Long {
    val v = this[key] ?: return 0L
    if (v is Number) {
        val d = v.toDouble()
        if (!d.isFinite() || d > Long.MAX_VALUE || d < Long.MIN_VALUE) return 0L
        return d.toLong()
    }
    return v.toString().trim().toLongOrNull() ?: 0L
}

/**
 * 把后端的开关值解析成 Boolean。
 *
 * aawaf 里「开/关」有三种表示并存：`1`（int）、`"1"`（字符串）、
 * `true`（布尔，海外版/部分 config 会给）。**必须三种都认**，
 * 少认一种就会出现「开关显示是关的，点开没反应」。
 *
 * 走[asString] 而不是 `toString()`：Gson 把 JSON 数字解成 Double，
 * `1.0` 直接 `toString()` 得"1.0"，比较必然落空（见 [asString] 注释）。
 */
fun Any?.asBool(): Boolean = when (this) {
    null -> false
    is Boolean -> this
    is Number -> this.toDouble() != 0.0
    else -> {
        val t = asString().trim()
        t == "1" || t.equals("true", ignoreCase = true) || t.equals("on", true) ||
            t.equals("yes", true) || t.equals("open", true)
    }
}

/**
 * 数字转成**JSON 字面量**，不带多余的小数尾巴。
 *
 * Gson 把 JSON 数字解成 Double，回传时`1.0.toString()` 给的是 `"1.0"`。
 * 后端 Go 侧若用 `int` /`int64` 接收，收到 `"1.0"` 要么类型报错、
 * 要么被静默截断 —— 而这类问题在 App 里完全看不出症状，
 * 只表现为「改了配置没生效」，极难排查。
 *
 * 所以凡是**要提交给后端的数字**（自己拼 JSON 时）都必须过这里：
 * 整值输出 `"1"`、`"0"`、`"1234567890123"`；非整值保持 `"1.5"`。
 *
 * 精度：`toLong()` 而不是 `toInt()`，10 位以上的时间戳不会被截断。
 *
 * ⚠️ 越界值必须退回 `toString()` 而不是硬转：整值 Double 一旦超出
 * Long 范围（比如 `1e19`），`toLong()` 的结果是Long.MIN_VALUE，
 * 会把一个巨大的数静默变成 -9223372036854775808 —— 那才是真的数据损坏。
 * 这种值本来也不可能是后端的 int/int64，退回 `"1.0E19"` 至少还能被Go
 * 的 float64 接住，不会静默改数。
 *
 * ⚠️ 这里的守卫是 **Long 边界（±9.22e18）**，不是 2^53精度边界。
 * 早先误用 2^53 守卫，结果 `1e18` 落到else 分支输出 `"1.0E18"`，
 * 而 Go 侧 `strconv.ParseInt("1e18", 10, 64)` 会直接解析失败 ——
 * 守卫缩小了覆盖范围，反而把本来能正常传的整数值弄坏了。
 */
fun Any?.asJsonNumber(): String = when (this) {
    is Double -> if (isFinite() && floor(this) == this &&
        this >= -9.223372036854776E18 && this <= 9.223372036854776E18
    ) {
        toLong().toString()
    } else {
        toString()
    }
    is Float -> if (isFinite() && floor(this) == this) toLong().toString() else toString()
    is Number -> toString()
    else -> toString()
}

/** 按候选键顺序取第一个非空字符串，用于列表项标题/副标题的容错展示。 */
fun pickString(map: Map<*, *>, vararg keys: String): String {
    for (k in keys) {
        val v = map.s(k)
        if (v.isNotBlank()) return v
    }
    return ""
}

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Map<String, Any?>> =
    (this as? List<*>)?.mapNotNull { it.asMap() } ?: emptyList()

/** 将 Map 中的数字/字符串统一为适合 JSON 提交的形态。 */
fun Map<String, String>.toParams(): Map<String, Any?> = this
