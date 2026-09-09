package com.github.lonepheasantwarrior.talkify.service.provider.impl

import android.util.Base64
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.XiaomiConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.HttpStreamingTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.toMaskedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.lonepheasantwarrior.talkify.service.provider.TtsErrorMessages

/**
 * 小米 - MiMo 语音合成供应商实现
 *
 * 继承 [HttpStreamingTtsProvider]，基于 OkHttp 实现 HTTP SSE 流式音频合成。
 * 分块调度、取消、错误分类等通用逻辑由基类提供。
 *
 * 供应商 ID：xiaomi
 * 供应商：小米
 * API 模型：mimo-v2.5-tts (MiMo Speech Synthesis v2.5)
 * API 文档：https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5
 */
class XiaomiProvider : HttpStreamingTtsProvider() {

    companion object {
        const val DEFAULT_API_URL = "https://api.xiaomimimo.com/v1/chat/completions"

        /** 多角色槽位 → MiMo 预置音色（旁白可被角色册旁白绑定覆盖） */
        private val SLOT_VOICES = mapOf(
            "narrator" to "冰糖",
            "male" to "苏打",
            "female" to "茉莉",
            "male2" to "白桦",
            "female2" to "冰糖"
        )

        /** 相邻对白防撞的备用声线（MiMo 中文预置男女各只有 2 个） */
        private val ALTERNATE_VOICES = mapOf(
            "苏打" to "白桦",
            "白桦" to "苏打",
            "茉莉" to "冰糖",
            "冰糖" to "茉莉"
        )

        /** 情感标签 → MiMo 风格指令（user role 自然语言） */
        private val EMOTION_STYLES = mapOf(
            "CALM" to "",
            "JOY" to "用欢快喜悦、明亮的语气",
            "ANGER" to "用愤怒严厉、压抑着怒火的语气",
            "SADNESS" to "用悲伤低沉、带一点哽咽的语气",
            "FEAR" to "用恐惧颤抖、气声很重的语气",
            "SURPRISE" to "用惊讶上扬、难以置信的语气"
        )

        /** 多角色开关全局唯一；启用后逐句合成 */
        private val bookModeEnabled: Boolean
            get() = com.github.lonepheasantwarrior.talkify.book.config.BookTtsSettings.isEnabled()
    }

    /** 多角色逐句合成用的独立客户端（基类连接池为私有） */
    private val bookClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override val chunkMaxLength: Int = 768

    override val configLabels: Map<String, Int> = mapOf(
        "api_key" to R.string.api_key_label,
        "style_instruction" to R.string.label_style_instruction
    )

    override val supportedLanguages: Array<String> = arrayOf("zho", "eng")

    override val fallbackVoiceId: String = "mimo_default"

    override val voiceIds: List<String> by lazy {
        loadVoiceIdsFromXml(R.xml.xiaomi_mimo_voices_v2p5)
    }

    override fun getProviderId(): String = ProviderIds.Xiaomi.providerId

    override fun getProviderName(): String = ProviderIds.Xiaomi.provider

    override fun getDefaultApiUrl(): String = DEFAULT_API_URL

    override fun getDefaultModelId(): String = ProviderIds.Xiaomi.defaultModelId

    override fun getAudioConfig(): AudioConfig = AudioConfig.XIAOMI_MIMO_TTS

