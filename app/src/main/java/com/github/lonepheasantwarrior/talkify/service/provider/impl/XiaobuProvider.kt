package com.github.lonepheasantwarrior.talkify.service.provider.impl

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseProviderConfig
import com.github.lonepheasantwarrior.talkify.domain.model.ProviderIds
import com.github.lonepheasantwarrior.talkify.domain.model.XiaobuConfig
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.provider.AbstractTtsProvider
import com.github.lonepheasantwarrior.talkify.service.provider.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.VOICE_NAME_SEPARATOR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 小布助手（离线）TTS 供应商实现
 *
 * 复用 ColorOS 系统自带的离线 TTS 引擎 [ENGINE_PACKAGE]（VITS + MNN，纯离线，
 * 引擎进程 `:tts`），通过标准 `TextToSpeech` 客户端 API 调用，无需网络与 API Key。
 * 仅 OPPO / 一加 / realme 等搭载 ColorOS 的设备存在该引擎。
 *
 * 引擎内部机制（经反编译 + 真机验证，见 output/jadx_out/ttsae_full）：
 * - 文件合成路径下**发音人不来自 setVoice()**，而是每次合成时读取全局安全设置
 *   [SETTING_TONE]（即系统「小布音色」设置项，整数 speakerId），因此本供应商
 *   在每次合成前写入该设置、完成后恢复原值；
 * - 语速/音高随请求透传（clamp 0.5~2.0），语言按请求语言 zh→zh_CN / 其他→en_US；
 * - 文件合成有 15s 无输出看门狗，长文本需自行分句成短块；
 * - 输出固定 24kHz / 16bit / 单声道 PCM（外层为标准 WAV 容器）。
 *
 * 写入安全设置需要 WRITE_SECURE_SETTINGS（adb `pm grant` 一次性授予），
 * 未授予时回退尝试 root（`su -c settings put ...`，KernelSU/Magisk 设备可用）。
 *
 * 供应商 ID：xiaobu
 */
class XiaobuProvider : AbstractTtsProvider() {

