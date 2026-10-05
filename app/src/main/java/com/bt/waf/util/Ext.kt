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
    is Double -> if (isFinite() && floor(this) == this) {
        //2.0.toLong() 而不是 toInt()：LongSite 之类的字段可能超 32 位
        toLong().toString()
    } else {
        toString()
    }
    is Float -> if (isFinite() && floor(this) == this) toLong().toString() else toString()
    is Number -> this.toString()
    is Boolean -> this.toString()
    else -> this.toString()
}

fun Map<*, *>.s(key: String): String =
    (this[key] ?: this[key.lowercase()] ?: this[key.uppercase()])?.asString() ?: ""

fun Map<*, *>.i(key: String): Int =
    (this[key]?.let { if (it is Number) it.toDouble().toInt() else it.toString().toIntOrNull() } ?: 0)

fun Map<*, *>.l(key: String): Long =
    (this[key]?.let { if (it is Number) it.toDouble().toLong() else it.toString().toLongOrNull() } ?: 0L)

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
