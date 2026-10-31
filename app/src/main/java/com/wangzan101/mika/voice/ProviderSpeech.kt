package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 录音器的最小接口:[PcmRecorder] 实现它,测试换成假的。 */
interface Recorder {
    val level: StateFlow<Float>
    fun start(): Boolean
    fun stop(): ShortArray
    fun cancel()
}

/**
 * 服务商那条路:按着时录音、只发 [SpeechEvent.Level];松开后发 [SpeechEvent.Converting],
 * 把整段录音交给 [transcribe](WAV 进、文字出,失败抛 [ModelError]),给出 Final 或 Failed(Provider)。
 * 构造时就开始录;录音不足 [MIN_MS] 当没听清,不去调服务商。
 */
class ProviderSpeech(
    private val recorder: Recorder,
    private val transcribe: suspend (wav: ByteArray) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false

    private var levels: Job? = null
    private var work: Job? = null
    private var released = false

    init {
        if (!recorder.start()) {
            out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        } else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
        }
    }

    @Synchronized
    override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        levels?.cancel()
        // stop 会等读循环退出(短暂阻塞),放到后台线程,别卡住主线程
        work = scope.launch {
            val pcm = recorder.stop()
            if (pcm.size < Wav.RATE * MIN_MS / 1000) {
                out.finish(SpeechEvent.Final(""))
                return@launch
            }
            out.send(SpeechEvent.Converting)
            val result = try {
                SpeechEvent.Final(transcribe(Wav.encode(pcm)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                SpeechEvent.Failed(SpeechFailure.Provider(e))
            } catch (e: Exception) {
                SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse(e.message.orEmpty(), e)))
            }
            out.finish(result)
        }
    }

    @Synchronized
    override fun cancel() {
        if (!out.cancel()) return
        levels?.cancel()
        work?.cancel()
        recorder.cancel()
    }

    companion object {
        /** 比这短的录音多半是误触,直接当没听清。 */
        const val MIN_MS = 500
    }
}
