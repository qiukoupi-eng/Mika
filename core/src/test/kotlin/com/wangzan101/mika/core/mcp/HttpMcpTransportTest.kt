package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.tool.FakeScreen
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpMcpTransportTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private var transport: HttpMcpTransport? = null

    @After fun tearDown() { transport?.close(); transport = null }

    private fun start(token: String? = "test-token", enableSessions: Boolean = true): HttpMcpTransport {
        val bus = RunEventBus()
        val server = McpServer(
            assemble = { spec -> toolDispatcher(FakeScreen(), runSpec = spec) },
            tasks = McpTaskHost { spec ->
                McpTaskLink(spec.id, bus) { bus.event(RunEvent.State(1, "mcp_task_ended", "cancelled")) }
            },
        )
        val t = HttpMcpTransport(server, HttpMcpConfig(port = 0, token = token), enableToolSessions = enableSessions)
        transport = t
        Thread.sleep(50)
        return t
    }

    private fun rpc(t: HttpMcpTransport, method: String, params: JsonObject = buildJsonObject {}, token: String? = "test-token", id: Int = 1): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        val req = Request.Builder().url(t.mcpUrl).post(body.toRequestBody("application/json".toMediaType())).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        http.newCall(req).execute().use { resp ->
            assertEquals("HTTP ${resp.code}", 200, resp.code)
            return json.parseToJsonElement(resp.body!!.string()).jsonObject
        }
    }

    @Test fun healthDoesNotNeedToken() {
        val t = start()
        val req = Request.Builder().url("${t.baseUrl}/health").get().build()
        http.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("mika-mcp-http"))
        }
    }

    @Test fun bearerRequiredWhenConfigured() {
        val t = start(token = "secret")
        val denied = Request.Builder().url(t.mcpUrl)
            .post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(denied).execute().use { assertEquals(401, it.code) }
        val ok = rpc(t, "ping", token = "secret")
        assertTrue(ok["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun submitAndCancelTask() {
        val t = start()
        val submitted = rpc(t, "tasks/submit", buildJsonObject { put("goal", "查天气"); put("threadId", "t1") })
        val id = submitted["result"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(id.isNotBlank())
        assertFalse(rpc(t, "tasks/events", buildJsonObject { put("id", id) })["result"]!!.jsonObject["ended"]!!.jsonPrimitive.boolean)
        assertTrue(rpc(t, "tasks/cancel", buildJsonObject { put("id", id) })["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
        assertTrue(rpc(t, "tasks/events", buildJsonObject { put("id", id) })["result"]!!.jsonObject["ended"]!!.jsonPrimitive.boolean)
    }

    @Test fun toolSessionRoundTrip() {
        val t = start()
        val opened = rpc(t, "session/open", buildJsonObject { put("threadId", "s1") })
        val sessionId = opened["result"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
        assertTrue(opened["result"]!!.jsonObject["tools"]!!.jsonArray.isNotEmpty())
        assertTrue(rpc(t, "session/close", buildJsonObject { put("sessionId", sessionId) })["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun toolSessionsCanBeDisabled() {
        val t = start(enableSessions = false)
        val err = rpc(t, "session/open")
        assertEquals(-32002, err["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertFalse(rpc(t, "initialize")["result"]!!.jsonObject["capabilities"]!!.jsonObject["toolSessions"]!!.jsonPrimitive.boolean)
    }

    @Test fun initializeReportsServer() {
        val t = start()
        val init = rpc(t, "initialize")["result"]!!.jsonObject
        assertEquals("mika-mcp-http", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(init["capabilities"]!!.jsonObject["tasks"]!!.jsonPrimitive.boolean)
    }
}
