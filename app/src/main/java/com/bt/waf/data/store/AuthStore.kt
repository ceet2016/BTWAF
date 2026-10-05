package com.bt.waf.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.net.toUri
import java.io.File

/**
 * 持久化 WAF 连接信息（SharedPreferences，简单同步读写）。
 * 仅保存：网页版完整地址 webUrl、账号、信任自签证书开关、JWT token。
 * 由 webUrl 派生：origin / apiBase / adminFull（后台入口路径）。
 */
class AuthStore(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("waf_auth", Context.MODE_PRIVATE)

    var webUrl: String
        get() = prefs.getString(KEY_WEB_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEB_URL, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /**
     * token 的绝对过期时刻（毫秒）。
     *
     * aawaf 登录接口会返回 `ttl`（秒，默认 7200），但早期版本登录时只存了
     * token 字符串，把 ttl 丢了 —— 结果前端完全不知道 token 什么时候过期，
     * 过期后只能等服务端返回 "Token is expired" 才反应过来，界面上直接甩一句
     * 英文给用户。这里把「收到 token 的时刻 + ttl」一起落盘，
     * 配合 [isTokenExpired] / [tokenRemainingMinutes] 做本地判断。
     */
    var tokenExpireAt: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRE, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRE, value).apply()

    /** 登录成功后调用：同时保存 token 与它的过期时刻。 */
    fun saveToken(token: String, ttlSeconds: Long) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_EXPIRE, now + ttlSeconds * 1000L)
            .apply()
    }

    /** 提前 60 秒就算过期，避免边界上发出一个必然失败的请求。 */
    fun isTokenExpired(): Boolean {
        val at = tokenExpireAt
        if (at <= 0L) return false           // 老版本存的数据没有过期时间，不本地拦截
        return System.currentTimeMillis() >= at - 60_000L
    }

    /** 剩余有效分钟数，未知时返回 -1。 */
    fun tokenRemainingMinutes(): Int {
        val at = tokenExpireAt
        if (at <= 0L) return -1
        val left = at - System.currentTimeMillis()
        return if (left <= 0) 0 else (left / 60_000L).toInt()
    }

    var trustSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_TRUST, false)
        set(value) = prefs.edit().putBoolean(KEY_TRUST, value).apply()

    val hasSession: Boolean get() = token.isNotBlank() && webUrl.isNotBlank()

    /**
     * 脱敏后的实例标识，用于顶部栏副标题。
     *
     * 只保留**域名**（IP 也算），把端口、后台入口路径、参数全部去掉：
     *  - `https://1.2.3.4:8379/xxxxxxxx` → `1.2.3.4`
     *  - `https://waf.example.com/abc123` → `waf.example.com`
     *
     * 注释里的示例一律用保留地址（RFC 5737 的 1.2.3.4 / RFC 2606 的 example.com），
     * 不要写进真实部署的 IP 与入口路径 —— 源码是公开的，注释也一样。
     *
     * 为什么不直接显示完整地址：顶部栏是每页公共框架，等于每张截图都带着它。
     * 完整地址里含内网 IP + 端口 + 后台入口路径（那串十六进制相当于半个凭据），
     * 截图一转发就等于泄露。完整地址仍可在「系统 → 账号信息」主动查看。
     */
    val hostLabel: String
        get() {
            val uri = webUrl.trim().toUri()
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: return "未配置"
            // IPv6 字面量去掉方括号
            return host.removePrefix("[").removeSuffix("]")
        }

    fun clearSession() = prefs.edit().remove(KEY_TOKEN).remove(KEY_TOKEN_EXPIRE).apply()

    fun clearAll() = prefs.edit().clear().apply()

    data class Parsed(val origin: String, val apiBase: String, val adminFull: String)

    /** 由网页版地址解析出 API 基址与后台入口路径。 */
    fun parsed(): Parsed {
        val raw = webUrl.trim().removeSuffix("/")
        if (raw.isEmpty()) return Parsed("http://127.0.0.1", "http://127.0.0.1/api/", "http://127.0.0.1")
        val uri = raw.toUri()
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: "127.0.0.1"
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val origin = "$scheme://$host$portPart"
        val adminPath = uri.path?.takeIf { it.isNotEmpty() } ?: ""
        return Parsed(
            origin = origin,
            apiBase = "$origin/api/",
            adminFull = "$origin$adminPath"
        )
    }

    val apiBase: String get() = parsed().apiBase
    val adminFull: String get() = parsed().adminFull

    /**
     * 文件下载/上传的落地目录。
     *
     * 刻意不用公共目录（Environment.DIRECTORY_DOWNLOADS）：那需要 WRITE_EXTERNAL_STORAGE，
     * 且 API 29+ 分区存储下即使申请也会被拒。cacheDir 无需任何权限，
     * 导出后由界面用 FileProvider/分享 Intent 交给用户即可。
     */
    val transferDir: File get() = File(appContext.cacheDir, "waf_transfer").apply { mkdirs() }

    companion object {
        private const val KEY_WEB_URL = "web_url"
        private const val KEY_USER = "username"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOKEN_EXPIRE = "token_expire_at"
        private const val KEY_TRUST = "trust_self_signed"
    }
}
