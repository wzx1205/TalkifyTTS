package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.llm.QwenTtsBridge
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.io.File

/**
 * Qwen3-TTS 本地引擎（llama.cpp backbone + mtmd 音频管线，非 sherpa-onnx）
 *
 * 模型目录内两个 GGUF：主干（qwen3tts 架构 LLM）+ mmproj（说话人编码 + 12Hz
 * codec + 声码器）。音色由参考音频零样本克隆（与 ZipVoice 同构），但**不需要
 * 逐字稿**——llama.cpp 侧只喂 24kHz 参考音频，说话人嵌入由 mmproj 内的
 * ECAPA 编码器提取。多角色听书的按句换声 = 每句换参考音频，语义与 ZipVoice 一致。
 *
 * 与 sherpa 引擎的差异：
 * - 语速（speed）暂不支持原生调节，入参被忽略（日志提示一次）；
 *   情感语速由上游停顿/其他引擎承担
 * - 采样率由 mmproj 决定（24kHz）
 */
class QwenTtsEngine(
    private val modelInfo: LocalModelInfo,
    private val modelDir: File,
    private val useGpu: Boolean = false,
    /** llama.cpp 推理线程数；8 Elite 实测 4 线程与 8 线程差距需实测决定默认值 */
    private val nThreads: Int = 4
) : LocalTtsEngine {

    private val tag = "QwenTtsEngine[${modelInfo.id}]"

    @Volatile
    private var session: QwenTtsBridge.Session? = null

    @Volatile
    private var isReleased = false

    private var speedWarned = false

    override fun getModelId(): String = modelInfo.id

    @Synchronized
    fun initialize() {
        if (session != null || isReleased) return

        val backbone = modelInfo.requiredLocalFiles.firstOrNull { !it.startsWith("mmproj") }
            ?: throw IllegalStateException("模型 ${modelInfo.id} 未声明主干 GGUF")
        val mmproj = modelInfo.requiredLocalFiles.firstOrNull { it.startsWith("mmproj") }
            ?: throw IllegalStateException("模型 ${modelInfo.id} 未声明 mmproj GGUF")

        for (name in listOf(backbone, mmproj)) {
            val f = File(modelDir, name)
            if (!f.exists() || f.length() <= 0) {
                throw IllegalStateException("Model file missing or empty: ${f.absolutePath}")
            }
        }

        TtsLogger.i("Initializing Qwen3-TTS engine: ${modelInfo.id}", tag = tag)
        // NPU 声码器(可选):模型目录或内部 files/qnn/ 下的 voc_htp.ctx 存在才启用,
        // 初始化失败 native 层自动回退 ggml 声码器
        val vocoderCtx = listOf(
            File(modelDir, "voc_htp.ctx"),
            TalkifyAppHolder.getContext()?.let { File(it.filesDir, "qnn/voc_htp.ctx") }
        ).firstOrNull { it != null && it.isFile && it.length() > 0 }
        val nativeDir = TalkifyAppHolder.getContext()?.applicationInfo?.nativeLibraryDir
        val s = QwenTtsBridge.open(
            backbonePath = File(modelDir, backbone).absolutePath,
            mmprojPath = File(modelDir, mmproj).absolutePath,
            nCtx = 2048,
            nThreads = nThreads,
            useGpu = useGpu,
            vocoderCtxPath = vocoderCtx?.absolutePath,
            nativeDir = nativeDir
        ) ?: throw IllegalStateException(
            "Qwen3-TTS 引擎初始化失败（native 层加载 backbone/mmproj 失败）"
        )
        session = s
        TtsLogger.i("Qwen3-TTS engine initialized: ${modelInfo.id}", tag = tag)
    }

    override fun synthesizeStream(
        text: String,
        referenceAudio: FloatArray,
        referenceSampleRate: Int,
        referenceText: String,
        speed: Float,
        speakerId: Int,
        onAudioChunk: (pcm16Data: ByteArray, sampleRate: Int) -> Boolean
    ): Boolean {
        if (isReleased) throw IllegalStateException("Engine has been released")
        if (session == null) initialize()

        val s = session ?: throw IllegalStateException("Qwen3-TTS engine not initialized")

        if (!speedWarned && speed != 1.0f) {
            speedWarned = true
            TtsLogger.w("Qwen3-TTS 不支持原生语速调节，忽略 speed=$speed", tag = tag)
        }

        // mmproj 的 mel 前端固定 24kHz；参考音频不是 24k 时线性重采样
        val speakerPcm = referenceAudio.takeIf { it.isNotEmpty() }?.let {
            if (referenceSampleRate == 24000) it else resampleLinear(it, referenceSampleRate, 24000)
        }

        // 帧率 12.5fps：1 帧约对应 6 个汉字（300ms/字）。按文本长度放大上限并留余量
        val maxFrames = (text.length * 4 + 60).coerceAtMost(1500)

        var completed = true
        val rate = s.synthesize(
            text = text,
            lang = detectLang(text),
            speakerPcm = speakerPcm,
            speakerSampleRate = 24000,
            onPcm = QwenTtsBridge.PcmCallback { pcm, sampleRate ->
                val keep = onAudioChunk(pcm, sampleRate)
                if (!keep) completed = false
                keep
            },
            maxFrames = maxFrames
        )
        if (rate < 0) {
            throw IllegalStateException("Qwen3-TTS 合成失败（native 返回 $rate）")
        }
        return completed
    }

    @Synchronized
    override fun release() {
        if (isReleased) return
        isReleased = true
        try {
            session?.close()
            TtsLogger.i("Qwen3-TTS engine released: ${modelInfo.id}", tag = tag)
        } catch (e: Exception) {
            TtsLogger.e("Error releasing Qwen3-TTS engine", throwable = e, tag = tag)
        } finally {
            session = null
        }
    }

    // ---- 内部 ----

    /** 中英之外的语言交给模型自动处理，这里只做粗粒度中/英分流 */
    private fun detectLang(text: String): String {
        var cjk = 0
        var ascii = 0
        for (c in text) {
            when {
                c.code >= 0x4E00 && c.code <= 0x9FFF -> cjk++
                c.isLetter() -> ascii++
            }
        }
        return if (ascii > cjk * 3) "en" else "zh"
    }

    /** 简单线性插值重采样（参考音频只需粗保真，克隆质量对此不敏感） */
    private fun resampleLinear(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || src.isEmpty()) return src
        val outLen = (src.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] * (1 - frac) + src[i1] * frac
        }
        return out
    }
}
