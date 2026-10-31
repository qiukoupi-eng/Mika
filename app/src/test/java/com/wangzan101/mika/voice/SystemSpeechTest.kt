package com.wangzan101.mika.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemSpeechTest {
    @Test
    fun errorCodesMapToFailures() {
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechFailure.Busy, SystemSpeech.failureOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(SpeechFailure.NoPermission, SystemSpeech.failureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SpeechFailure.MicUnavailable, SystemSpeech.failureOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(SpeechFailure.MicUnavailable, SystemSpeech.failureOf(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun languageTagsFollowAppLanguage() {
        assertEquals("zh-CN", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.SimplifiedChinese, java.util.Locale.US))
        assertEquals("zh-TW", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.TraditionalChinese, java.util.Locale.US))
        assertEquals("en-US", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.English, java.util.Locale.CHINA))
        assertEquals("fr-FR", SystemSpeech.speechTag(com.wangzan101.mika.i18n.AppLanguage.System, java.util.Locale.FRANCE))
        assertEquals("zh", SystemSpeech.isoCode("zh-TW"))
        assertEquals("en", SystemSpeech.isoCode("en-US"))
    }
}