    /**
     * 多角色听书：开关开启且文本含对白时，按句分析 → 角色槽位/角色册绑定
     * 选音色 → 情感标签转风格指令（user role），逐句请求 MiMo。
     * 其余情况走基类分块流水线（单音色）。
     */
    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    ) {
        if (!bookModeEnabled || config !is XiaomiConfig) {
            super.synthesize(text, params, config, listener)
            return
        }
        val utterances = try {
            com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer.analyze(text)
        } catch (e: Exception) {
            logWarning("Dialogue analysis failed, fallback single voice: ${e.message}")
            emptyList()
        }
        if (utterances.isEmpty()) {
            super.synthesize(text, params, config, listener)
            return
        }

        logInfo("Book multi-role over MiMo: ${utterances.size} utterances")
        isCancelled = false

        providerScope.launch {
            try {
                for ((index, u) in utterances.withIndex()) {
                    if (isCancelled) return@launch
                    // 逐句窗口线索缺失时，用角色册全书投票性别补正槽位
                    val genderHint = if (u.gender == com.github.lonepheasantwarrior.talkify.book.model.Gender.UNKNOWN) {
                        com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore.activeGenderFor(u.speaker)
                    } else null
                    val slot = com.github.lonepheasantwarrior.talkify.book.router.RoleVoiceRouter.slotFor(u, genderHint)
                    var voice = resolveBookVoice(u, slot)
                    // 相邻对白防撞：不同角色连续对话时换备用声线（苏打↔白桦/茉莉↔冰糖）
                    if (com.github.lonepheasantwarrior.talkify.book.router.RoleVoiceRouter
                            .collidesWithPrevious(u.speaker, voice)
                    ) {
                        voice = ALTERNATE_VOICES[voice] ?: voice
                    }
                    com.github.lonepheasantwarrior.talkify.book.router.RoleVoiceRouter
                        .registerSpoken(u.speaker, voice)
                    val style = buildStyleInstruction(u)
                    val utteranceConfig = config.copy(voiceId = voice, styleInstruction = style)
                    logDebug(
                        "Book utterance #$index speaker=${u.speaker} slot=$slot voice=$voice " +
                            "emotion=${u.emotion} text=${u.text.take(16)}"
                    )

                    val request = buildHttpRequest(u.text, utteranceConfig, params)
                    val ok = bookClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: ""
                            logError("HTTP ${resp.code}: $errorBody")
                            withContext(Dispatchers.Main) {
                                listener.onError(mapHttpError(errorBody))
                            }
                            false
                        } else {
                            processStreamResponse(resp, index, utteranceConfig, params, listener)
                        }
                    }
                    if (!ok || isCancelled) return@launch
                }
                if (!isCancelled) {
                    withContext(Dispatchers.Main) {
                        listener.onSynthesisCompleted()
                    }
                }
            } catch (e: Exception) {
                if (!isCancelled && e !is kotlinx.coroutines.CancellationException) {
                    logError("Book synthesis error", e)
                    withContext(Dispatchers.Main) {
                        listener.onError(TtsErrorMessages.synthesisFailed())
                    }
                }
            }
        }
    }

    /** 旁白/角色 → MiMo 音色：角色册绑定（属于 MiMo 音色表时）优先，槽位默认兜底 */
    private fun resolveBookVoice(
        u: com.github.lonepheasantwarrior.talkify.book.model.Utterance,
        slot: String
    ): String {
        if (!u.isQuote || u.speaker == com.github.lonepheasantwarrior.talkify.book.model.Utterance.SPEAKER_NARRATOR) {
            return com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore
                .activeNarratorVoice()?.takeIf { it in voiceIds }
                ?: SLOT_VOICES[slot] ?: fallbackVoiceId
        }
        return com.github.lonepheasantwarrior.talkify.book.store.CharacterBookStore
            .activeVoiceFor(u.speaker)?.takeIf { it in voiceIds }
            ?: SLOT_VOICES[slot] ?: fallbackVoiceId
    }

    /** 情感标签 → MiMo 风格指令；平静/旁白不加指令，保持原生听感 */
    private fun buildStyleInstruction(
        u: com.github.lonepheasantwarrior.talkify.book.model.Utterance
    ): String = EMOTION_STYLES[u.emotion.name].orEmpty()

    override fun validateConfig(config: BaseProviderConfig): String? {
        if (config !is XiaomiConfig) {
            return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED)
        }
        if (config.apiKey.isEmpty()) {
            return TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_PROVIDER_NOT_CONFIGURED)
        }
        return null
    }

    override fun buildHttpRequest(
        text: String,
        config: BaseProviderConfig,
        params: SynthesisParams
    ): Request {
        val mimoConfig = config as XiaomiConfig
        val voiceId = if (mimoConfig.voiceId.isNotEmpty()) {
            extractRealVoiceName(mimoConfig.voiceId) ?: mimoConfig.voiceId
        } else {
            voiceIds.firstOrNull() ?: fallbackVoiceId
        }

        val effectiveVoice = resolveVoiceForLanguage(voiceId, params.language)

        // 构建请求体 - 小米 MiMo v2.5 Speech Synthesis 格式
        // v2.5 API 支持 user role 用于风格指令（自然语言描述朗读风格、语气等）
        val effectiveModel = mimoConfig.modelId.ifBlank { getDefaultModelId() }
        val requestBody = JSONObject().apply {
            put("model", effectiveModel)
            put("messages", JSONArray().apply {
                // user role: 可选的风格指令（v2.5 新特性）
                if (mimoConfig.styleInstruction.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", mimoConfig.styleInstruction)
                    })
                }
                // assistant role: 实际合成文本（必需，v2.5 API 当前仅支持一个 assistant 消息）
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", JSONObject().apply {
                put("format", "pcm16")
                put("voice", effectiveVoice)
            })
            put("stream", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(mimoConfig.apiUrl.ifBlank { DEFAULT_API_URL })
            .post(body)
            .header("api-key", mimoConfig.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Connection", "keep-alive")
            .build()

        // 打印请求详情（Headers 脱敏处理仅用于日志显示，实际发送的是原始值）
        logDebug("HTTP Request URL: ${request.url}")
        logDebug("HTTP Request Headers (masked for log): ${request.headers.toMaskedString()}")
        logDebug("HTTP Request Body: ${requestBody.toString(2)}")

        return request
    }

    /**
     * 处理流式响应（SSE 格式：`data: {...}` 行）
     */
    override suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        config: BaseProviderConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean {
        val body = response.body
        if (body == null) {
            logError("Response body is null")
            return false
        }

        var hasError = false

        try {
            body.source().use { source ->
                while (!source.exhausted() && !isCancelled) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue

                    // SSE 格式: data: {...}
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank()) continue

                    // [DONE] 表示流结束
                    if (data == "[DONE]") {
                        logDebug("Stream completed for chunk $chunkIndex")
                        break
                    }

                    try {
                        val json = JSONObject(data)

                        // 检查是否有错误
                        if (json.has("error")) {
                            val errorObj = json.getJSONObject("error")
                            val errMsg = errorObj.optString("message", "Unknown error")
                            logError("API error: $errMsg")
                            hasError = true
                            withContext(Dispatchers.Main) {
                                listener.onError(errMsg)
                            }
                            break
                        }

                        // 提取音频数据
                        // SSE 格式中，音频数据在 choices[0].delta.audio.data 或 audio 字段中
                        val audioData = extractAudioFromSSE(json)
                        if (audioData != null && audioData.isNotEmpty()) {
                            emitAudio(audioData, listener)
                            logDebug("Received audio data: ${audioData.size} bytes")
                        }

                    } catch (e: Exception) {
                        logError("Failed to parse SSE data: $data", e)
                        // 继续处理下一行，不中断
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error reading response stream", e)
            hasError = true
        }

        return !hasError
    }

    /**
     * 从 SSE JSON 数据中提取音频数据
     * 格式参考 Python SDK: delta.audio["data"]
     */
    private fun extractAudioFromSSE(json: JSONObject): ByteArray? {
        return try {
            // 方式1: choices[0].delta.audio.data (base64 encoded) - 与 Python SDK 一致
            if (json.has("choices")) {
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    if (choice.has("delta")) {
                        val delta = choice.getJSONObject("delta")
                        if (delta.has("audio")) {
                            val audioObj = delta.get("audio")
                            if (audioObj is JSONObject) {
                                val audioData = audioObj.optString("data")
                                if (audioData.isNotBlank()) {
                                    return Base64.decode(audioData, Base64.DEFAULT)
                                }
                            }
                        }
                    }
                }
            }

            // 方式2: audio 字段直接包含 base64 数据
            if (json.has("audio")) {
                val audioObj = json.get("audio")
                if (audioObj is String) {
                    return Base64.decode(audioObj, Base64.DEFAULT)
                } else if (audioObj is JSONObject) {
                    val audioData = audioObj.optString("data")
                    if (audioData.isNotBlank()) {
                        return Base64.decode(audioData, Base64.DEFAULT)
                    }
                }
            }

            null
        } catch (e: Exception) {
            logError("Failed to extract audio data", e)
            null
        }
    }

    /**
     * 根据语言解析合适的声音
     *
     * 特别注意 [mimo_default]：其音色「因部署集群而异，中国集群默认为冰糖，其他集群默认为 Mia」。
     * 若请求被路由到非中国集群，中文朗读会变成 Mia（台湾腔/怪腔调）。
     * 因此当选择 [mimo_default] 或未指定音色时，按目标语言显式解析为具体中文/英文音色，
     * 避免集群差异导致中文发音异常。
     */
    private fun resolveVoiceForLanguage(voiceId: String, language: String?): String {
        // mimo_default 或未指定音色时，按语言显式指定音色，规避集群路由差异
        if (voiceId.isBlank() || voiceId == "mimo_default") {
            return when (language?.lowercase()) {
                "zh", "zho", "chi", "cn" -> "冰糖"
                "en", "eng" -> "Mia"
                else -> "冰糖"
            }
        }

        return voiceId
    }

    override fun mapHttpError(errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            val message = json.optString("error", "")
            if (message.isNotBlank()) {
                return message
            }
            // 尝试从 detail 或 message 获取
            json.optString(
                "detail",
                json.optString("error", TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
            )
        } catch (_: Exception) {
            TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
        }
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        return isConfiguredAs(config) { c: XiaomiConfig -> c.apiKey.isNotBlank() }
    }

    override fun createDefaultConfig(): BaseProviderConfig {
        return XiaomiConfig()
    }

    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "style_instruction" -> context.getString(R.string.label_style_instruction)
            else -> super.getConfigLabel(configKey, context)
        }
    }
}