    companion object {
        private const val TAG = "XiaobuProvider"

        /** ColorOS 小布离线 TTS 引擎包名 */
        const val ENGINE_PACKAGE = "com.oplus.ttsaccessibilityengine"

        /** 全局发音人设置键：引擎每次文件合成都从这里读 speakerId */
        private const val SETTING_TONE = "tts_default_selected_tone"

        /**
         * 单块文本最大字符数。
         * 引擎文件合成路径有 15s 无输出看门狗，块太大时首包前若被引擎
         * 正常化/推理拖过 15s 会直接报错，按句级长度保守分块。
         */
        private const val MAX_CHUNK_LENGTH = 200

        /** 短于该长度时不主动在标点处断句 */
        private const val PUNCT_SPLIT_THRESHOLD = 100

        /** 音频回传块大小（字节） */
        private const val EMIT_CHUNK_BYTES = 8192

        /** 单块合成等待上限：15s 看门狗 + 按文本长度放宽 */
        private fun chunkTimeoutMs(text: String): Long = 20_000L + text.length * 300L

        private data class SpeakerDef(val speakerId: Int, val locale: Locale)

        /** voiceId → 发音人。speakerId 与引擎 onGetVoices 的 features 一一对应（真机验证）。 */
        private val SPEAKERS = linkedMapOf(
            "xiaobu_wenrou" to SpeakerDef(0, Locale.SIMPLIFIED_CHINESE),
            "xiaobu_minglang" to SpeakerDef(3, Locale.SIMPLIFIED_CHINESE),
            "xiaobu_qingleng" to SpeakerDef(5, Locale.SIMPLIFIED_CHINESE),
            "xiaobu_en_female" to SpeakerDef(6, Locale.US),
            "xiaobu_en_male" to SpeakerDef(4, Locale.US)
        )

        // ---- 进程级共享引擎 ----
        // Provider 实例会随 TTS 服务 / 设置页音色预览各自创建，引擎连接必须共享，
        // 否则多次绑定会撞上引擎服务的连接数上限（3 个）

        /** 引擎不支持并发合成（引擎侧单线程排队），进程内再串行化一层 */
        private val synthesisMutex = Mutex()

        @Volatile
        private var sharedTts: TextToSpeech? = null

        /** 当前在等完成的合成：utterance 回调经它路由（合成已被 synthesisMutex 串行化） */
        private val currentWait = AtomicReference<ChunkWait?>(null)

        /** 引擎空闲释放：与 LocalModelProvider 不同，引擎在系统进程里，常驻连接代价可忽略 */
        private val providerJob = SupervisorJob()
        private val providerScope = CoroutineScope(Dispatchers.IO + providerJob)

        /** 引擎是否已安装（ColorOS 系统才存在） */
        fun isEngineInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(ENGINE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /**
         * 获取共享引擎实例（懒初始化，30s 超时）
         * @throws IllegalStateException 初始化失败或超时
         */
        private fun ensureEngine(): TextToSpeech {
            sharedTts?.let { return it }
            synchronized(this) {
                sharedTts?.let { return it }
                val context = TalkifyAppHolder.getContext()
                    ?: throw IllegalStateException("Context 不可用")
                val status = AtomicInteger(-1)
                val latch = CountDownLatch(1)
                val tts = TextToSpeech(context, { st ->
                    status.set(st)
                    latch.countDown()
                }, ENGINE_PACKAGE)
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw IllegalStateException("小布引擎初始化超时")
                }
                if (status.get() != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("小布引擎初始化失败: ${status.get()}")
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        currentWait.getAndSet(null)?.finish(null)
                    }

                    override fun onError(utteranceId: String?) {
                        currentWait.getAndSet(null)?.finish("引擎合成出错")
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        currentWait.getAndSet(null)?.finish("引擎合成出错 (code=$errorCode)")
                    }
                })
                sharedTts = tts
                return tts
            }
        }

        /** 读取当前全局发音人（不可读时返回 null） */
        private fun readTone(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, SETTING_TONE)

        /**
         * 写入全局发音人设置。
         * 优先走 WRITE_SECURE_SETTINGS，未授予时回退 root。
         * @return null 表示成功，否则为失败原因
         */
        private fun writeTone(context: Context, speakerId: Int): String? {
            val value = speakerId.toString()
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                val ok = try {
                    Settings.Secure.putString(context.contentResolver, SETTING_TONE, value)
                } catch (e: Exception) {
                    TtsLogger.e("$TAG: Secure putString failed", e)
                    false
                }
                if (ok) return null
            }
            // root 回退：KernelSU / Magisk 授权后可用
            return try {
                val proc = ProcessBuilder("su", "-c", "settings put secure $SETTING_TONE $value")
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit == 0) null else "su exit=$exit ${output.take(120)}"
            } catch (e: Exception) {
                e.message
            }
        }

        /** 尽力恢复原发音人（失败不影响合成结果） */
        private fun restoreTone(context: Context, previous: String?) {
            if (previous == null) return
            try {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    Settings.Secure.putString(context.contentResolver, SETTING_TONE, previous)
                } else {
                    val proc = ProcessBuilder("su", "-c", "settings put secure $SETTING_TONE $previous")
                        .redirectErrorStream(true).start()
                    proc.waitFor()
                }
            } catch (e: Exception) {
                TtsLogger.w("$TAG: 恢复原发音人失败: ${e.message}")
            }
        }

        /**
         * 按句级边界分块：短句尽量合并，达到软阈值后在句末标点处断开，
         * 超过硬阈值强制断开
         */
        private fun splitText(text: String): List<String> {
            if (text.length <= MAX_CHUNK_LENGTH) return listOf(text)
            val enders = "。！？；!?;\n"
            val chunks = mutableListOf<String>()
            val sb = StringBuilder()
            for (c in text) {
                sb.append(c)
                val len = sb.length
                if (len >= MAX_CHUNK_LENGTH || (len >= PUNCT_SPLIT_THRESHOLD && enders.contains(c))) {
                    chunks.add(sb.toString())
                    sb.clear()
                }
            }
            if (sb.isNotEmpty()) chunks.add(sb.toString())
            return chunks
        }

        /** 单块合成等待状态 */
        private class ChunkWait(val latch: CountDownLatch = CountDownLatch(1)) {
            @Volatile
            var error: String? = null
                private set

            fun finish(err: String?) {
                error = err
                latch.countDown()
            }
        }
    }

    @Volatile
    private var isCancelled = false

    private var synthesisJob: Job? = null

    override val supportedLanguages: Array<String> = arrayOf("zho", "eng")

    override val voiceIds: List<String>
        get() = SPEAKERS.keys.toList()

    override val fallbackVoiceId: String = "xiaobu_wenrou"

    override fun getProviderId(): String = ProviderIds.Xiaobu.providerId

    override fun getProviderName(): String = ProviderIds.Xiaobu.provider

    override fun getAudioConfig(): AudioConfig = AudioConfig() // 24kHz / 16bit / mono，与引擎输出一致

    /** 各音色语言不同，音色 × 语言叉乘不适用，直接按引擎实际能力构造 */
    override fun getSupportedVoices(): List<Voice> {
        return SPEAKERS.map { (id, def) ->
            Voice(
                "$id$VOICE_NAME_SEPARATOR${def.locale.language}",
                def.locale,
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                false,
                emptySet()
            )
        }
    }

    override fun isConfigured(config: BaseProviderConfig?): Boolean {
        val context = TalkifyAppHolder.getContext()
        if (context == null || !isEngineInstalled(context)) {
            logWarning("小布引擎未安装（仅 ColorOS 设备可用）")
            return false
        }
        return isConfiguredAs(config) { _: XiaobuConfig -> true }
    }

    override fun createDefaultConfig(): BaseProviderConfig = XiaobuConfig()

    override fun synthesize(
        text: String,
        params: SynthesisParams,
        config: BaseProviderConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val context = TalkifyAppHolder.getContext()
        if (context == null || !isEngineInstalled(context)) {
            logError("小布引擎未安装（仅 ColorOS 设备可用）")
            listener.onError(
                context?.getString(R.string.tts_error_xiaobu_engine_missing)
                    ?: "未检测到小布离线引擎（仅 OPPO/一加/realme 等 ColorOS 设备可用）"
            )
            return
        }
        if (config !is XiaobuConfig) {
            logError("Invalid config type, expected XiaobuConfig")
            listener.onError(context.getString(R.string.tts_error_provider_not_configured))
            return
        }
        if (text.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }
        if (!containsReadableText(text)) {
            logWarning("文本不包含可朗读的文字内容")
            listener.onSynthesisCompleted()
            return
        }

        val speaker = SPEAKERS[extractRealVoiceName(config.voiceId)] ?: SPEAKERS[fallbackVoiceId]!!
        val chunks = splitText(text)
        logInfo("Starting xiaobu synthesis: speaker=${speaker.speakerId}, chunks=${chunks.size}, textLength=${text.length}")

        isCancelled = false
        synthesisJob = providerScope.launch {
            try {
                val engine = ensureEngine()
                var started = false
                synthesisMutex.withLock {
                    val previousTone = readTone(context)
                    try {
                        for (chunk in chunks) {
                            if (isCancelled) break
                            val writeError = writeTone(context, speaker.speakerId)
                            if (writeError != null) {
                                throw IllegalStateException(
                                    context.getString(R.string.tts_error_xiaobu_no_permission) + "\n" + writeError
                                )
                            }
                            engine.setLanguage(speaker.locale)
                            val pcm = synthesizeChunk(engine, chunk) ?: break
                            if (!started) {
                                listener.onSynthesisStarted()
                                started = true
                            }
                            var offset = 0
                            while (offset < pcm.size && !isCancelled) {
                                val len = minOf(EMIT_CHUNK_BYTES, pcm.size - offset)
                                listener.onAudioAvailable(
                                    pcm.copyOfRange(offset, offset + len),
                                    AudioConfig.DEFAULT_SAMPLE_RATE,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                    1
                                )
                                offset += len
                            }
                        }
                    } finally {
                        restoreTone(context, previousTone)
                    }
                }
                if (!isCancelled) {
                    listener.onSynthesisCompleted()
                    logInfo("Xiaobu synthesis completed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCancelled) {
                    logError("Xiaobu synthesis error", e)
                    listener.onError(context.getString(R.string.tts_error_xiaobu_synthesis_failed) + ": ${e.message}")
                }
            }
        }
    }

    /**
     * 单块合成：写文件 → 解析 WAV data 块 → 返回 PCM。
     * 返回 null 表示因 stop() 取消，静默结束。
     */
    private suspend fun synthesizeChunk(engine: TextToSpeech, chunk: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val context = TalkifyAppHolder.getContext()
                ?: throw IllegalStateException("Context 不可用")
            val dir = File(context.cacheDir, "xiaobu_tts").apply { mkdirs() }
            val outFile = File(dir, "u_${System.nanoTime()}.wav")
            try {
                val wait = ChunkWait().also { currentWait.set(it) }
                val result = engine.synthesizeToFile(chunk, Bundle(), outFile, "xiaobu")
                if (result != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("synthesizeToFile 返回 $result")
                }
                val finished = wait.latch.await(chunkTimeoutMs(chunk), TimeUnit.MILLISECONDS)
                if (isCancelled) return@withContext null
                if (wait.error != null) throw IllegalStateException(wait.error)
                if (!finished) throw IllegalStateException("合成超时")
                if (!outFile.exists() || outFile.length() <= 44) {
                    throw IllegalStateException("引擎无音频输出")
                }
                readWavDataChunk(outFile)
            } finally {
                currentWait.getAndSet(null)
                outFile.delete()
            }
        }

    /** 解析标准 RIFF/WAVE 容器，返回 data 块 PCM（找不到时按 44 字节头兜底） */
    private fun readWavDataChunk(file: File): ByteArray {
        val bytes = file.inputStream().use { it.readBytes() }
        if (bytes.size < 44) return bytes
        var i = 12 // RIFF(12) 后开始遍历子块
        while (i + 8 <= bytes.size) {
            val chunkId = String(bytes, i, 4, Charsets.US_ASCII)
            val size = (bytes[i + 4].toInt() and 0xFF) or
                ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                ((bytes[i + 6].toInt() and 0xFF) shl 16) or
                ((bytes[i + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                val start = i + 8
                val end = minOf(start + size, bytes.size)
                return if (start < end) bytes.copyOfRange(start, end) else ByteArray(0)
            }
            i += 8 + size + (size and 1) // 子块按 2 字节对齐
        }
        return bytes.copyOfRange(44, bytes.size)
    }

    override fun stop() {
        logInfo("stop called")
        isCancelled = true
        synthesisJob?.cancel()
        synthesisJob = null
        runCatching { sharedTts?.stop() }
    }

    override fun release() {
        logInfo("release called")
        isCancelled = true
        synthesisJob?.cancel()
        synthesisJob = null
        super.release()
    }
}
