package com.bt.waf.data.repo

import com.bt.waf.data.model.WafResponse
import com.bt.waf.data.net.WafClient
import com.bt.waf.data.store.AuthStore
import okhttp3.Request

class ApiException(message: String, val code: Int = -1) : Exception(message)

/**
 * WAF 业务访问层。
 * - 所有后台接口均为 POST + JSON，路径为 module/action。
 * - 登录前需先 bootstrap（GET 后台入口路径以植入 SID 会话 Cookie）。
 * - 登录后所有 /api 请求由 OkHttp 拦截器自动注入 Authorization: bt<token>。
 */
class WafRepository(
    val authStore: AuthStore,
    val wafClient: WafClient
) {
    /** 登录前应用连接信息（网页地址、信任自签）并重建客户端。 */
    fun applyConnection(webUrl: String, trustSelfSigned: Boolean) {
        authStore.webUrl = webUrl
        authStore.trustSelfSigned = trustSelfSigned
        wafClient.reconfigure()
    }

    /** GET 后台入口页面，植入 SID Cookie（安全入口中间件要求）。 */
    suspend fun bootstrap() {
        val url = authStore.adminFull
        if (url.isBlank()) return
        try {
            val req = Request.Builder().url(url).get().build()
            wafClient.client.newCall(req).execute().use { /* ignore body */ }
        } catch (_: Exception) {
            // 自签证书/网络异常时忽略，登录时会再次尝试
        }
    }

    /** 账号密码登录。成功写入 token 并重建客户端（拦截器实时读取 token）。 */
    suspend fun login(username: String, password: String): Result<Unit> {
        bootstrap()
        val resp = wafClient.api.post(
            "user/login",
            mapOf("username" to username, "password" to password)
        )
        if (!resp.isSuccess) return Result.failure(Exception(resp.errorMessage()))
        val res = resp.asMap()
        val token = res["token"] as? String
        if (token.isNullOrBlank()) return Result.failure(Exception("登录成功但未返回 token"))
        authStore.token = token
        wafClient.reconfigure()
        return Result.success(Unit)
    }

    suspend fun logout() {
        runCatching { wafClient.api.post("user/logout", emptyMap()) }
        authStore.clearSession()
        wafClient.reconfigure()
    }

    /** 通用调用：返回原始响应（含 code）。 */
    suspend fun call(path: String, params: Map<String, Any?> = emptyMap()): WafResponse =
        wafClient.api.post(path, params)

    /** 通用调用：失败抛 [ApiException]，成功返回响应。 */
    suspend fun safeCall(path: String, params: Map<String, Any?> = emptyMap()): WafResponse {
        val resp = wafClient.api.post(path, params)
        if (!resp.isSuccess) throw ApiException(resp.errorMessage(), resp.code)
        return resp
    }
}
