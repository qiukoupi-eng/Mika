package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class VoiceCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun engineChipNamesWhatTranscribes() {
        assertEquals("手机自带 · 离线", VoiceCopy.engine(SpeechEngineSpec.System(onDevice = true)))
        assertEquals("手机自带", VoiceCopy.engine(SpeechEngineSpec.System(onDevice = false)))
    }

    @Test fun failuresSayWhichKind() {
        assertEquals("识别服务正忙，稍后再按住说", VoiceCopy.failure(SpeechFailure.Busy))
        assertEquals("网络断了，没能转成文字", VoiceCopy.failure(SpeechFailure.Network))
        assertEquals("麦克风用不了，可能正被别的 App 占着", VoiceCopy.failure(SpeechFailure.MicUnavailable))
        assertEquals("没能转成文字：额度已用完", VoiceCopy.failure(SpeechFailure.Provider(ModelError.QuotaExceeded())))
    }

    @Test fun silentPhoneRecognizerPointsToSpeechSettings() {
        assertEquals(
            "手机自带的语音识别没有响应，听不到字。到「设置 › 模型 › 语音转文字」选一个服务商的语音模型就能用。",
            VoiceCopy.unavailable(SpeechEngineSpec.Why.SystemNoResponse),
        )
    }

    @Test fun countdownOnlyInTheLastTenSeconds() {
        assertNull(VoiceCopy.countdown(49_000))
        assertEquals("还剩 10 秒", VoiceCopy.countdown(50_000))
        assertEquals("还剩 1 秒", VoiceCopy.countdown(59_200))
    }
}
