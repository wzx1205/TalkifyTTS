package com.github.lonepheasantwarrior.talkify.llm

import android.content.Context
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 端上 LLM 模型下载器
 *
 * 只负责把一个 gguf 拉到 [LlmEngine.modelFile] 约定的位置，支持断点续传
 * 与国内镜像回退。与 TTS 模型下载（LocalModelDownloadService）分开：
 * 后者绑定 LocalModelInfo/registry 的解压与布局归一化，LLM 是单文件直下，
 * 套用那套流程反而要伪造一堆元数据。
 */
object LlmModelDownloader {

    private const val TAG = "LlmModelDownloader"

    /** 魔搭（ModelScope）国内直下优先，失败再走 HF 镜像/源站 */
    private const val MODELSCOPE = "https://www.modelscope.cn"
    private const val HF_MIRROR = "https://hf-mirror.com"
    private const val HF_ORIGIN = "https://huggingface.co"

    /**
     * 社区量化 bartowski/Qwen_Qwen3.5-0.8B-GGUF 的 Q4_K_M。
     *
     * 与 0.6B 相比指令跟随/中文对白抽取更稳；魔搭已同步同名仓库，
     * 国内优先走魔搭 resolve 链，HF 仅作回退。
     */
    private const val MODELSCOPE_REPO = "bartowski/Qwen_Qwen3.5-0.8B-GGUF"
    private const val MODEL_FILE = "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf"
    private const val HF_REPO_PATH = "bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/$MODEL_FILE"

    /** 579_615_840 ≈ 553MB（魔搭/HF 实测 LFS 体积） */
    const val EXPECTED_SIZE_BYTES = 579_615_840L

    val candidateUrls: List<String> = listOf(
        "$MODELSCOPE/models/$MODELSCOPE_REPO/resolve/master/$MODEL_FILE",
        "$MODELSCOPE/api/v1/models/$MODELSCOPE_REPO/repo?Revision=master&FilePath=$MODEL_FILE",
        "$HF_MIRROR/$HF_REPO_PATH",
        "$HF_ORIGIN/$HF_REPO_PATH"
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载模型到 [LlmEngine.modelFile]
     *
     * @param onProgress (已下载字节, 总字节) -> Unit
     * @param isCancelled 轮询取消
     * @return 成功与否；失败会清理半成品
     */
    fun download(
        context: Context,
        modelId: String = LlmBookConfig.DEFAULT_MODEL_ID,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val target = LlmEngine.modelFile(context, modelId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")

        for (url in candidateUrls) {
            if (isCancelled()) return false
            TtsLogger.i("Downloading LLM model from: $url", tag = TAG)
            if (tryDownload(url, temp, onProgress, isCancelled)) {
                if (temp.renameTo(target)) {
                    TtsLogger.i("LLM model ready: ${target.absolutePath}", tag = TAG)
                    return true
                }
                TtsLogger.e("Failed to move temp to ${target.absolutePath}", tag = TAG)
            }
        }
        temp.delete()
        TtsLogger.e("All LLM model sources failed", tag = TAG)
        return false
    }

    private fun tryDownload(
        url: String,
        temp: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TalkifyTTS/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TtsLogger.e("HTTP ${response.code} for $url", tag = TAG)
                    return@use false
                }
                val body = response.body ?: return@use false
                val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_SIZE_BYTES
                var downloaded = 0L
                var lastNotify = 0L

                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (!isCancelled()) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 200) {
                                lastNotify = now
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                if (isCancelled()) return@use false
                onProgress(downloaded, total)
                downloaded >= total * 0.99
            }
        } catch (e: Exception) {
            TtsLogger.e("Download failed from $url: ${e.message}", throwable = e, tag = TAG)
            false
        }
    }
}
