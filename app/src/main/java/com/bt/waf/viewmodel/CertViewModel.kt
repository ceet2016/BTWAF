package com.bt.waf.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bt.waf.data.repo.WafRepository
import com.bt.waf.util.asList
import com.bt.waf.util.asMap
import kotlinx.coroutines.launch

/**
 * SSL 证书管理（对照 aawaf）：
 * - wafmastersite/get_all_ssl   返回 types.SslInfo[]（ssl_name / brand / Domains / not_after）
 * - wafmastersite/delete_ssl    删除证书
 * - wafmastersite/deploy_ssl    部署到站点
 * - wafmastersite/download_ssl  导出证书
 * - acmesh/apply_cert           申请 Let's Encrypt 证书（domain[] + site_id）
 * - acmesh/renew_cert           续签（domain[] + types + site_id）
 * - acmesh/renewal_cert         按 site_id 续签
 * - acmesh/check_bt_account_info 校验宝塔账号是否登录
 */
class CertViewModel(private val repo: WafRepository) : ViewModel() {

    var certs by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var sites by mutableStateOf<List<Map<String, Any?>>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var btAccountOk by mutableStateOf<Boolean?>(null)

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = repo.safeCall("wafmastersite/get_all_ssl", emptyMap())
                certs = r.res.asList()
                sites = runCatching {
                    repo.safeCall("wafmastersite/get_site_list", mapOf("p" to 1, "p_size" to 100))
                        .asMap()["list"].asList()
                }.getOrDefault(emptyList())
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    /** 申请证书：domain 每行一个，传数组。 */
    fun applyCert(domains: List<String>, siteId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "acmesh/apply_cert",
                    mapOf("domain" to domains, "site_id" to siteId, "types" to "apply")
                )
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) {
                    message = "证书申请已提交，稍后可在列表查看"
                    load()
                }
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun renewCert(domains: List<String>, siteId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "acmesh/renew_cert",
                    mapOf("domain" to domains, "site_id" to siteId, "types" to "renew")
                )
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) {
                    message = "续签已提交"
                    load()
                }
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun checkBtAccount() {
        viewModelScope.launch {
            btAccountOk = runCatching {
                repo.safeCall("acmesh/check_bt_account_info", emptyMap()).isSuccess
            }.getOrDefault(false)
        }
    }

    fun delete(name: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall("wafmastersite/delete_ssl", mapOf("ssl_name" to name))
                onResult(if (r.isSuccess) null else r.errorMessage())
                if (r.isSuccess) load()
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    /** 部署证书到站点：需要 ssl_name + site_id。 */
    fun deploy(sslName: String, siteId: String, siteName: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.safeCall(
                    "wafmastersite/deploy_ssl",
                    mapOf("ssl_name" to sslName, "site_id" to siteId, "site_name" to siteName)
                )
                onResult(if (r.isSuccess) null else r.errorMessage())
            } catch (e: Exception) {
                onResult(e.message)
            }
        }
    }

    fun consumeMessage() {
        message = null
    }
}
