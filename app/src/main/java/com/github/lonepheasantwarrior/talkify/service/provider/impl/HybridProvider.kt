package com.github.lonepheasantwarrior.talkify.service.provider.impl

import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.book.config.BookTtsSettings
import com.github.lonepheasantwarrior.talkify.book.model.Gender
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer
import com.github.lonepheasantwarrior.talkify.book.router.RoleVoiceRouter
import com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore
import com.github.lonepheasantwarrior.talkify.domain.model.AzureConfig
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.XiaomiConfig
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.repo.XiaomiConfigRepository
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.service.provider.AbstractTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.book.model.EmotionTag
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 混合引擎：MiMo 念旁白 + Edge 免费音色念角色
 *
 * 多角色听书开启时逐句编排：
 * - 旁白 → 小米 MiMo（冰糖，稳定有质感的解说声；有 MiMo key 时启用）
 * - 对白 → Edge 神经音色（免费、多个中文音色，按角色册绑定或性别槽位分配，
 *          彻底解决双男/双女对手戏撞声线；晓双童声适合小孩角色）
 * - 情感 → 旁白随 MiMo 风格指令；对白随语速微调（Edge express-as 在免费
 *          端点不被保证，不作主要情感手段）
 *
 * 非听书文本（预览等）整段走 MiMo；无 MiMo key 时旁白回退 Edge 云野。
 */
class HybridProvider : AbstractTtsProvider() {

    companion object {
        /** 角色 → Edge 音色槽位默认（zh-CN 稳定可用集合） */
        private val SLOT_VOICES = mapOf(
            "male" to "zh-CN-YunxiNeural",
            "male2" to "zh-CN-YunjianNeural",
            "female" to "zh-CN-XiaoyiNeural",
            "female2" to "zh-CN-XiaoxiaoNeural"
        )

        /** 无 MiMo key 时的旁白兜底音色 */
        private const val NARRATOR_FALLBACK = "zh-CN-YunyangNeural"

        /** MiMo 默认旁白音色 */
        private const val MIMO_NARRATOR = "冰糖"

        /** Edge 失败时 MiMo 兜底声线（按槽位） */
        private val MIMO_SLOT_FALLBACK = mapOf(
            "male" to "苏打",
            "male2" to "白桦",
            "female" to "茉莉",
            "female2" to "冰糖",
            "narrator" to "冰糖"
        )

        private const val UTTERANCE_TIMEOUT_SECONDS = 90L

        private val EMOTION_STYLES = mapOf(
            "JOY" to "用欢快喜悦、明亮的语气",
            "ANGER" to "用愤怒严厉、压抑着怒火的语气",
            "SADNESS" to "用悲伤低沉、带一点哽咽的语气",
            "FEAR" to "用恐惧颤抖、气声很重的语气",
            "SURPRISE" to "用惊讶上扬、难以置信的语气"
        )
    }

    private val xiaomiProvider by lazy { XiaomiProvider() }
    private val azureProvider by lazy { AzureProvider() }

    private val xiaomiConfig: XiaomiConfig? by lazy {
        runCatching {
            val ctx = TalkifyAppHolder.getContext() ?: return@runCatching null
            XiaomiConfigRepository(ctx).getConfig(ProviderIds.Xiaomi.providerId) as? XiaomiConfig
        }.getOrNull()
    }

    private val hasMimoKey: Boolean
        get() = !xiaomiConfig?.apiKey.isNullOrBlank()

    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isCancelled = false

    override val tag: String = "HybridProvider"

    override val voiceIds: List<String> by lazy {
        loadVoiceIdsFromXml(R.xml.hybrid_voices)
    }

    override val fallbackVoiceId: String = "zh-CN-XiaoxiaoNeural"

    override val supportedLanguages: Array<String> = arrayOf("zho", "eng")

    override val configLabels: Map<String, Int> = mapOf(
        "voice_id" to R.string.voice_select_label
    )

    override fun getProviderId(): String = ProviderIds.Hybrid.providerId

    override fun getProviderName(): String = ProviderIds.Hybrid.provider

    override fun getDefaultApiUrl(): String = ""

    override fun getDefaultModelId(): String = ProviderIds.Hybrid.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.createStandard(sampleRate = 24000)

    override fun isConfigured(config: BaseProviderConfig?): Boolean = true

    override fun createDefaultConfig(): BaseProviderConfig =
        com.github.lonepheasantwarrior.talkify.domain.model.HybridConfig()

    override fun stop() {
        isCancelled = true
        xiaomiProvider.stop()
        azureProvider.stop()
    }

