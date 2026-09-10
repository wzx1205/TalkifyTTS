package com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo

import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelArchitecture
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalVoiceCatalog

/**
 * 本地模型供应商 - 音色仓储实现
 *
 * 音色来源按模型架构区分：
 * - ZipVoice：内置参考音频音色目录（res/xml/local_model_voices.xml，音频随 APK 分发）
 * - VITS/MeloTTS：模型自带 speaker 表，与参考音频目录无关，不能混用
 * 目录异常为空时回退 [LocalModelRegistry] 注册表音色。
 */
class LocalModelVoiceRepository(
    private val configRepository: LocalModelConfigRepository
) : VoiceRepository {

    override suspend fun getVoicesForProvider(provider: TtsProvider): List<VoiceInfo> {
        if (provider.id != ProviderIds.LocalModel.providerId) return emptyList()

        // 读取用户当前选择的模型 ID
        val config = configRepository.getConfig(ProviderIds.LocalModel.providerId)
        val modelId = config.modelId.ifBlank {
            ProviderIds.LocalModel.defaultModelId
        }

        // 从注册表获取该模型的元信息（采样率等）
        val modelInfo = LocalModelRegistry.getModel(modelId)
            ?: return emptyList()

        return voicesForModel(modelInfo)
    }

    companion object {
        /**
         * 模型元信息 → 音色列表
         *
         * 供两处复用：本仓储（读已保存配置）与设置弹窗（读下拉里**尚未保存**的
         * 选中模型——弹窗内切换模型时音色表必须立即跟随，不能等保存）。
         */
        fun voicesForModel(modelInfo: com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo): List<VoiceInfo> {
            val voices = if (modelInfo.architecture == LocalModelArchitecture.MELO_VITS) {
                modelInfo.voiceList
            } else {
                LocalVoiceCatalog.getVoices().ifEmpty { modelInfo.voiceList }
            }
            return voices.map { voice ->
                VoiceInfo(
                    voiceId = voice.voiceId,
                    displayName = "${voice.displayName} (${voice.language.uppercase()})",
                    sampleRate = modelInfo.sampleRate
                )
            }
        }
    }
}
