package com.bt.waf.util

/** 从 WAF 动态响应对象中安全取出字符串。 */
fun Any?.asString(): String = when (this) {
    null -> ""
    is String -> this
    is Number -> this.toString()
    is Boolean -> this.toString()
    else -> this.toString()
}

fun Map<*, *>.s(key: String): String =
    (this[key] ?: this[key.lowercase()] ?: this[key.uppercase()])?.asString() ?: ""

fun Map<*, *>.i(key: String): Int =
    (this[key]?.let { if (it is Number) it.toInt() else it.toString().toIntOrNull() } ?: 0)

fun Map<*, *>.l(key: String): Long =
    (this[key]?.let { if (it is Number) it.toLong() else it.toString().toLongOrNull() } ?: 0L)

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
