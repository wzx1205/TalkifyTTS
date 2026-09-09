package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.HybridConfig

/**
 * 混合引擎配置仓储
 *
 * @property apiKey MiMo key 留空时运行时自动回退小米供应商已存 key，
 *                  再空则旁白走 Edge 免费音色
 */
class HybridConfigRepository(
    context: Context
) : BasePrefsConfigRepository<HybridConfig>(context, HybridConfig::class.java) {

    override fun serialize(config: HybridConfig): Map<String, String> = mapOf(
        KEY_VOICE_ID to config.voiceId,
        KEY_API_URL to config.apiUrl,
        KEY_MODEL_ID to config.modelId,
        KEY_API_KEY to config.apiKey
    )

    override fun deserialize(values: Map<String, String>): HybridConfig = HybridConfig(
        voiceId = values[KEY_VOICE_ID] ?: "冰糖",
        apiUrl = values[KEY_API_URL] ?: "",
        modelId = values[KEY_MODEL_ID] ?: "",
        apiKey = values[KEY_API_KEY] ?: ""
    )

    private companion object {
        const val KEY_VOICE_ID = "voice_id"
        const val KEY_API_URL = "api_url"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_API_KEY = "api_key"
    }
}
