package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

/**
 * 本地 TTS 引擎统一接口
 *
 * [SherpaTtsEngine]（sherpa-onnx：ZipVoice / MeloTTS）与
 * [QwenTtsEngine]（llama.cpp + mtmd：Qwen3-TTS）共同实现，
 * [com.github.lonepheasantwarrior.talkify.service.provider.impl.LocalModelProvider]
 * 按模型架构选择实现，上层合成流水线无感知。
 */
interface LocalTtsEngine {

    fun getModelId(): String

    /**
     * 流式合成一段文本。
     *
     * @param onAudioChunk 音频回调：(pcm16Data, sampleRate) → Boolean，返回 false 中断
     * @return true 正常完成，false 被回调中断
     */
    fun synthesizeStream(
        text: String,
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String,
        speed: Float,
        speakerId: Int = 0,
        onAudioChunk: (pcm16Data: ByteArray, sampleRate: Int) -> Boolean
    ): Boolean

    fun release()
}
