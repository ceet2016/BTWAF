package com.bt.waf.data.repo

import com.bt.waf.data.model.WafResponse
import com.bt.waf.data.net.WafClient
import com.bt.waf.data.store.AuthStore
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.net.URLDecoder

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
    private val gson = GsonBuilder().setLenient().create()

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

    // ---------------------------------------------------------------------
    // 文件传输（multipart 上传 / 二进制下载）
    //
    // customize/export 返回 application/octet-stream，
    // customize/import 接收 multipart/form-data，
    // 二者都走不通上面的 JSON 信封（raw/call/safeCall），因此单独走裸 OkHttp。
    //
    // 关键点：必须复用 wafClient.client —— 里面的 AuthInterceptor 负责注入
    // Authorization: bt<token>，InMemoryCookieJar 负责带 SID 会话 Cookie。
    // 自建 OkHttpClient 会同时丢掉这两样，后端直接401 或被安全入口拦成 404。
    // ---------------------------------------------------------------------

    /** 拼接完整的 API 地址（apiBase 已含结尾斜杠，path 为 module/action）。 */
    private fun apiUrl(path: String): String = authStore.apiBase + path.trimStart('/')

    /**
     * 上传本地文件（multipart/form-data）。
     *
     * @param path module/action，例如 "customize/import"
     * @param fieldName 表单字段名。customize/import 必须是 `customize_rule`（源码 FormFile 硬编码）
     * @param mimeType 随文件一起发的 Content-Type，aawaf 只看文件名的 .json 后缀，不看这个
     * @param fileName 覆盖文件名。为 null 时用 [file] 的原名（**后端会校验 .json 后缀**）
     */
    suspend fun uploadFile(
        path: String,
        fieldName: String,
        file: File,
        mimeType: String = "application/octet-stream",
        fileName: String? = null
    ): WafResponse = withContext(Dispatchers.IO) {
        if (!file.exists()) throw ApiException("文件不存在：${file.name}")
        if (file.length() > MAX_UPLOAD_BYTES) {
            throw ApiException("文件超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB 上限")
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fieldName,
                fileName ?: file.name,
                file.asRequestBody(mimeType.toMediaTypeOrNull())
            )
            .build()
        val req = Request.Builder().url(apiUrl(path)).post(body).build()
        // 后端用 HTTP 400 携带业务错误（如「不能导入空文件」），同样要把 res 文案取出来
        parseEnvelope(executeRaw(req))
    }

    /**
     * 下载二进制文件到本地，返回落盘结果。
     *
     * 用 GET 直连（后端 export 是无参 GET，见 modules/customize.go Export）。
     * 注意后端可能返回 JSON 错误信封（鉴权失败 401 / 限流 429 等）而不是文件，
     * 这里按 Content-Type 判定并转成 [ApiException]，避免把错误 JSON 存成 .json 文件。
     *
     * @param suggestedName 服务端未给 Content-Disposition 时使用的文件名
     */
    suspend fun downloadFile(
        path: String,
        suggestedName: String,
        extension: String = ".json"
    ): DownloadedFile = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiUrl(path)).get().build()
        val resp = executeRaw(req)
        try {
            val ct = resp.header("Content-Type").orEmpty().lowercase()
            if (!resp.isSuccessful) {
                throw ApiException(errorFromBody(resp, resp.code), resp.code)
            }
            if (ct.contains("json")) {
                // 200 但返回 JSON：一定是业务错误被包进了信封
                throw ApiException(errorFromBody(resp, resp.code), resp.code)
            }
            val bytes = resp.body?.bytes()
                ?: throw ApiException("服务端未返回文件内容")
            if (bytes.isEmpty()) throw ApiException("服务端返回了空文件")

            val name = resolveFileName(resp.header("Content-Disposition"), suggestedName, extension)
            val dir = authStore.transferDir
            val target = File(dir, name)
            target.writeBytes(bytes)
            DownloadedFile(target, name, bytes.size.toLong())
        } finally {
            resp.close()
        }
    }

    /** 执行裸请求（不走 Retrofit），调用方负责关闭。 */
    private fun executeRaw(req: Request): okhttp3.Response =
        wafClient.client.newCall(req).execute()

    /**
     * 把裸响应体按 WafResponse 信封解析。
     * multipart 上传的响应仍是标准 JSON 信封（成功时 res 为「导入成功」字符串），
     * 必须还原出 code，否则成功响应会被误判成失败。
     */
    private fun parseEnvelope(resp: okhttp3.Response): WafResponse {
        resp.use {
            val text = runCatching { it.body?.string() }.getOrNull().orEmpty()
            if (text.isBlank()) return WafResponse(code = it.code, res = "请求失败 (HTTP ${it.code})")
            val parsed = runCatching { gson.fromJson(text, WafResponse::class.java) }.getOrNull()
            // WafResponse 的 code 默认值是 -1，与失败码重合；只有 res 也缺失时才判为解析失败
            if (parsed != null && parsed.res != null) return parsed
            return WafResponse(code = it.code, res = extractMessage(text))
        }
    }

    /** 从错误响应体取 `res` 文案，取不到就用 HTTP 状态码兜底。 */
    private fun errorFromBody(resp: okhttp3.Response, code: Int): String {
        val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        if (text.isBlank()) return "请求失败 (HTTP $code)"
        return extractMessage(text)
    }

    /**
     * 解析 Content-Disposition 里的文件名。
     * aawaf 用的是 `attachment;filename=<url.QueryEscape(name)>`（core/response.go），
     * 中文名会被百分号编码，所以必须 URLDecode；同时兼容 RFC 5987 的 filename* 写法。
     */
    private fun resolveFileName(disposition: String?, suggestedName: String, extension: String): String {
        var raw: String? = null
        if (!disposition.isNullOrBlank()) {
            // filename* 优先（带 charset'' 前缀）
            val star = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)
            if (!star.isNullOrBlank()) {
                raw = runCatching { URLDecoder.decode(star.trim(), "UTF-8") }.getOrNull()
            }
            if (raw.isNullOrBlank()) {
                raw = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                    .find(disposition)?.groupValues?.get(1)
                if (!raw.isNullOrBlank()) {
                    raw = runCatching { URLDecoder.decode(raw!!.trim(), "UTF-8") }.getOrNull()
                }
            }
        }
        val picked = raw?.takeIf { it.isNotBlank() } ?: suggestedName
        return sanitizeFileName(picked, extension)
    }

    /**
     * 清洗服务端下发的文件名：只保留基本名，去掉路径分隔符与控制字符。
     * 服务端文件名来自固定模板，风险低，但落盘路径由远端输入拼成，必须做一次防御。
     */
    private fun sanitizeFileName(name: String, extension: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f\\x7f]"), "")
            .replace(Regex("""[<>:"/\\|?*]"""), "_")
            .trim()
            .ifBlank { "waf_export$extension" }
        return if (base.endsWith(extension, ignoreCase = true)) base else base + extension
    }

    /** 下载结果：本地文件 + 服务端文件名 + 字节数。 */
    data class DownloadedFile(val file: File, val fileName: String, val size: Long)

    companion object {
        /**
         * 上传体积上限 50MB。
         * 源码用的是 `request.ParseMultipartForm(50 << 20)` 作为 maxMemory，
         * 超过部分落临时文件而非直接拒绝，所以这只是一个客户端侧的合理护栏。
         */
        const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024
    }
}
