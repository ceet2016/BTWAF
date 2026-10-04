package com.bt.waf.data.net

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

    var client: OkHttpClient = buildClient()
        private set
    var api: WafApi = buildApi(client)
        private set

    fun reconfigure() {
        client = buildClient()
        api = buildApi(client)
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .addInterceptor(AuthInterceptor { authStore.token })
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
            )
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
