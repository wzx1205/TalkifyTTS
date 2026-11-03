package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import android.content.Context
import com.github.lonepheasantwarrior.talkify.domain.model.XiaobuConfig

/**
 * 小布助手（离线）供应商 - 配置仓储实现
 *
 * 字段读写由 [BasePrefsConfigRepository] 统一提供，此处仅声明字段映射。
 */
class XiaobuConfigRepository(
    context: Context
) : BasePrefsConfigRepository<XiaobuConfig>(context, XiaobuConfig::class.java) {

    override fun serialize(config: XiaobuConfig): Map<String, String> = mapOf(
        KEY_VOICE_ID to config.voiceId
    )

    override fun deserialize(values: Map<String, String>): XiaobuConfig = XiaobuConfig(
        voiceId = values[KEY_VOICE_ID] ?: ""
    )

    private companion object {
        const val KEY_VOICE_ID = "voice_id"
    }
}
