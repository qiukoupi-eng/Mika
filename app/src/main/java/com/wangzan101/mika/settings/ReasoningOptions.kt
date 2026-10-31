package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel

/**
 * 思考档位。服务商列表写明档位的模型,四种协议(Chat Completions、Messages、generateContent、Responses)都照它给;
 * 没写明时,Responses 一族给通用手动档位,Messages 和 generateContent 在服务商说明支持思考时给各自协议认的档位,
 * 兼容接口不猜(不把 `reasoning_effort` 发给没声明它的端点)。手动档位都带提示,实际支持情况以服务端为准。
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // 列表没说明档位时供手动选择,不声称每个模型都支持;界面会说明,默认始终不指定。
        private val responsesLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        // Anthropic 的自适应思考 + output_config.effort;`none`/`minimal` 不是它认的档位。
        private val anthropicLevels = listOf("low", "medium", "high", "xhigh", "max")
        // Gemini 的 thinkingLevel;`xhigh`/`max` 不是它认的档位。
        private val geminiLevels = listOf("minimal", "low", "medium", "high")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            listed?.reasoningEfforts?.let { return ReasoningOptions(it, fromProvider = true) }
            // 仍返回空选项,使已保存的旧档位能在界面恢复默认。
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            // 订阅登录固定走 Responses,不跟模型列表里写的别的协议走(见 ProviderStore.buildModel)
            val kind = if (config.kind == ProviderConfig.Kind.Xai) config.kind
                else ProviderStore.adapterFor(config, listed?.endpoint).first
            val levels = when (kind) {
                ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai -> responsesLevels
                // 列表只说了「能思考」、没给档位时,给这个协议认的通用档位;列表都没加载时不猜
                ProviderConfig.Kind.Anthropic -> anthropicLevels.takeIf { listed?.reasoning == true }
                ProviderConfig.Kind.Gemini -> geminiLevels.takeIf { listed?.reasoning == true }
                ProviderConfig.Kind.OpenAiCompat -> null
            } ?: return null
            return ReasoningOptions(levels, fromProvider = false)
        }
    }
}
