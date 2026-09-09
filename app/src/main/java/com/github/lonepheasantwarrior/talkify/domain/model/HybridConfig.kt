package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 混合引擎配置
 *
 * @property voiceId 旁白声线（MiMo 音色名，如「冰糖」；Edge 音色请勿填在此处，
 *                   Edge 音色用于角色，在角色册中绑定）
 * @property apiKey 小米 MiMo 的 API Key（旁白合成用）。留空时自动复用
 *                  「小米」供应商配置里已填的 key；两者都为空时旁白回退
 *                  Edge 免费音色（云野），角色部分始终免费
 */
data class HybridConfig(
    override val voiceId: String = "冰糖",
    override val apiUrl: String = "",
    override val modelId: String = "",
    val apiKey: String = ""
) : BaseProviderConfig(voiceId, apiUrl, modelId)
