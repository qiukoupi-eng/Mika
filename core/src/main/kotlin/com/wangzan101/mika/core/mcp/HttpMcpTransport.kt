package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.adb.OwnListeningPorts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 把 [McpServer] 的三个入口(工具会话、整任务提交、取消)接到本机 HTTP JSON-RPC。
 * 默认只听 [HttpMcpConfig.DEFAULT_HOST];真正的授权层目前是可选的共享 Bearer。
 *
 * 协议: `POST {path}` 体为 JSON-RPC 2.0 对象(也接受批量数组)。方法:
 * - `initialize` / `ping`
 * - `tasks/submit` `{goal, threadId?, attendance?}` → `{id}`
 * - `tasks/cancel` `{id}`
 * - `tasks/events` `{id, after?}` → `{events, ended}`
 * - `session/open` `{threadId?, attendance?}` → `{sessionId, tools}`
 * - `session/call` `{sessionId, name, arguments}` → 工具结果对象
 * - `session/close` `{sessionId}`
 *
 * `GET /health`(同主机端口)不鉴权,只回 `ok`。
 */
class HttpMcpTransport(
    private val mcp: McpServer,
    private val config: HttpMcpConfig = HttpMcpConfig(),
    private val enableToolSessions: Boolean = true,
) : Closeable {
    private val server: ServerSocket = ServerSocket(config.port, config.backlog, InetAddress.getByName(config.host))
    private val running = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mcp-http").apply { isDaemon = true } }
    private val tasks = ConcurrentHashMap<String, TrackedTask>()
    private val sessions = ConcurrentHashMap<String, McpToolSession>()
    private val acceptThread = Thread(::acceptLoop, "mcp-http-accept").apply { isDaemon = true }

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://${config.host}:$port"
    val mcpUrl: String get() = "$baseUrl${config.path}"

    init {
        OwnListeningPorts.add(server.localPort)
        acceptThread.start()
    }

    private fun acceptLoop() {
        server.soTimeout = 500
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running.get()) break else continue
            }
            pool.execute { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = READ_TIMEOUT_MS
            try {
                val input = BufferedInputStream(s.getInputStream())
                val request = readRequest(input) ?: return respond(s.getOutputStream(), 400, TYPE_TEXT, "bad request")
                when {
                    request.method == "GET" && request.path == "/health" ->
                        respond(s.getOutputStream(), 200, TYPE_JSON, """{"ok":true,"service":"mika-mcp-http","port":$port}""")
                    request.method == "POST" && request.path == config.path -> handleRpc(request, s.getOutputStream())
                    else -> respond(s.getOutputStream(), 404, TYPE_TEXT, "Not Found")
                }
            } catch (_: Exception) {
                runCatching { respond(s.getOutputStream(), 500, TYPE_TEXT, "error") }
            }
        }
    }

    private fun handleRpc(request: HttpRequest, out: OutputStream) {
        if (!authorized(request)) {
            respond(out, 401, TYPE_JSON, """{"ok":false,"error":"unauthorized"}""")
            return
        }
        val root = runCatching { JSON.parseToJsonElement(request.body) }.getOrNull()
            ?: return respond(out, 400, TYPE_JSON, rpcError(null, -32700, "parse error"))
        val body = when (root) {
            is JsonArray -> buildJsonArray { root.forEach { add(dispatchOne(it)) } }.toString()
            else -> dispatchOne(root).toString()
        }
        respond(out, 200, TYPE_JSON, body)
    }

    private fun dispatchOne(el: JsonElement): JsonElement {
        val obj = el as? JsonObject ?: return rpcErrorObj(null, -32600, "invalid request")
        val id = obj["id"]
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
            ?: return rpcErrorObj(id, -32600, "missing method")
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            val result = dispatch(method, params)
            buildJsonObject {
                put("jsonrpc", "2.0")
                if (id != null) put("id", id) else put("id", JsonNull)
                put("result", result)
            }
        } catch (e: RpcException) {
            rpcErrorObj(id, e.code, e.message ?: "error", e.data)
        } catch (e: Exception) {
            rpcErrorObj(id, -32000, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "initialize" -> buildJsonObject {
            put("protocolVersion", PROTOCOL)
            putJsonObject("serverInfo") {
                put("name", "mika-mcp-http")
                put("version", "0.1")
            }
            putJsonObject("capabilities") {
                put("tasks", true)
                put("toolSessions", enableToolSessions)
            }
        }
        "ping" -> buildJsonObject { put("ok", true) }
        "tasks/submit" -> submitTask(params)
        "tasks/cancel" -> cancelTask(params)
        "tasks/events" -> taskEvents(params)
        "session/open" -> openSession(params)
        "session/call" -> callSession(params)
        "session/close" -> closeSession(params)
        else -> throw RpcException(-32601, "method not found: $method")
    }

    private fun submitTask(params: JsonObject): JsonObject {
        val goal = params.str("goal")?.trim().orEmpty()
        if (goal.isEmpty()) throw RpcException(-32602, "goal required")
        val threadId = params.str("threadId")?.trim()?.ifEmpty { null } ?: "http-${UUID.randomUUID()}"
        val attendance = parseAttendance(params.str("attendance"))
        val link = mcp.submitTask(goal, threadId, attendance)
        val tracked = TrackedTask(link)
        tasks[link.id.value] = tracked
        link.events.subscribe(tracked)
        return buildJsonObject {
            put("id", link.id.value)
            put("threadId", threadId)
        }
    }

    private fun cancelTask(params: JsonObject): JsonObject {
        val id = params.str("id") ?: throw RpcException(-32602, "id required")
        val tracked = tasks[id] ?: throw RpcException(-32001, "unknown task")
        tracked.link.cancel()
        tracked.markEnded("cancelled")
        return buildJsonObject { put("ok", true); put("id", id) }
    }

    private fun taskEvents(params: JsonObject): JsonObject {
        val id = params.str("id") ?: throw RpcException(-32602, "id required")
        val after = params["after"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val tracked = tasks[id] ?: throw RpcException(-32001, "unknown task")
        val slice = tracked.snapshot(after)
        return buildJsonObject {
            put("id", id)
            put("events", JsonArray(slice.map { JSON.parseToJsonElement(RunEvent.encode(it)) }))
            put("next", after + slice.size)
            put("ended", tracked.ended)
            tracked.endedReason?.let { put("endedReason", it) }
        }
    }

    private fun openSession(params: JsonObject): JsonObject {
        if (!enableToolSessions) throw RpcException(-32002, "tool_sessions_unavailable")
        val threadId = params.str("threadId")?.trim()?.ifEmpty { null } ?: "http-session-${UUID.randomUUID()}"
        val attendance = parseAttendance(params.str("attendance"))
        val session = mcp.openToolSession(threadId, attendance)
        val sessionId = session.spec.id.value
        sessions[sessionId] = session
        return buildJsonObject {
            put("sessionId", sessionId)
            put("threadId", threadId)
            put("tools", JsonArray(session.tools().map { toolSpecJson(it.name, it.description) }))
        }
    }

    private fun callSession(params: JsonObject): JsonObject {
        if (!enableToolSessions) throw RpcException(-32002, "tool_sessions_unavailable")
        val sessionId = params.str("sessionId") ?: throw RpcException(-32602, "sessionId required")
        val name = params.str("name") ?: throw RpcException(-32602, "name required")
        val session = sessions[sessionId] ?: throw RpcException(-32001, "unknown session")
        val arguments = when (val a = params["arguments"]) {
            null, JsonNull -> "{}"
            is JsonPrimitive -> a.content
            else -> a.toString()
        }
        // 阻塞在工作线程上:外层 accept 已把每个连接丢进池
        return kotlinx.coroutines.runBlocking { session.call(name, arguments) }
    }

    private fun closeSession(params: JsonObject): JsonObject {
        if (!enableToolSessions) throw RpcException(-32002, "tool_sessions_unavailable")
        val sessionId = params.str("sessionId") ?: throw RpcException(-32602, "sessionId required")
        val session = sessions.remove(sessionId) ?: throw RpcException(-32001, "unknown session")
        kotlinx.coroutines.runBlocking { session.close() }
        return buildJsonObject { put("ok", true); put("sessionId", sessionId) }
    }

    private fun authorized(request: HttpRequest): Boolean {
        val expected = config.token ?: return true
        val auth = request.headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true) &&
            auth.substring(7).trim() == expected) return true
        val header = request.headers["x-mika-token"]
        return header != null && header == expected
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        OwnListeningPorts.remove(server.localPort)
        runCatching { server.close() }
        pool.shutdownNow()
        runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
        sessions.keys.toList().forEach { id ->
            sessions.remove(id)?.let { runCatching { kotlinx.coroutines.runBlocking { it.close() } } }
        }
        tasks.clear()
        runCatching { acceptThread.join(1000) }
    }

    private class TrackedTask(val link: McpTaskLink) : RunSink {
        private val events = CopyOnWriteArrayList<RunEvent>()
        @Volatile var ended = false
            private set
        @Volatile var endedReason: String? = null
            private set

        override fun event(e: RunEvent) {
            events += e
            if (e is RunEvent.State && e.name == "mcp_task_ended") markEnded(e.detail)
        }

        fun markEnded(reason: String?) {
            ended = true
            if (endedReason == null) endedReason = reason
        }

        fun snapshot(after: Int): List<RunEvent> {
            val all = events.toList()
            if (after <= 0) return all
            if (after >= all.size) return emptyList()
            return all.subList(after, all.size)
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private class RpcException(val code: Int, message: String, val data: JsonElement? = null) : RuntimeException(message)

    companion object {
        const val PROTOCOL = "2024-11-05-mika-http"
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_BODY = 1_000_000
        private const val TYPE_JSON = "application/json; charset=utf-8"
        private const val TYPE_TEXT = "text/plain; charset=utf-8"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        private fun parseAttendance(raw: String?): Attendance = when (raw?.trim()?.lowercase()) {
            null, "", "unattended" -> Attendance.Unattended
            "present" -> Attendance.Present
            "reachable" -> Attendance.Reachable
            else -> Attendance.Unattended
        }

        private fun toolSpecJson(name: String, description: String) = buildJsonObject {
            put("name", name)
            put("description", description)
        }

        private fun rpcError(id: JsonElement?, code: Int, message: String): String =
            rpcErrorObj(id, code, message).toString()

        private fun rpcErrorObj(id: JsonElement?, code: Int, message: String, data: JsonElement? = null) = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            }
        }

        private fun readRequest(input: BufferedInputStream): HttpRequest? {
            val line = readLine(input) ?: return null
            val parts = line.split(' ')
            if (parts.size < 2) return null
            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore('?')
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i <= 0) continue
                headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            val body = if (length == 0) "" else {
                val buf = ByteArray(length)
                var off = 0
                while (off < length) {
                    val n = input.read(buf, off, length - off)
                    if (n < 0) break
                    off += n
                }
                if (off < length) return null
                String(buf, Charsets.UTF_8)
            }
            return HttpRequest(method, path, headers, body)
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ArrayList<Byte>(64)
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), Charsets.ISO_8859_1)
                if (b == '\n'.code) break
                if (b != '\r'.code) bytes += b.toByte()
                if (bytes.size > 8_192) return null
            }
            return String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }

        private fun respond(out: OutputStream, code: Int, type: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                else -> "Error"
            }
            val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }
}
