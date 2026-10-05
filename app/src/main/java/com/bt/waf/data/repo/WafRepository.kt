package com.bt.waf.data.repo

import com.bt.waf.data.model.WafResponse
import com.bt.waf.data.net.WafClient
import com.bt.waf.data.store.AuthStore
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 串行化重登：多个页面同时发现 token 过期时，只发一次登录请求。 */
    private val reloginMutex = Mutex()

    /** 串行化 bootstrap：并发调用时只发一次 GET，避免 Cookie 还没种下就被/api 请求抢跑。 */
    private val bootstrapMutex = Mutex()

    /**
     * 这些接口不触发 bootstrap。
     *
     * 前两个由调用方在调它们之前已经**串行 await 过** bootstrap，
     * 再进一次就是自己等自己 —— bootstrapMutex 不可重入，直接死锁。
     * logout 马上要清 Cookie，多发一次 GET 纯属浪费。
     */
    private val SKIP_BOOTSTRAP_PATHS = setOf(
        "user/login", "user/get_validate_code", "user/logout"
    )

    /** SID Cookie 是否已成功种下。@Volatile：bootstrap 在 IO 线程写、业务线程读。 */
    @Volatile
    private var bootstrapped: Boolean = false

    /**
     * 服务端已判定鉴权失效，需要用户重新登录。
     *
     * 刻意**不保存密码做静默重登** —— 明文存密码风险过高。
     * 改为：本地判断 token 过期、或服务端返回鉴权错误时，把 needRelogin 置 true，
     * 界面订阅它弹一次性登录框。用户登录成功后标志清掉，期间所有页面不再重复弹。
     */
    @Volatile
    private var authExpired: Boolean = false

    /** 界面订阅这个 StateFlow：变成 true 就弹重新登录框。 */
    val needRelogin = MutableStateFlow(false)

    fun acknowledgeReloginPrompt() {
        authExpired = false
        needRelogin.value = false
    }

    /** 登录前应用连接信息（网页地址、信任自签）并重建客户端。 */
    fun applyConnection(webUrl: String, trustSelfSigned: Boolean) {
        authStore.webUrl = webUrl
        authStore.trustSelfSigned = trustSelfSigned
        wafClient.reconfigure()
        // 实例地址可能换了，旧实例的 SID Cookie 对新地址无效：
        // 必须显式丢弃旧 Cookie 并让下次请求重新 bootstrap。
        wafClient.clearCookies()
        resetBootstrap()
    }

    /**
     * 执行一次 API 调用并拆包。
     *
     * aawaf 用 HTTP 状态码表达错误（400/401/404），业务错误文案放在响应体 `res` 里。
     * 这里统一把响应体还原成 [WafResponse]，让界面能显示「请填写验证码」这类真实提示，
     * 而不是无信息量的 "HTTP 400"。仅当响应体无法解析时才退回状态码文案。
     */
    private suspend fun raw(path: String, params: Map<String, Any?>): WafResponse {
        // ★ 关键：任何 API 请求发出**之前**都必须先确保 Cookie 已种下。
        //
        // aawaf 的安全入口中间件没有 SID Cookie 一律返回 404，而 Cookie
        // 由bootstrap()（GET 入口页）种下。bootstrap 是一次网络往返（约 100ms），
        // 如果不等它完成就发API，请求会抢在 Cookie 之前到达 —— 实测
        // App 冷启动（进程被系统回收后重启）时，bootstrap 的 GET 在
        // 09:27:39.932 发出，概览的 POST 在 09:27:39.946 就跟上了，
        // **只隔 14ms**，Cookie 尚未种下，于是全部 404。
        //
        // 只在 login() 里 await bootstrap 是不够的：还有冷启动、
        // token 过期重登、进程被系统回收后重建等路径不会经过它。
        //
        // bootstrap() 内部有 Mutex 串行 + bootstrapped 去重，
        // 已种下时这里就是一次 volatile 读，几乎零开销。
        //
        // 例外：bootstrap 自身与 user/login 不套娃，否则自己等自己死锁 ——
        // 由下面这个白名单显式排除。
        //
        // user/login     前面已串行 bootstrap 过，这里再等一次是死锁
        // user/get_validate_code  同上（验证码要靠刚种下的 Cookie 才拿得到）
        // user/logout    马上要登出了，Cookie 状态无关紧要，别多发一次 GET
        if (path !in SKIP_BOOTSTRAP_PATHS) {
            bootstrap()
        }

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
     *
     * ## 为什么必须「串行且去重」
     *
     * aawaf 的安全入口中间件要求请求带上有效 SID Cookie，**没有 Cookie 一律返回 404**
     *（不是 401 —— 权限错误是 404，这样探测者拿不到"存在但无权限"的信号）。
     *
     * 原来的写法有两个问题：
     *
     * 1. **并发竞态**：登录成功后 `onLoggedIn()` 切到主界面，MainScreen 的
     *    `bootstrap()` 与概览页首次 `load()` 是**并行**发起的。bootstrap 还没
     *    种下 Cookie，概览的 7 个接口就全部 404了。表现为「刚登录进去，
     *    概览一片报错，但顶部却显示已连接」。
     * 2. **每次都发**：MainScreen / fetchValidateCode / login 各自调一次，
     *    同一个会话内重复 GET 入口页，纯浪费。
     *
     * 现在用 [Mutex] 串行化+ [bootstrapped] 去重：第一次真正种 Cookie 并等它完成，
     * 后续调用直接返回。配合 login() 里在返回前 await 一次，
     * 保证「登录成功回调」发生时 Cookie 一定已经就绪。
     */
    suspend fun bootstrap() = bootstrapMutex.withLock {
        if (bootstrapped) return@withLock
        val url = authStore.adminFull
        if (url.isBlank()) return@withLock
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).get().build()
                wafClient.client.newCall(req).execute().use { resp ->
                    // 入口页返回 2xx 才算 Cookie 种下成功；否则不置标记，
                    // 下次调用还会重试（比如用户当时网络不通）。
                    if (resp.isSuccessful) bootstrapped = true
                    resp.body?.bytes()
                }
            }
        }
    }

    /** 退出登录时清标记，下次登录要重新种Cookie。 */
    private fun resetBootstrap() {
        bootstrapped = false
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
        // 换实例/换会话，必须重新种 Cookie
        wafClient.clearCookies()
        resetBootstrap()
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
        // aawaf 登录响应带 ttl（秒，默认 7200）。必须一起存：
        // 只存 token 的话前端无法在请求前判断过期，只能等服务端返回
        // "Token is expired" —— 用户看到的是一句英文错误。
        val ttl = (res["ttl"] as? Number)?.toLong()
            ?: res["ttl"]?.toString()?.toDoubleOrNull()?.toLong()
            ?: 7200L
        authStore.saveToken(token, ttl)
        wafClient.reconfigure()
        // 再串行确认一次 Cookie 已就绪才返回。
        //
        // 为什么必要：调用方拿到成功结果后会立刻 onLoggedIn() 切主界面，
        // 概览页随即并发发起 7 个 /api 请求。而 aawaf 的安全入口中间件
        // **没有 SID Cookie 一律 404** —— 如果此刻 Cookie 还没种下，
        // 就会看到「顶部显示已连接、但整页数据全报错」的诡异状态。
        //
        // bootstrap() 内部已用 Mutex 串行化 + bootstrapped 去重，
        // 这里再调一次：如果前面已经种成功就是空操作，否则真正补种。
        bootstrap()
        return Result.success(Unit)
    }

    suspend fun logout() {
        runCatching { raw("user/logout", emptyMap()) }
        authStore.clearSession()
        wafClient.reconfigure()
        // 服务端会话已注销，下次登录必须重新走 bootstrap 种 Cookie
        wafClient.clearCookies()
        resetBootstrap()
    }

    /** 通用调用：返回原始响应（含 code）。 */
    suspend fun call(path: String, params: Map<String, Any?> = emptyMap()): WafResponse =
        raw(path, params)

    /**
     * 是否是 token 过期 / 鉴权失败类错误。
     *
     * 这类错误可以靠重新登录恢复，与「站点不存在」「参数错误」有本质区别，
     * 必须区分开：只有前者才值得自动重登一次。
     */
    private fun isAuthError(r: WafResponse): Boolean {
        if (r.code == 0) return false
        val text = (r.res as? String)?.lowercase() ?: return false
        return text.contains("token") || text.contains("unauthorized") ||
            text.contains("forbidden") || text.contains("not logged") ||
            text.contains("login") || text.contains("会话") || text.contains("登录")
    }

    /**
     * 标记 token 已失效，需要用户重新登录。
     *
     * aawaf 的 JWT ttl 默认 7200 秒（2 小时）。此前 App 只存 token 不存过期时间，
     * 过期后服务端对所有 /api 请求返回 "Token is expired"，用户只能在
     * 每个页面各点一次「重试」，而且重试也必然失败 —— 必须重新走登录流程。
     *
     * 两处触发：
     *  1. 请求前本地判断（[AuthStore.isTokenExpired]）——提前 60 秒就知道，
     *     这时候登录体验最顺（用户在操作别的页面，不会有明显中断）；
     *  2. 服务端提前判定失效（比如服务端重启改了密钥），收到鉴权错误后触发。
     *
     * 用 [reloginMutex] 串行化：多个页面同时发现过期时只置一次标志，
     * 界面也只弹一次登录框。
     *
     * @return true 表示已经置了标志（本轮应当中止请求并提示登录）
     */
    private suspend fun tryRelogin(): Boolean = reloginMutex.withLock {
        if (authExpired) return@withLock true
        authExpired = true
        needRelogin.value = true
        true
    }

    /** 通用调用：失败抛 [ApiException]，成功返回响应。内部含 token 过期处理。 */
    suspend fun safeCall(path: String, params: Map<String, Any?> = emptyMap()): WafResponse {
        // 请求前本地判断：提前 60 秒就知道 token 要过期，
        // 此时置标志让界面弹登录框，用户不用等请求失败才发现。
        if (authStore.isTokenExpired() && tryRelogin()) {
            throw ApiException("登录已过期，请重新登录", 401)
        }

        val resp = raw(path, params)
        if (resp.isSuccess) return resp

        // 服务端判定鉴权失效（含ttl 比本地记的更短的情况）
        if (isAuthError(resp) && tryRelogin()) {
            throw ApiException("登录已过期，请重新登录", 401)
        }

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
