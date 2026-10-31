package com.wangzan101.mika.core.mcp

/**
 * HTTP MCP 传输的监听配置。默认只绑本机回环地址,别的设备连不进来;
 * 换端口或绑其它地址时改 [port] / [host](非回环时务必设 [token])。
 */
data class HttpMcpConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    /** 非空时要求请求头 `Authorization: Bearer <token>`(或 `X-Mika-Token`)。 */
    val token: String? = null,
    val backlog: Int = 16,
    val path: String = DEFAULT_PATH,
) {
    init {
        require(port in 0..65535) { "port out of range" }
        require(host.isNotBlank()) { "host blank" }
        require(path.startsWith("/")) { "path must start with /" }
        require(token == null || token.isNotBlank()) { "token blank" }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 18765
        const val DEFAULT_PATH = "/mcp"
    }
}
