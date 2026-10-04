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

    var trustSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_TRUST, false)
        set(value) = prefs.edit().putBoolean(KEY_TRUST, value).apply()

    val hasSession: Boolean get() = token.isNotBlank() && webUrl.isNotBlank()

    fun clearSession() = prefs.edit().remove(KEY_TOKEN).apply()

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
        private const val KEY_TRUST = "trust_self_signed"
    }
}
