package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

/**
 * 用 OpenRouter 账号登录走一遍:浏览器(假的)打开授权页,带着 code 回到本机回调,换到 Key,
 * 按 OpenRouter 预设存成服务商、选上主模型、打开详情;退出只删本机的 Key。换 Key 的接口是本地假服务。
 */
class OpenRouterSignInTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val listCalls = mutableListOf<Pair<String, String?>>()

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        openRouterAuth = OpenRouterAuth(http, keysUrl = server.url("/api/v1/auth/keys").toString()),
        modelLister = { _, url, apiKey, _ ->
            synchronized(listCalls) { listCalls += url to apiKey }
            ModelListResult.Ok(listOf(ListedModel("openai/gpt-x"), ListedModel("anthropic/claude-x")))
        },
    )

    /** 假浏览器:打开授权页时照用户同意了那样,带着 code 和 state 请求本机回调;[pages] 记下回调后浏览器里显示的结果。 */
    private inner class Browser(private val approve: Boolean = true) : SignInBrowser {
        val opened = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        override fun open(url: String) {
            opened += url
            val u = url.toHttpUrl()
            val callback = u.queryParameter("callback_url")!!.toHttpUrl().newBuilder().host("127.0.0.1")
            if (approve) callback.addQueryParameter("code", "the-code").addQueryParameter("state", u.queryParameter("state"))
            else callback.addQueryParameter("error", "access_denied")
            Thread { http.newCall(Request.Builder().url(callback.build()).build()).execute().close() }.start()
        }
        override fun returnToApp() {}
        override fun page(failure: String?): String { pages += failure; return "<p>ok</p>" }
    }

    private fun ModelSetupViewModel.signInAndWait(provider: AccountProvider) = runBlocking {
        signInWithAccount(provider)
        withTimeout(15_000) { state.first { !it.accountSignIn.running && (it.signedInProvider != null || it.accountSignIn.failure != null) } }
    }

    @Test fun signInStoresAnEncryptedKeyAndOpensTheProvider() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"key":"sk-or-v1-SECRET"}"""))
        val s = store()
        val browser = Browser()
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)

        val p = s.openRouterAccount()!!
        assertEquals(p.id, ui.signedInProvider)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals(OpenRouterAuth.API_BASE, p.baseUrl)
        assertEquals("OpenRouter", p.name)
        assertEquals("sk-or-v1-SECRET", s.providerKey(p.id))
        assertEquals(listOf(OpenRouterAuth.API_BASE to "sk-or-v1-SECRET"), listCalls)
        assertTrue(ui.openRouterAccount!!.signedIn)
        assertNotNull("还没有主模型时选一个", ui.roles.main)
        assertEquals(listOf<String?>(null), browser.pages)
        assertTrue(browser.opened.single().startsWith("https://openrouter.ai/auth?"))
        val sent = server.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("\"code\":\"the-code\""))
        // Key 只加密存,不进配置文件
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("SECRET")) }
        // 重新读入时还认得是授权来的
        assertEquals(p.id, store().openRouterAccount()?.id)

        vm.signedInShown()
        assertNull(vm.state.value.signedInProvider)
        runBlocking {
            vm.signOutAccount(p.id)
            withTimeout(5_000) { vm.state.first { it.openRouterAccount?.signedIn == false } }
        }
        assertNull(s.providerKey(p.id))
        assertNotNull("服务商和模型留着", s.provider(p.id)?.models)
        assertTrue(vm.state.value.signedOut(s.provider(p.id)!!))
    }

    @Test fun deniedAuthorizationSaysSoAndSavesNothing() {
        val s = store()
        val browser = Browser(approve = false)
        val vm = ModelSetupViewModel(s, scope, io = Dispatchers.IO, browser = browser, signIns = ChatGptSignIns(scope))
        val ui = vm.signInAndWait(AccountProvider.OpenRouter)
        assertEquals(AccountProvider.OpenRouter, ui.accountSignIn.provider)
        assertEquals("已取消登录。", ui.accountSignIn.failure)
        assertEquals(listOf<String?>("已取消登录。"), browser.pages)
        assertTrue(s.providers.value.isEmpty())
        assertEquals(0, server.requestCount)
    }

    /** 用户自己填 Key 连上同一家:去掉「授权来的」标记,详情里回到填 Key 的样子。 */
    @Test fun typingAKeyForTheSameProviderDropsTheSignInMark() {
        val s = store()
        val p = s.connectOpenRouter("sk-or-v1-a", emptyList())
        assertEquals(p.id, s.openRouterAccount()?.id)
        val again = s.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, "sk-typed", emptyList())
        assertEquals(p.id, again.id)
        assertNull(s.openRouterAccount())
        assertEquals("sk-typed", s.providerKey(p.id))
    }
}
