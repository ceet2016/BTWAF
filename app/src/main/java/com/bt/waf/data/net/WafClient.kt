package com.bt.waf.data.net

import com.bt.waf.BuildConfig
import com.bt.waf.data.store.AuthStore
import com.google.gson.GsonBuilder
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 进程内 CookieJar：持久化 SID 会话 Cookie（后台安全入口中间件要求携带）。
 */
class InMemoryCookieJar : CookieJar {
    private val cache = ConcurrentHashMap<String, List<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) cache[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cache[url.host] ?: emptyList()

    /** 清空全部 Cookie（换实例 / 退出登录）。 */
    fun clear() = cache.clear()
}

/** 在请求头注入 JWT：Authorization: bt<token>（仅对 /api/ 路径）。 */
class AuthInterceptor(private val tokenProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val token = tokenProvider()
        return if (token.isNotBlank() && request.url.encodedPath.startsWith("/api/")) {
            chain.proceed(request.newBuilder().header("Authorization", "bt$token").build())
        } else {
            chain.proceed(request)
        }
    }
}

/** 开发期信任所有证书（应对自签 HTTPS）。生产环境请勿开启。 */
private fun OkHttpClient.Builder.trustAllCertificates(): OkHttpClient.Builder {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
    sslSocketFactory(sslContext.socketFactory, trustManager)
    hostnameVerifier { _, _ -> true }
    return this
}

/**
 * 封装 OkHttp + Retrofit，根据 AuthStore 实时派生 BaseUrl 与 Token。
 * 修改连接信息后调用 [reconfigure] 重建。
 */
class WafClient(private val authStore: AuthStore) {

    /**
     * 进程级共享的 CookieJar。
     *
     * ⚠️ 这个实例**必须跨 reconfigure() 复用**，不能每次 buildClient()
     * 新建一个 `InMemoryCookieJar()`。
     *
     * 为什么：aawaf 的安全入口中间件要求所有 `/api/` 开头的请求带上有效
     * SID Cookie，**没有 Cookie 一律返回 404**（权限错误故意用 404
     * 而不是 401，避免探测者区分「不存在」与「无权限」）。
     * 而 Cookie 就存在这个 jar 里。
     *
     * 原来的写法 `builder.cookieJar(InMemoryCookieJar())` 每次重建客户端
     * 都造一个空 jar，把刚种下的 SID Cookie 全部丢掉。触发路径：
     * `login()` → `saveToken()` → `reconfigure()` → **Cookie 清空** → 此后
     * 所有 API 请求 404。表现为「刚登录就整页报错」，且重试永远好不了，
     * 因为每次重试又会再 reconfigure 一次。
     *
     * 实例地址变更（applyConnection）时需要丢弃旧 Cookie，由调用方
     * 显式调[clearCookies]；其余情况一律保留。
     */
    private val cookieJar = InMemoryCookieJar()

    var client: OkHttpClient = buildClient()
        private set
    var api: WafApi = buildApi(client)
        private set

    /** 重建客户端以套用新的 BaseUrl / 证书配置。**保留 CookieJar**。 */
    fun reconfigure() {
        client = buildClient()
        api = buildApi(client)
    }

    /** 换实例或退出登录时调用：丢弃已持有的 Cookie，强制重新 bootstrap。 */
    fun clearCookies() = cookieJar.clear()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(AuthInterceptor { authStore.token })
        // ⚠️ 日志拦截器只在 debug 包里装。
        //
        // 就算降到 BASIC，OkHttp 打的仍然是**完整请求行**，里面含主机名+端口：
        //   --> POST https://1.2.3.4:8379/api/user/login
        // release 包用户只要抓一次 logcat（客服排障、系统日志上报、
        // 第三方工具自动收集）就等于把部署地址交出去了，而 WAF 实例
        // 的 IP + 端口本身就是敏感信息。release 里完全不要装它。
        //
        // 排查线上问题时也不要临时改成 HEADERS —— 那个级别会把 Cookie 与
        // Authorization 完整打进日志，等于把会话凭据写进设备日志。
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
        }
        if (authStore.trustSelfSigned) builder.trustAllCertificates()
        return builder.build()
    }

    private fun buildApi(client: OkHttpClient): WafApi {
        val gson = GsonBuilder().setLenient().create()
        return Retrofit.Builder()
            .baseUrl(authStore.apiBase)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(WafApi::class.java)
    }
}
