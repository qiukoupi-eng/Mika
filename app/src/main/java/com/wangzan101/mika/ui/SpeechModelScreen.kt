package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions
import com.wangzan101.mika.voice.SystemSpeech

/** 选择页上的动作。 */
class SpeechPickActions(
    /** 「手机自带」或「不用」:当场生效并返回。 */
    val pick: (SpeechChoice) -> Unit = {},
    /** (服务商 id, 模型名):先试转,通了才选上并返回;列表里点的和手填的都走这里。 */
    val pickModel: (String, String) -> Unit = { _, _ -> },
)

/**
 * 设置 › 模型 › 语音转文字:自己带一个模型状态,可以从别处(例如输入框的「还不能语音输入」)直接进入。
 * 选好(手机自带、不用、试转通过的服务商模型)后回到进来的那一页。
 */
@Composable
fun SpeechModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm = remember {
        ModelSetupViewModel(
            store, scope,
            speech = (context.applicationContext as? MikaApp)?.speech,
            systemSpeech = {
                val silent = (context.applicationContext as? MikaApp)?.speech?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
        )
    }
    val ui by vm.state.collectAsState()
    val probe by vm.speechProbe.collectAsState()
    LaunchedEffect(Unit) { vm.ensureListed(); vm.clearSpeechProbe() }
    SpeechModelContent(
        ui, probe,
        SpeechPickActions(
            pick = { vm.pickSpeech(it); onBack() },
            pickModel = { pid, model -> vm.pickSpeechModel(pid, model, onPicked = onBack) },
        ),
        onBack,
    )
}

/** 不带状态的选择页,便于测试:顶上一段说明,「手机自带」「不用」,下面按服务商分组单选。 */
@Composable
fun SpeechModelContent(ui: ModelSetupUi, probe: Map<String, String>, actions: SpeechPickActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    val system = ui.systemSpeech
    val choice = ui.speech
    // 没选过时有手机自带就是在用它,所以「没选过」也把它标成选中
    // 记成不响应时不标选中;行仍可点,重新选它就清掉记号再试
    val systemSelected = system.usable && (choice == SpeechChoice.System || choice == SpeechChoice.Auto)
    Page(tr(R.string.role_speech), onBack) {
        Text(
            tr(R.string.speech_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2,
        )
        GroupCard {
            RadioRow(
                tr(R.string.speech_system), selected = systemSelected, enabled = system.available,
                onClick = { actions.pick(SpeechChoice.System) }, first = true,
                subtitle = tr(
                    when {
                        !system.available -> R.string.speech_system_none_note
                        !system.responsive -> R.string.speech_system_silent_note
                        system.onDevice -> R.string.speech_system_note
                        else -> R.string.speech_system_note_online
                    },
                ),
                trailing = {
                    when {
                        system.usable -> StatusPill(tr(R.string.speech_tag_ok), PillTone.Ok)
                        system.available -> StatusPill(tr(R.string.speech_tag_silent), PillTone.Neutral)
                        else -> StatusPill(tr(R.string.speech_tag_none), PillTone.Neutral)
                    }
                },
            )
            RadioRow(
                tr(R.string.models_off), selected = choice == SpeechChoice.Off, onClick = { actions.pick(SpeechChoice.Off) },
                subtitle = tr(R.string.speech_off_note),
            )
        }
        SpeechOptions.groups(ui.providers).forEach { g ->
            val p = g.provider
            val testing = ui.speechTesting == p.id
            GroupLabel(ModelsCopy.providerName(p))
            if (g.models.isNotEmpty() || g.manual) {
                GroupCard {
                    g.models.forEachIndexed { i, id ->
                        val selected = (choice as? SpeechChoice.Provider)?.let { it.providerId == p.id && it.model == id } == true
                        RadioRow(
                            id, selected = selected, onClick = { actions.pickModel(p.id, id) }, first = i == 0,
                            titleStyle = ModelIdStyle, enabled = ui.speechTesting == null,
                            trailing = { CapabilityTag(tr(R.string.speech_tag_speech)) },
                        )
                    }
                    if (g.manual) {
                        Box(Modifier.testTag("speech-manual-${p.name}")) {
                            ManualModel(p, if (testing) ListState.Running else null, first = g.models.isEmpty()) { actions.pickModel(p.id, it) }
                        }
                    }
                }
            }
            when {
                probe[p.id] != null -> FooterNote(probe.getValue(p.id), Modifier.testTag("speech-pick-failed"), color = c.bad)
                testing && !g.manual -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("speech-pick-running"))
            }
        }
        FooterNote(tr(R.string.speech_footer), Modifier.padding(top = 8.dp))
    }
}
