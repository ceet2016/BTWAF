package com.bt.waf.data.model

import com.google.gson.annotations.SerializedName

/**
 * 堡塔云WAF 统一响应包装：{ "code": 0, "res": <data>, "nonce": <ts> }
 * code == 0 表示成功；失败时 res 通常为错误描述字符串。
 */
data class WafResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("res") val res: Any? = null,
    @SerializedName("nonce") val nonce: Long = 0
) {
    val isSuccess: Boolean get() = code == 0

    fun errorMessage(): String = when {
        isSuccess -> ""
        res is String -> res
        else -> "请求失败 (code=$code)"
    }

    @Suppress("UNCHECKED_CAST")
    fun asMap(): Map<String, Any?> = (res as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(): List<Any?> = (res as? List<Any?>) ?: emptyList()
}
