package com.wangzan101.mika.task

import android.content.Context
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.mcp.HttpMcpConfig
import com.wangzan101.mika.core.mcp.HttpMcpTransport
import com.wangzan101.mika.core.mcp.McpServer
import com.wangzan101.mika.settings.FeatureFlags
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App 进程里的 MCP HTTP 传输:挂在 [RemoteTaskHub] 上,整任务经现有 [TaskService.startMcp] 走;
 * 工具会话暂未接到真机能力(enableToolSessions=false),避免假屏幕进生产路径。
 * 默认只听 127.0.0.1;开关、端口、令牌在 [FeatureFlags]。
 */
class McpHttpRuntime(private val app: MikaApp) {
    private val current = AtomicReference<HttpMcpTransport?>(null)

    data class Status(val enabled: Boolean, val listening: Boolean, val host: String, val port: Int, val mcpUrl: String?)

    fun status(): Status {
        val flags = FeatureFlags.get(app)
        val t = current.get()
        return Status(
            enabled = flags.mcpHttpEnabled(),
            listening = t != null,
            host = flags.mcpHttpHost(),
            port = t?.port ?: flags.mcpHttpPort(),
            mcpUrl = t?.mcpUrl,
        )
    }

    /** 按当前开关启停;设置变更后再次调用即可。 */
    @Synchronized fun apply() {
        stop()
        val flags = FeatureFlags.get(app)
        if (!flags.mcpHttpEnabled()) return
        val token = flags.ensureMcpHttpToken()
        try {
            val server = McpServer(
                assemble = { error("tool sessions not wired on device") },
                tasks = app.remoteTasks,
            )
            val transport = HttpMcpTransport(
                server,
                HttpMcpConfig(host = flags.mcpHttpHost(), port = flags.mcpHttpPort(), token = token),
                enableToolSessions = false,
            )
            current.set(transport)
            writeEndpointFile(transport, token, flags.mcpHttpHost())
            Log.i(TAG, "MCP HTTP listening on ${transport.mcpUrl} (Bearer required)")
        } catch (e: Exception) {
            Log.e(TAG, "MCP HTTP failed to start", e)
            current.set(null)
        }
    }

    @Synchronized fun stop() {
        current.getAndSet(null)?.close()
        endpointFile(app).delete()
    }

    private fun writeEndpointFile(transport: HttpMcpTransport, token: String, host: String) {
        val body = buildJsonObject {
            put("host", host)
            put("port", transport.port)
            put("path", HttpMcpConfig.DEFAULT_PATH)
            put("url", transport.mcpUrl)
            put("token", token)
            put("auth", "Authorization: Bearer <token>")
        }.toString()
        endpointFile(app).writeText(body)
    }

    companion object {
        private const val TAG = "MikaMcpHttp"
        /** 本机可读的端点说明(含令牌);仅 App 私有目录,用 adb run-as 查看。 */
        fun endpointFile(context: Context) = File(context.applicationContext.filesDir, "mcp-http.json")

        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
