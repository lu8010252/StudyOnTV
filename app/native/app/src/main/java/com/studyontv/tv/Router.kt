package com.studyontv.tv

import java.io.OutputStream

/**
 * 一次 HTTP 请求的上下文。路由处理器只从这里取输入、通过 [out] 写响应，
 * 不再依赖 LanServer 里那一大堆局部变量。
 */
class RouteContext(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String,
    val out: OutputStream,
    /** 请求来自本机（电视自己的 WebView） */
    val local: Boolean,
    /** 带了设备令牌的本机请求 */
    val deviceAccess: Boolean,
    /** 已通过家长令牌 / 管理密钥鉴权 */
    val parent: Boolean,
    val token: String?,
    /** 集成令牌的权限；null 表示不是集成令牌 */
    val integration: IntegrationAuth.Scope?
)

/**
 * 极简路由表：先找精确路径，再按注册顺序匹配前缀（`/api/reviews/{id}` 这类）。
 * 只做分发，鉴权仍由 LanServer 在进入路由前统一完成。
 */
class Router {
    private class Route(val method: String, val path: String, val prefix: Boolean, val handler: (RouteContext) -> Unit)

    private val routes = mutableListOf<Route>()

    fun get(path: String, handler: (RouteContext) -> Unit) = add("GET", path, false, handler)
    fun post(path: String, handler: (RouteContext) -> Unit) = add("POST", path, false, handler)
    fun put(path: String, handler: (RouteContext) -> Unit) = add("PUT", path, false, handler)
    fun delete(path: String, handler: (RouteContext) -> Unit) = add("DELETE", path, false, handler)
    fun prefixGet(path: String, handler: (RouteContext) -> Unit) = add("GET", path, true, handler)
    fun prefixPost(path: String, handler: (RouteContext) -> Unit) = add("POST", path, true, handler)
    fun prefixDelete(path: String, handler: (RouteContext) -> Unit) = add("DELETE", path, true, handler)

    private fun add(method: String, path: String, prefix: Boolean, handler: (RouteContext) -> Unit) {
        routes.add(Route(method, path, prefix, handler))
    }

    /** 返回 true 表示已经处理了这个请求；false 交给静态文件服务 */
    fun dispatch(ctx: RouteContext): Boolean {
        routes.firstOrNull { !it.prefix && it.method == ctx.method && it.path == ctx.path }?.let {
            it.handler(ctx)
            return true
        }
        routes.firstOrNull { it.prefix && it.method == ctx.method && ctx.path.startsWith(it.path) }?.let {
            it.handler(ctx)
            return true
        }
        return false
    }
}
