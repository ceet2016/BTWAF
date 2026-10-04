package com.bt.waf.data.net

import com.bt.waf.data.model.WafResponse
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 通用 REST 接口：所有 WAF 接口均为 POST + JSON。
 * 路径使用 module/action（不含 /api 前缀，由 baseUrl 提供）。
 */
interface WafApi {
    @POST("{path}")
    suspend fun post(
        @Path("path", encoded = true) path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): WafResponse
}
