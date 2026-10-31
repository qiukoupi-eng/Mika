package com.wangzan101.mika.voice

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 用户为「语音转文字」做的选择,以及它在设置里的存取。具体用哪个引擎由 [SpeechResolver] 结合手机能力再定。 */
sealed interface SpeechChoice {
    /** 没选过:有手机自带识别就用它。 */
    data object Auto : SpeechChoice

    /** 明确选了手机自带识别。 */
    data object System : SpeechChoice

    /** 不用语音输入。 */
    data object Off : SpeechChoice

    /** 用某个服务商的某个模型转写,[method] 决定请求怎么发。 */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice
}

/** 服务商转写音频的三种接口形态。 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline }

/** 选择存在 [FeatureFlags] 那份设置里,一个键、一段 JSON;读不出来一律当 [SpeechChoice.Auto]。 */
class SpeechSettings(private val prefs: SharedPreferences) {
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()

    fun set(choice: SpeechChoice) {
        prefs.edit().putString(KEY, encode(choice).toString()).apply()
        _choice.value = choice
    }

    private fun read(): SpeechChoice {
        val raw = prefs.getString(KEY, null) ?: return SpeechChoice.Auto
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: SpeechChoice.Auto
    }

    private fun encode(choice: SpeechChoice): JsonObject = buildJsonObject {
        when (choice) {
            SpeechChoice.Auto -> put(TYPE, JsonPrimitive("auto"))
            SpeechChoice.System -> put(TYPE, JsonPrimitive("system"))
            SpeechChoice.Off -> put(TYPE, JsonPrimitive("off"))
            is SpeechChoice.Provider -> {
                put(TYPE, JsonPrimitive("provider"))
                put("providerId", JsonPrimitive(choice.providerId))
                put("model", JsonPrimitive(choice.model))
                put("method", JsonPrimitive(choice.method.name))
            }
        }
    }

    /** 字段缺了、类型不认识、方法名不存在都返回 null,由调用处退回自动。 */
    private fun decode(obj: JsonObject): SpeechChoice? = when (obj[TYPE]?.jsonPrimitive?.contentOrNull) {
        "auto" -> SpeechChoice.Auto
        "system" -> SpeechChoice.System
        "off" -> SpeechChoice.Off
        "provider" -> {
            val id = obj["providerId"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val method = TranscribeMethod.entries.firstOrNull { it.name == obj["method"]?.jsonPrimitive?.contentOrNull }
            if (id == null || model == null || method == null) null else SpeechChoice.Provider(id, model, method)
        }
        else -> null
    }

    companion object {
        private const val KEY = "speech_choice"
        private const val TYPE = "type"

        fun get(context: Context) = SpeechSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
