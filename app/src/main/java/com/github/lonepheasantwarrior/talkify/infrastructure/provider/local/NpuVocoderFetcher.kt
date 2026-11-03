package com.github.lonepheasantwarrior.talkify.infrastructure.provider.local

import android.content.Context
import android.net.ConnectivityManager
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Qwen3-TTS NPU 声码器 context binary 的后台补拉器。
 *
 * ctx 不进模型的强制下载清单：GitHub 直连在国内经常不可达，挂进
 * downloadFileInfo 会让拉不到 GitHub 的用户连 GGUF 都下载不了。
 * 改为引擎初始化时后台尽力补拉——失败静默（回退 ggml 声码器），
 * 下次引擎初始化时自动重试；完成后新开的引擎会话即用 NPU 声码器。
 *
 * 仅在非计量网络（默认 Wi-Fi）下载，避免偷跑用户流量。
 */
object NpuVocoderFetcher {

    private const val TAG = "NpuVocoderFetcher"
    private const val URL =
        "https://github.com/wzx1205/TalkifyTTS/releases/download/npu-artifacts-v1/voc_fpo.ctx.bin"
    const val FILE_NAME = "voc_htp.ctx"
    private const val PART_SUFFIX = ".part"

    private val fetchLock = Any()

    @Volatile
    private var fetching = false

    fun targetFile(modelDir: File): File = File(modelDir, FILE_NAME)

    /** 已就绪（文件存在且非空） */
    fun isReady(modelDir: File): Boolean =
        targetFile(modelDir).let { it.exists() && it.length() > 0 }

    /**
     * 后台补拉（非阻塞）。已存在/正在拉取/计量网络时直接返回。
     * 完成后原子落位为 modelDir/voc_htp.ctx，下次引擎初始化即生效。
     */
    fun ensureAsync(modelDir: File) {
        if (isReady(modelDir)) return
        synchronized(fetchLock) {
            if (fetching) return
            fetching = true
        }
        val worker = Thread({
            try {
                fetchBlocking(modelDir)
            } catch (t: Throwable) {
                TtsLogger.w("NPU vocoder fetch failed (ggml fallback kept): ${t.message}", tag = TAG)
            } finally {
                synchronized(fetchLock) { fetching = false }
            }
        }, "npu-vocoder-fetch")
        worker.isDaemon = true
        worker.start()
    }

    private fun fetchBlocking(modelDir: File) {
        val context = TalkifyAppHolder.getContext() ?: return

        // 仅非计量网络：118MB 不偷跑流量
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && cm.isActiveNetworkMetered) {
            TtsLogger.i("NPU vocoder fetch skipped: metered network", tag = TAG)
            return
        }

        val part = File(modelDir, FILE_NAME + PART_SUFFIX)
        part.parentFile?.mkdirs()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        TtsLogger.i("NPU vocoder fetch started: $URL", tag = TAG)
        val request = okhttp3.Request.Builder().url(URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("empty body")
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }
            if (part.length() < 1_000_000) {
                throw IllegalStateException("downloaded file too small: ${part.length()}")
            }
        }
        // 原子落位：先删旧（不完整残留），再重命名
        targetFile(modelDir).delete()
        if (!part.renameTo(targetFile(modelDir))) {
            throw IllegalStateException("rename failed")
        }
        TtsLogger.i(
            "NPU vocoder ready: ${targetFile(modelDir).absolutePath} " +
                "(${targetFile(modelDir).length()} bytes)", tag = TAG
        )
    }
}
