package com.bt.waf.data.repo

import com.bt.waf.data.model.WafResponse
import com.bt.waf.data.net.WafClient
import com.bt.waf.data.store.AuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import retrofit2.Response

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

    /**
     * 执行一次 API 调用并拆包。
     *
     * aawaf 用 HTTP 状态码表达错误（400/401/404），业务错误文案放在响应体 `res` 里。
     * 这里统一把响应体还原成 [WafResponse]，让界面能显示「请填写验证码」这类真实提示，
     * 而不是无信息量的 "HTTP 400"。仅当响应体无法解析时才退回状态码文案。
     */
    private suspend fun raw(path: String, params: Map<String, Any?>): WafResponse {
        val resp: Response<WafResponse> = wafClient.api.post(path, params)
        val body = resp.body()
        if (body != null) return body
        // 非 2xx 且响应体不是 WafResponse 结构时，尝试按同构 JSON 再解析一次
        val text = runCatching { resp.errorBody()?.string() }.getOrNull()
        val parsed = text?.takeIf { it.isNotBlank() }?.let {
            runCatching { WafResponse(code = resp.code(), res = extractMessage(it)) }.getOrNull()
        }
        return parsed ?: WafResponse(code = resp.code(), res = "请求失败 (HTTP ${resp.code()})")
    }

    /** 从错误响应体里取出 `res` 字段文案；不是 JSON 就原样返回。 */
    private fun extractMessage(text: String): String {
        val m = Regex("\"res\"\\s*:\\s*\"(.*?)\"").find(text)
        if (m != null) return m.groupValues[1]
        return text.take(120)
    }

    /**
     * GET 后台入口页面，植入 SID Cookie（安全入口中间件要求）。
     * 必须在 IO 线程执行：OkHttp 的同步 execute() 在主线程会抛
     * NetworkOnMainThreadException，导致 Cookie 没种下、后续 /api 请求被安全入口拦成 404。
     */
    suspend fun bootstrap() {
        val url = authStore.adminFull
        if (url.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).get().build()
                wafClient.client.newCall(req).execute().use { it.body?.bytes() }
            }
        }
    }

    /** 取图形验证码。返回 (validate_code_id, 图片 base64)。 */
    suspend fun fetchValidateCode(): Pair<String, String>? {
        bootstrap()
        val resp = raw("user/get_validate_code", emptyMap())
        if (!resp.isSuccess) return null
        val res = resp.asMap()
        val id = res["validate_code_id"]?.toString().orEmpty()
        val img = res["validate_code_base64"]?.toString().orEmpty()
        return if (id.isNotBlank() && img.isNotBlank()) id to img else null
    }

    /**
     * 账号密码登录。成功写入 token 并重建客户端（拦截器实时读取 token）。
     * 服务端在同一 IP 登录失败后会强制要求图形验证码（[validateCodeId] / [validateCode]），
     * 缺任一参数都会返回 400，因此失败重试时必须带上验证码。
     */
    suspend fun login(
        username: String,
        password: String,
        validateCodeId: String? = null,
        validateCode: String? = null
    ): Result<Unit> {
        bootstrap()
        val payload = mutableMapOf<String, Any?>("username" to username, "password" to password)
        if (!validateCodeId.isNullOrBlank() && !validateCode.isNullOrBlank()) {
            payload["validate_code_id"] = validateCodeId
            payload["validate_code"] = validateCode
        }
        val resp = raw("user/login", payload)
        if (!resp.isSuccess) return Result.failure(Exception(resp.errorMessage()))
        val res = resp.asMap()
        if (res["two_auth"] == true) return Result.failure(Exception("该实例已开启两步验证，请先在网页端登录"))
        val token = res["token"] as? String
        if (token.isNullOrBlank()) return Result.failure(Exception("登录成功但未返回 token"))
        authStore.token = token
        wafClient.reconfigure()
        return Result.success(Unit)
    }

    suspend fun logout() {
        runCatching { raw("user/logout", emptyMap()) }
        authStore.clearSession()
        wafClient.reconfigure()
    }

    /** 通用调用：返回原始响应（含 code）。 */
    suspend fun call(path: String, params: Map<String, Any?> = emptyMap()): WafResponse =
        raw(path, params)

    /** 通用调用：失败抛 [ApiException]，成功返回响应。 */
    suspend fun safeCall(path: String, params: Map<String, Any?> = emptyMap()): WafResponse {
        val resp = raw(path, params)
        if (!resp.isSuccess) throw ApiException(resp.errorMessage(), resp.code)
        return resp
    }
}
