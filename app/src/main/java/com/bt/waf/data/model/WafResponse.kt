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

    /**
     * 把 aawaf 返回的英文错误翻成中文。
     *
     * aawaf 的错误文案直接写在响应体的 `res` 里，且多数是英文
     * （如 "Token is expired"、"Unauthorized"）。之前原样透传，
     * 中文界面里突然冒出一句英文错误，用户既看不懂也不知道该怎么办。
     *
     * 只覆盖实际遇到过的几种，其余原文照常返回 —— 不做猜测式翻译，
     * 免得把没见过的英文硬译错意思。
     */
    private fun localize(raw: String): String {
        val t = raw.trim()
        return when {
            t.isEmpty() -> "请求失败 (code=$code)"
            //大小写与标点不敏感地匹配
            t.equals("Token is expired", true) ||
            t.contains("token is expired", true) ||
            t.contains("token expired", true) -> "登录已过期，请退出后重新登录"

            t.equals("Unauthorized", true) ||
            t.contains("unauthorized", true) -> "登录已失效，请重新登录"

            t.equals("Forbidden", true) ||
            t.contains("forbidden", true) -> "当前账号没有该操作权限"

            t.contains("not found", true) -> "请求的资源不存在或接口已变更"

            t.equals("Invalid token", true) ||
            t.contains("invalid token", true) -> "登录凭证无效，请重新登录"

            t.contains("password", true) &&
                (t.contains("wrong", true) || t.contains("incorrect", true)) ->
                "账号或密码错误"

            t.contains("signature", true) -> "请求签名校验失败，请检查实例地址是否正确"

            t.contains("timeout", true) -> "请求超时，请检查网络或实例地址"

            else -> raw
        }
    }

    fun errorMessage(): String = when {
        isSuccess -> ""
        res is String -> localize(res)
        else -> "请求失败 (code=$code)"
    }

    @Suppress("UNCHECKED_CAST")
    fun asMap(): Map<String, Any?> = (res as? Map<String, Any?>) ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(): List<Any?> = (res as? List<Any?>) ?: emptyList()
}
