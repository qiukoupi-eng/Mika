package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechSettingsTest {
    @Test fun freshIsAuto() {
        assertEquals(SpeechChoice.Auto, SpeechSettings(MemoryPrefs()).choice.value)
    }

    @Test fun everyChoiceSurvivesARestart() {
        val choices = listOf(
            SpeechChoice.Auto,
            SpeechChoice.System,
            SpeechChoice.Off,
            SpeechChoice.Provider("p1", "whisper-1", TranscribeMethod.AudioTranscriptions),
            SpeechChoice.Provider("p2", "gpt-4o-audio", TranscribeMethod.ChatInputAudio),
            SpeechChoice.Provider("p3", "gemini-flash", TranscribeMethod.GeminiInline),
        )
        for (c in choices) {
            val prefs = MemoryPrefs()
            val s = SpeechSettings(prefs)
            s.set(c)
            assertEquals(c, s.choice.value)
            assertEquals(c, SpeechSettings(prefs).choice.value)
        }
    }

    @Test fun silentMarkSurvivesUntilSystemIsPickedAgain() {
        val prefs = MemoryPrefs()
        val s = SpeechSettings(prefs)
        assertEquals(false, s.systemSilent.value)
        s.markSystemSilent()
        assertEquals(true, s.systemSilent.value)
        assertEquals(true, SpeechSettings(prefs).systemSilent.value)
        // 选别的不清:记号说的是这台手机的识别服务
        s.set(SpeechChoice.Off)
        assertEquals(true, SpeechSettings(prefs).systemSilent.value)
        s.set(SpeechChoice.System)
        assertEquals(false, s.systemSilent.value)
        assertEquals(false, SpeechSettings(prefs).systemSilent.value)
    }

    @Test fun brokenJsonReadsAsAuto() {
        for (bad in listOf("{not json", "[]", """{"type":"nope"}""", """{"type":"provider","providerId":"p1"}""")) {
            val prefs = MemoryPrefs()
            prefs.edit().putString("speech_choice", bad).commit()
            assertEquals(bad, SpeechChoice.Auto, SpeechSettings(prefs).choice.value)
        }
    }
}
