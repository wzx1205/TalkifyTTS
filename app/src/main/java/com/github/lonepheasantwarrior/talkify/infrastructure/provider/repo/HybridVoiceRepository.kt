package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.infrastructure.xml.VoiceXmlEntry

/**
 * 混合引擎音色仓储
 *
 * MiMo 旁白声线 + Edge 免费音色同列，角色册绑定时按前缀路由引擎
 * （zh-CN-* → Edge，其余 → MiMo）。
 */
class HybridVoiceRepository(
    context: Context
) : BaseXmlVoiceRepository(
    context = context,
    xmlResId = R.xml.hybrid_voices,
    expectedProviderId = ProviderIds.Hybrid.providerId
) {
    override fun VoiceXmlEntry.toVoiceInfo(): VoiceInfo =
        VoiceInfo(voiceId = id, displayName = displayName)
}
