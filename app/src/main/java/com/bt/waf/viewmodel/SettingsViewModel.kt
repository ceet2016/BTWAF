package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asBool
import com.bt.waf.util.asMap
import kotlinx.coroutines.launch

/**
 * 系统设置。
 *
 * config/get_config 返回结构为 { config:{...}, basic_auth:{...}, apiinfo:{...}, port, syslog, ... }，
 * 因此本 ViewModel 拆分为三段展示；写参数名严格对齐源码 modules/config.go：
 *  - set_title        -> { title, logo }
 *  - set_domain       -> { accept_domain }
 *  - set_timeout      -> { session_timeout }
 *  - set_language     -> { lan }
 *  - set_ip           -> { accept_ip }  （逗号分隔字符串，非数组）
 *  - set_pwd          -> { password_complexity } （0/1，密码复杂度开关）
 *  - set_open_api     -> { type, limit_addr }  （type:1重置Token 2开关 3限制地址；limit_addr 以 \n 分隔）
 * 改用户密码走 user/update_profile -> { password }。
 */
class SettingsViewModel(private val repo: WafRepository) : ViewModel() {

    /** config/get_config 的原始返回 */
    var raw by mutableStateOf<Map<String, Any?>>(emptyMap())
    /** 其中的 config 节点 */
    var config by mutableStateOf<Map<String, Any?>>(emptyMap())
    var basicAuth by mutableStateOf<Map<String, Any?>>(emptyMap())
    var apiInfo by mutableStateOf<Map<String, Any?>>(emptyMap())
    /**
     * syslog 节点，结构是 `{ open, host, port }`（public.Syslog），
     * **不是布尔**。文件不存在时后端返回零值对象而不是报错，所以这里永远是对象。
     */
    var syslog by mutableStateOf<Map<String, Any?>>(emptyMap())
    /** two_step_auth：布尔，get_config 已直接下发，不必再调 get_two_auth。 */
    var twoStepAuth by mutableStateOf(false)
    var port by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                raw = repo.safeCall("config/get_config", emptyMap()).asMap()
                config = raw["config"].asMap()
                basicAuth = raw["basic_auth"].asMap()
                apiInfo = raw["apiinfo"].asMap()
                syslog = raw["syslog"].asMap()
                twoStepAuth = raw["two_step_auth"] == true ||
                    raw["two_step_auth"].asBool()
                port = raw["port"]?.toString() ?: ""
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun reload() = load()