    override fun release() {
        isCancelled = true
        providerScope.cancel()
        xiaomiProvider.release()
        azureProvider.release()
        super.release()
    }

    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()
        if (text.isEmpty()) {
            listener.onSynthesisCompleted()
            return
        }

        val hybridConfig = config as? com.github.lonepheasantwarrior.talkify.domain.model.HybridConfig
            ?: com.github.lonepheasantwarrior.talkify.domain.model.HybridConfig()

        val bookMode = BookTtsSettings.isEnabled()
        val utterances = if (bookMode) {
            try {
                DialogueAnalyzer.analyze(text)
            } catch (e: Exception) {
                logWarning("Dialogue analysis failed: ${e.message}")
                emptyList()
            }
        } else emptyList()

        isCancelled = false

        if (utterances.isEmpty()) {
            // 整段无对白：旁白引擎一条龙
            providerScope.launch {
                val ok = runBuffered(text, mimoConfig(hybridConfig, null, null), xiaomiProvider, listener, 1f)
                if (ok && !isCancelled) {
                    withContext(Dispatchers.Main) { listener.onSynthesisCompleted() }
                }
            }
            return
        }

        logInfo("Hybrid book: ${utterances.size} utterances (旁白→MiMo, 角色→Edge)")
        providerScope.launch {
            try {
                for ((index, u) in utterances.withIndex()) {
                    if (isCancelled) return@launch
                    val isNarration = !u.isQuote || u.speaker == Utterance.SPEAKER_NARRATOR
                    val genderHint = if (u.gender == Gender.UNKNOWN) {
                        CharacterBookStore.activeGenderFor(u.speaker)
                    } else null
                    val slot = RoleVoiceRouter.slotFor(u, genderHint)
                    val plan = RoleVoiceRouter.resolve(u, null)

                    val ok = if (isNarration) {
                        val cfg = mimoConfig(hybridConfig, slotVoiceNarrator(), u.emotion)
                        logDebug("#$index 旁白→MiMo text=${u.text.take(16)}")
                        // 双引擎互为备胎：主引擎失败重试一次，仍失败切另一引擎，永不断流
                        val primaryError = runWithRetry(u.text, cfg, xiaomiProvider, listener, plan.speedMultiplier)
                        if (primaryError == null) true else {
                            logWarning("MiMo narration failed, fallback Edge narrator")
                            runBuffered(
                                u.text,
                                AzureConfig(voiceId = NARRATOR_FALLBACK),
                                azureProvider,
                                listener,
                                plan.speedMultiplier
                            )
                        }
                    } else {
                        var edgeVoice = edgeVoiceFor(u.speaker) ?: SLOT_VOICES[slot] ?: fallbackVoiceId
                        // 相邻对白防撞：不同角色连续对话时在 Edge 同性别池内轮转
                        if (RoleVoiceRouter.collidesWithPrevious(u.speaker, edgeVoice)) {
                            val (femalePool, malePool) =
                                com.github.lonepheasantwarrior.talkify.book.store.VoiceAutoAssign
                                    .poolsFor(com.github.lonepheasantwarrior.talkify.book.store.VoiceAutoAssign.Scheme.EDGE)
                            val pool = if (edgeVoice in femalePool) femalePool else malePool
                            val idx = pool.indexOf(edgeVoice)
                            if (idx >= 0) edgeVoice = pool[(idx + 1) % pool.size]
                        }
                        RoleVoiceRouter.registerSpoken(u.speaker, edgeVoice)
                        logDebug("#$index 角色→Edge speaker=${u.speaker} voice=$edgeVoice emotion=${u.emotion} text=${u.text.take(16)}")
                        val primaryError = runWithRetry(
                            u.text,
                            AzureConfig(voiceId = edgeVoice),
                            azureProvider,
                            listener,
                            plan.speedMultiplier
                        )
                        if (primaryError == null) true else {
                            // Edge 失败：切 MiMo 用性别槽位声线念这句，听感不断
                            val mimoVoice = MIMO_SLOT_FALLBACK[slot] ?: "苏打"
                            logWarning("Edge failed for ${u.speaker}, fallback MiMo voice=$mimoVoice")
                            runBuffered(
                                u.text,
                                mimoConfig(hybridConfig, null, u.emotion).copy(voiceId = mimoVoice),
                                xiaomiProvider,
                                listener,
                                plan.speedMultiplier
                            )
                        }
                    }
                    if (!ok || isCancelled) return@launch
                }
                if (!isCancelled) {
                    withContext(Dispatchers.Main) { listener.onSynthesisCompleted() }
                }
            } catch (e: Exception) {
                if (!isCancelled && e !is kotlinx.coroutines.CancellationException) {
                    logError("Hybrid synthesis error", e)
                    withContext(Dispatchers.Main) {
                        listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
                    }
                }
            }
        }
    }

    /**
     * MiMo 旁白配置
     *
     * Key 优先级：混合配置里独立填的 [HybridConfig.apiKey] → 「小米」供应商保存的 key
     * （混合模式没有自己的 key 时也能开箱即用，前提是小米供应商配置过）。
     *
     * 旁白音色绑定只接受 MiMo 系音色：角色册旁白若绑了 Edge / 本地音色，
     * 透传给 MiMo 会直接 4xx，触发回落到 Edge 云野——那正是"旁白冰糖没生效"
     * 表面上听起来的原因之一，所以必须在源头挡掉，回落到混合自己的默认旁白。
     */
    private fun mimoConfig(
        hybrid: com.github.lonepheasantwarrior.talkify.domain.model.HybridConfig,
        narratorBinding: String?,
        emotion: EmotionTag?
    ): XiaomiConfig {
        val base = xiaomiConfig ?: XiaomiConfig()
        val effectiveKey = hybrid.apiKey.ifBlank { base.apiKey }
        val narrator = when {
            narratorBinding != null &&
                com.github.lonepheasantwarrior.talkify.book.store.VoiceAutoAssign.schemeOf(narratorBinding) ==
                com.github.lonepheasantwarrior.talkify.book.store.VoiceAutoAssign.Scheme.MIMO -> narratorBinding
            hybrid.voiceId.isNotBlank() -> hybrid.voiceId
            else -> MIMO_NARRATOR
        }
        val style = emotion?.let { EMOTION_STYLES[it.name] }.orEmpty()
        return base.copy(apiKey = effectiveKey, voiceId = narrator, styleInstruction = style)
    }

    private fun slotVoiceNarrator(): String? = CharacterBookStore.activeNarratorVoice()

    /** 角色册若绑定了 Edge 音色（zh-CN- 前缀），优先精确使用 */
    private fun edgeVoiceFor(speaker: String): String? =
        CharacterBookStore.activeVoiceFor(speaker)?.takeIf { it.startsWith("zh-CN-") }

    /** 失败重试一次的合成（Edge/WSS 间歇性拒绝的兜底） */
    private suspend fun runWithRetry(
        text: String,
        config: BaseProviderConfig,
        engine: AbstractTtsProvider,
        listener: TtsSynthesisListener,
        speedMultiplier: Float
    ): String? {
        val first = runBuffered(text, config, engine, listener, speedMultiplier, reportErrors = false)
        if (first || isCancelled) return null
        logWarning("Retry once after failure: ${config.voiceId}")
        val second = runBuffered(text, config, engine, listener, speedMultiplier, reportErrors = false)
        if (second || isCancelled) return null
        return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
    }

    /** 阻塞驱动子引擎合成单句，收集音频后按序投递给混合监听器 */
    private suspend fun runBuffered(
        text: String,
        config: BaseProviderConfig,
        engine: AbstractTtsProvider,
        listener: TtsSynthesisListener,
        speedMultiplier: Float,
        reportErrors: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val buffer = ArrayDeque<ByteArray>()
        var sampleRate = getAudioConfig().sampleRate
        var failed: String? = null
        val done = CountDownLatch(1)

        val collector = object : TtsSynthesisListener {
            override fun onSynthesisStarted() {}
            override fun onAudioAvailable(
                audioData: ByteArray,
                sr: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                synchronized(buffer) { buffer.add(audioData) }
                sampleRate = sr
            }

            override fun onSynthesisCompleted() {
                done.countDown()
            }

            override fun onError(error: String) {
                failed = error
                done.countDown()
            }
        }

        val synthParams = SynthesisParams(
            pitch = 100f,
            speechRate = speedMultiplier * 100f,
            volume = 1f
        )
        engine.synthesize(text, synthParams, config, collector)

        val finished = done.await(UTTERANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        when {
            !finished -> {
                engine.stop()
                if (reportErrors) {
                    withContext(Dispatchers.Main) {
                        listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT))
                    }
                }
                false
            }
            failed != null -> {
                if (reportErrors) {
                    withContext(Dispatchers.Main) { listener.onError(failed!!) }
                }
                false
            }
            else -> {
                val chunks = synchronized(buffer) { buffer.toList() }
                withContext(Dispatchers.Main) {
                    listener.onSynthesisStarted()
                    chunks.forEach { chunk ->
                        listener.onAudioAvailable(chunk, sampleRate, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)
                    }
                }
                true
            }
        }
    }
}
