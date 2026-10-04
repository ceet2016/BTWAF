package com.bt.waf.data.net

import com.bt.waf.data.model.WafResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 通用 REST 接口：所有 WAF 接口均为 POST + JSON。
 * 路径使用 module/action（不含 /api 前缀，由 baseUrl 提供）。
 *
 * 返回 [Response] 包装而非裸对象：aawaf 用 HTTP 400 携带业务错误
 * （如「请填写验证码」「用户名或密码错误」），直接反序列化裸对象会抛
 * HttpException 丢掉这段文案，界面只能显示 "HTTP 400"。
 */
interface WafApi {
    @POST("{path}")
    suspend fun post(
        @Path("path", encoded = true) path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<WafResponse>
}