    private fun set(path: String, params: Map<String, Any?>, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            message = null
            try {
                val r = repo.safeCall(path, params)
                message = if (r.isSuccess) "操作成功" else r.errorMessage()
                onResult(if (r.isSuccess) null else r.errorMessage())
                load()
            } catch (e: Exception) {
                message = e.message
                onResult(e.message)
            }
        }
    }

    /** 修改账号（用户名/密码）。密码需满足复杂度要求，否则服务端会报错。 */
    fun updatePassword(newPwd: String, onResult: (String?) -> Unit) =
        set("user/update_profile", mapOf("password" to newPwd), onResult)

    fun updateUsername(name: String, onResult: (String?) -> Unit) =
        set("user/update_profile", mapOf("username" to name), onResult)

    fun setPasswordComplexity(on: Boolean, onResult: (String?) -> Unit) =
        set("config/set_pwd", mapOf("password_complexity" to if (on) 1 else 0), onResult)

    /**
     * set_title：改后台企业名。
     *
     * 坑：源码要求 `logo` 键**必须存在**（只判键在不在，不判空串），
     * 而 logo 并不在 get_config 的返回里。所以这里用原配置里的值兜底，
     * 拿不到就传空串（空串会被原样写进 logo 文件，等于清空 logo）。
     */
    fun setTitle(title: String, onResult: (String?) -> Unit) =
        set(
            "config/set_title",
            mapOf("title" to title, "logo" to (config["logo"]?.toString() ?: "")),
            onResult
        )

    fun setDomain(domain: String, onResult: (String?) -> Unit) =
        set("config/set_domain", mapOf("accept_domain" to domain), onResult)

    fun setAcceptIp(ips: List<String>, onResult: (String?) -> Unit) =
        set("config/set_ip", mapOf("accept_ip" to ips.joinToString(",")), onResult)

    fun setLanguage(lang: String, onResult: (String?) -> Unit) =
        set("config/set_language", mapOf("lan" to lang), onResult)

    fun setSessionTimeout(seconds: String, onResult: (String?) -> Unit) =
        set("config/set_timeout", mapOf("session_timeout" to seconds.toIntOrNull()), onResult)

    fun setHelp(open: Boolean, onResult: (String?) -> Unit) =
        set("config/set_help", mapOf("open" to if (open) 1 else 0), onResult)

    /** type: 1 重置 Token，2 开关 API，3 设置限制地址（limit_addr 以 \n 分隔） */
    fun setOpenApi(type: Int, limitAddr: String = "", onResult: (String?) -> Unit) =
        set("config/set_open_api", mapOf("type" to type, "limit_addr" to limitAddr), onResult)

    fun setAdminPath(path: String, onResult: (String?) -> Unit) =
        set("config/set_admin_path", mapOf("admin_path" to path.trimStart('/')), onResult)

    fun setPort(p: String, onResult: (String?) -> Unit) =
        set("config/set_port", mapOf("port" to p.toIntOrNull()), onResult)

    fun setBasicAuth(open: Boolean, user: String, pwd: String, onResult: (String?) -> Unit) =
        set(
            "config/set_basic_auth",
            mapOf("open" to if (open) 1 else 0, "basic_user" to user, "basic_pwd" to pwd),
            onResult
        )

    // ──────────────────────────────────────────────────────────────────
    // 以下为 config 模块剩余接口（源码 modules/config.go 逐个核对）
    // ──────────────────────────────────────────────────────────────────

    /** get_cert：读取控制台自签证书 PEM。返回 { cert_pem, key_pem }。 */
    var certPem by mutableStateOf("")
    var keyPem by mutableStateOf("")
    var certLoading by mutableStateOf(false)
    var certError by mutableStateOf<String?>(null)

    /**
     * 读取证书。
     *
     * 坑：文件读不出来时后端直接 Fail（不是返回空串），所以空��是失败而非「证书为空」。
     * 私钥是明文返回的，界面只做只读展示，绝不回显到日志或剪贴板。
     */
    fun loadCert(onResult: (String?, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            certLoading = true
            certError = null
            try {
                val m = repo.safeCall("config/get_cert", emptyMap()).asMap()
                certPem = m["cert_pem"]?.toString() ?: ""
                keyPem = m["key_pem"]?.toString() ?: ""
                onResult(certPem, keyPem)
            } catch (e: Exception) {
                certError = e.message
                onResult("", e.message)
            } finally {
                certLoading = false
            }
        }
    }

    /**
     * set_cert：替换控制台自签证书。
     *
     * 两个键都是**必填**（源码只判键存在、不判空串），所以空内容也会被写进文件，
     * 导致控制台 HTTPS 立刻不可用。界面必须先本地拦一道空值。
     *
     * 副作用：后端会 kill -9 再重启 CloudWaf 进程，调用期间控制台短暂无响应。
     */
    fun setCert(certContent: String, keyContent: String, onResult: (String?) -> Unit) {
        if (certContent.isBlank()) {
            onResult("请输入证书内容")
            return
        }
        if (keyContent.isBlank()) {
            onResult("请输入证书私钥")
            return
        }
        set(
            "config/set_cert",
            mapOf("certContent" to certContent, "keyContent" to keyContent),
            onResult
        )
    }

    /**
     * new_generate_certificate：向宝塔官方在线申请一张新的自签证书。
     *
     * 无参数。服务端会访问 api.bt.cn，失败文案是「在线申请自签证书失败」。
     * 成功后同样会重启控制台进程，且会覆盖现有证书与 pfx。
     */
    fun regenerateCertificate(onResult: (String?) -> Unit) =
        set("config/new_generate_certificate", emptyMap(), onResult)

    /** set_ntp：开关时间同步。open 只接受 0/1，其他值报「参数不合法!」。 */
    fun setNtp(open: Boolean, onResult: (String?) -> Unit) =
        set("config/set_ntp", mapOf("open" to if (open) 1 else 0), onResult)

    /**
     * sync_date：立刻与时间服务器同步。
     *
     * 无参数。服务端会执行 `date -s` 改系统时间 —— 改错会影响证书校验与日志时间，
     * 界面必须显式二次确认。
     */
    fun syncDate(onResult: (String?) -> Unit) =
        set("config/sync_date", emptyMap(), onResult)

    /**
     * set_pwd_expire：密码过期天数。
     *
     * 传 0 的语义要注意：源码只判 <0 非法，0 会被原样写进配置，
     * 具体是否等于「永不过期」由读取侧决定，界面不擅自解释。
     */
    fun setPasswordExpire(days: Int, onResult: (String?) -> Unit) {
        if (days < 0) {
            onResult("密码过期时间不合法!")
            return
        }
        set("config/set_pwd_expire", mapOf("password_expire" to days), onResult)
    }

    /**
     * set_warning_open：全局告警开关。warning_open 只接受 0/1。
     */
    fun setWarningOpen(open: Boolean, onResult: (String?) -> Unit) =
        set("config/set_warning_open", mapOf("warning_open" to if (open) 1 else 0), onResult)

    /**
     * set_intercept_page：自定义拦截页说明文案。
     *
     * 坑一：源码判 `au.Auth.Extra.Type == 0` 时直接 Success 返回
     * 「免费版不支持修改拦截页」—— **code 是 0**，属于假成功，界面必须原样提示。
     * 坑二：type 传 "logo" 时会把企业 logo 内嵌进拦截页 HTML。
     * 坑三：data 是 HTML 片段，会被塞进 black.html 的 desc 节点，不是纯文本。
     */
    fun setInterceptPage(content: String, withLogo: Boolean, onResult: (String?) -> Unit) =
        set(
            "config/set_intercept_page",
            mapOf(
                "data" to content,
                "type" to if (withLogo) "logo" else ""
            ),
            onResult
        )

    /**
     * set_syslog：配置 syslog 转发。
     *
     * 源码用 struct 绑定，host 为空报「Syslog服务器地址不能为空」，
     * 端口必须落在 1-65535。open 是 bool，传 0/1 也能解出来。
     */
    fun setSyslog(open: Boolean, host: String, port: Int, onResult: (String?) -> Unit) {
        if (host.isBlank()) {
            onResult("Syslog服务器地址不能为空")
            return
        }
        if (port !in 1..65535) {
            onResult("Syslog服务器端口范围为1-65535")
            return
        }
        set(
            "config/set_syslog",
            mapOf("open" to open, "host" to host.trim(), "port" to port),
            onResult
        )
    }

    /**
     * get_two_auth：读取动态口令详情。
     *
     * get_config 只给一个 two_step_auth 布尔，用户名要单独调这个接口才能拿到。
     * 返回 { open, username, secret_key }。
     */
    var twoAuth by mutableStateOf<Map<String, Any?>>(emptyMap())

    fun loadTwoAuth() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                twoAuth = repo.safeCall("config/get_two_auth", emptyMap()).asMap()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /**
     * set_two_auth：开关动态口令（Google Authenticator 一类）。
     *
     * 坑：open=1 时服务端会**自动生成 8 位随机用户名**并覆盖旧的，
     * secret_key 由服务端按用户名派生，客户端传什么都不影响结果。
     * 关掉时（open=0）secret_key 传空串即可。
     * 二维码是服务端按 secret 生成的 otpauth 链接，客户端只能展示文本。
     */
    fun setTwoAuth(open: Boolean, onResult: (String?) -> Unit) =
        set(
            "config/set_two_auth",
            mapOf("open" to if (open) 1 else 0, "secret_key" to ""),
            onResult
        )

    /**
     * check_two_auth：校验动态口令。
     *
     * 未开启时返回「动态口令认证未开启」而不是认证失败，所以这个接口不能
     * 用来判断是否已启用。未开启时用 [loadTwoAuth] 读 open 字段。
     */
    fun checkTwoAuth(passcode: String, onResult: (Boolean, String?) -> Unit) {
        if (passcode.isBlank()) {
            onResult(false, "请输入动态口令")
            return
        }
        viewModelScope.launch {
            try {
                repo.safeCall("config/check_two_auth", mapOf("passcode" to passcode.trim()))
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message)
            }
        }
    }

    /**
     * title：读取企业名与 logo。
     *
     * 坑：logo 走 base64 时可能非常大（源码自己按 100KB / 0.7 做过一次判断，
     * 那是 set_title 的校验；title 这条只是原样返回），界面只显示长度不显示内容。
     */
    var brandTitle by mutableStateOf("")
    var brandLogo by mutableStateOf("")

    fun loadBrand() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("config/title", emptyMap())
                val m = r.asMap()
                brandTitle = m["title"]?.toString() ?: ""
                brandLogo = m["logo"]?.toString() ?: ""
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
}
