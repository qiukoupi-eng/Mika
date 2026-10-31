package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriberTest {
    private val server = MockWebServer().apply { start() }
    private val t = Transcriber(OkHttpClient())

    @After fun stop() = server.shutdown()

    private fun provider(kind: ProviderConfig.Kind, suffix: String) =
        Provider("p", "P", kind, server.url(suffix).toString().trimEnd('/'))

    private val wav = Wav.silence(100)

    @Test
    fun audioTranscriptionsSendsMultipart() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "whisper-1", TranscribeMethod.AudioTranscriptions, wav, "zh")
        val r = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", r.path)
        assertEquals("Bearer k", r.getHeader("Authorization"))
        val body = r.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("whisper-1"))
        assertTrue(body.contains("name=\"language\""))
        assertEquals("你好", out)
    }

    @Test
    fun chatInputAudioParsesChoice() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"今天下雨"}}]}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.ChatInputAudio, wav, null)
        val r = server.takeRequest()
        assertEquals("/v1/chat/completions", r.path)
        assertTrue(r.body.readUtf8().contains("input_audio"))
        assertEquals("今天下雨", out)
    }

    @Test
    fun geminiInlineParses() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}"""))
        val out = t.transcribe(provider(ProviderConfig.Kind.Gemini, ""), "k", "gemini-x", TranscribeMethod.GeminiInline, wav, null)
        val r = server.takeRequest()
        assertEquals("/v1beta/models/gemini-x:generateContent", r.path)
        assertEquals("k", r.getHeader("x-goog-api-key"))
        assertTrue(r.body.readUtf8().contains("inline_data"))
        assertEquals("hello", out)
    }

    @Test
    fun detectFallsBackToChatAudio() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":""}}]}"""))
        val m = t.detect(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m")
        assertEquals(TranscribeMethod.ChatInputAudio, m)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unauthorizedMapsToKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        try {
            t.transcribe(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m", TranscribeMethod.AudioTranscriptions, wav, null)
            fail()
        } catch (e: ModelError) {
            assertTrue(e is ModelError.Auth)
        }
    }

    @Test
    fun detectStopsOnKeyError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"x"}}]}"""))
        try {
            t.detect(provider(ProviderConfig.Kind.OpenAiCompat, "/v1"), "k", "m")
            fail()
        } catch (e: ModelError) {
            assertTrue(e is ModelError.Auth)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun looksLikeSpeech() {
        listOf("whisper-1", "qwen3-asr-flash", "gpt-4o-transcribe", "paraformer-v2").forEach {
            assertTrue(it, Transcriber.looksLikeSpeech(ListedModel(it)))
        }
        listOf("qwen3-max", "gpt-4o").forEach { assertFalse(it, Transcriber.looksLikeSpeech(ListedModel(it))) }
    }
}
