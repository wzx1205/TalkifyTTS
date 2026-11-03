package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 小布助手（离线）供应商配置
 *
 * 引擎为系统自带（com.oplus.ttsaccessibilityengine），无 API Key / API 地址 / 模型概念，
 * 仅需选择音色。
 *
 * @property voiceId 声音 ID，如 "xiaobu_wenrou"、"xiaobu_minglang" 等
 *                   （完整列表见 res/xml/xiaobu_voices.xml）
 */
data class XiaobuConfig(
    override val voiceId: String = ""
) : BaseProviderConfig(voiceId)
