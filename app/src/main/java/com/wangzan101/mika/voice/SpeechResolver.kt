package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.Provider

/** 手机自带识别的能力:能不能用、是不是在本机上识别(不联网)。 */
data class SystemCapability(val available: Boolean, val onDevice: Boolean)

/** 这一次实际要用的识别引擎;选择的东西不在了就给出 [Unavailable] 和原因,由界面提示。 */
sealed interface SpeechEngineSpec {
    data class System(val onDevice: Boolean) : SpeechEngineSpec
    data class Provider(val provider: com.wangzan101.mika.settings.Provider, val model: String, val method: TranscribeMethod) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    enum class Why { Off, NoSystemNoModel, SystemGone, ProviderGone }
}

/** 把用户的选择、手机能力和现有服务商对起来,得出这次用哪个引擎。纯函数,不碰系统。 */
object SpeechResolver {
    fun resolve(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        SpeechChoice.Auto ->
            if (system.available) SpeechEngineSpec.System(system.onDevice)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel)
        SpeechChoice.System ->
            if (system.available) SpeechEngineSpec.System(system.onDevice)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone)
        is SpeechChoice.Provider -> {
            val p = providers.firstOrNull { it.id == choice.providerId }
            if (p == null) SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
            else SpeechEngineSpec.Provider(p, choice.model, choice.method)
        }
    }
}
