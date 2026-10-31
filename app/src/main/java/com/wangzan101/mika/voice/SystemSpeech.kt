package com.wangzan101.mika.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** 手机自带识别:能力检测、错误码怎么归类、识别用的语言。 */
object SystemSpeech {
    /** 安卓 11 起要在清单里声明查询 RecognitionService,否则这里永远是不可用。 */
    fun capability(context: Context): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return SystemCapability(available = online || onDevice, onDevice = onDevice)
    }

    /**
     * 识别服务的错误码归到哪种失败;null 表示「这一段没听到话」(没匹配上、没开口),按着时重开、松开后照已有的给结果。
     * 列表外的码(服务端错误、语言不支持等)按服务出了问题处理。
     */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.MicUnavailable
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)
    }

    /** 本机识别说这个语言用不了(没装语言包等):换成联网识别再试。 */
    internal fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** App 语言对应的识别语言(BCP-47);跟随系统时用系统的语言。界面是英文、系统也是英文时沿用系统的地区。 */
    fun speechTag(language: AppLanguage, system: Locale = Locale.getDefault()): String = when (language) {
        AppLanguage.SimplifiedChinese -> "zh-CN"
        AppLanguage.TraditionalChinese -> "zh-TW"
        AppLanguage.English -> if (system.language == "en") system.toLanguageTag() else "en-US"
        AppLanguage.System -> system.toLanguageTag()
    }

    /** 转写接口要的 ISO-639-1 语言代码。 */
    fun isoCode(tag: String): String = Locale.forLanguageTag(tag).language

    // 安卓 12 起才有的错误码;写成数值,低版本收不到也不影响
    private const val ERROR_TOO_MANY_REQUESTS = 10
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * 手机自带识别的一次会话。[SpeechRecognizer] 只能在主线程建和调,所以这里所有对它的操作都投到主线程。
 * 按着期间识别服务自己结束一段(出最终结果或「没听到」)就立刻重开,各段由 [Segments] 拼起来;
 * 松开后 stopListening,最多等 [FINAL_WAIT_MS] 拿最终结果,等不到就用最后一次中间结果。
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    // 下面这些只在主线程读写
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var holding = true
    private val timeout = Runnable { finish(SpeechEvent.Final(segments.full())) }

    init {
        onMain { open(onDevice) }
    }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        recognizer?.stopListening()
        main.postDelayed(timeout, FINAL_WAIT_MS)
    }

    override fun cancel() {
        // 先关事件出口(任何线程都立刻生效),再到主线程收拾识别器
        if (!out.cancel()) return
        onMain {
            recognizer?.cancel()
            teardown()
        }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        val r = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
        usingOnDevice = r != null
        val rec = r ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (rec == null) {
            finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)))
            return
        }
        recognizer = rec
        rec.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 联网识别时:手机有本机识别能力就请服务尽量离线识别
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: RuntimeException) {
            finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        }
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    private fun teardown() {
        main.removeCallbacks(timeout)
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (done()) return
            // 识别服务给的大致在 -2..10 dB,压到 0..1 画波形
            out.send(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (done()) return
            val text = firstText(partialResults)
            if (text.isBlank()) return
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            if (done()) return
            segments.segmentDone(firstText(results))
            if (holding) {
                // 识别服务觉得一句说完了,但手还按着:接着听下一段
                out.send(Segments.split(segments.full()))
                listen()
            } else {
                finish(SpeechEvent.Final(segments.full()))
            }
        }

        override fun onError(error: Int) {
            if (done()) return
            if (usingOnDevice && SystemSpeech.languageMissing(error) && segments.full().isEmpty()) {
                open(preferOnDevice = false)
                if (!holding && !done()) {
                    // 已经松开了:新识别器只收尾,等待上限重新算
                    recognizer?.stopListening()
                    main.postDelayed(timeout, FINAL_WAIT_MS)
                }
                return
            }
            val failure = SystemSpeech.failureOf(error)
            when {
                failure == null && holding -> listen()
                // 已经听到的字不丢:松开后的错误(包括 stopListening 后有的服务报的 ERROR_CLIENT)、
                // 按着时中途出错,都把听到的给出去
                failure == null || segments.full().isNotEmpty() -> finish(SpeechEvent.Final(segments.full()))
                else -> finish(SpeechEvent.Failed(failure))
            }
        }
    }

    companion object {
        /** 松开后等最终结果的上限。 */
        const val FINAL_WAIT_MS = 2_000L
    }
}
