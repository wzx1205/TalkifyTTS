package com.github.lonepheasantwarrior.talkify.llm

import android.content.Context
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import java.io.File

/**
 * 端上 LLM 引擎（单例）
 *
 * 持有唯一的 [LlamaBridge.Session]。理由与本地 TTS 引擎一致：
 * 模型常驻 ~460MB，多方各持一份会内存翻倍并争抢 CPU。
 *
 * 超时依赖 native 回调：llama.cpp 的生成无法从外部安全打断，
 * 但我们的 JNI [LlamaBridge.TokenCallback] 返回 false 会让它主动停下。
 * 因此把"检查是否超时"放进回调，比用线程强杀干净得多。
 */
object LlmEngine {

    private const val TAG = "LlmEngine"

    /** 模型存放目录：filesDir/models/llm/ */
    private const val MODEL_SUBDIR = "models/llm"

    private val lock = Any()

    @Volatile
    private var session: LlamaBridge.Session? = null

    @Volatile
    private var loadedPath: String? = null

    /** 模型文件名约定：<modelId>.gguf */
    fun modelFile(context: Context, modelId: String): File =
        File(File(context.filesDir, MODEL_SUBDIR), "$modelId.gguf")

    fun isModelReady(context: Context, modelId: String = LlmBookConfig.getModelId()): Boolean {
        val f = modelFile(context, modelId)
        return f.exists() && f.length() > 0
    }

    /**
     * 取会话；模型缺失/加载失败/本机不支持时返回 null（调用方回退规则结果）
     */
    private fun obtain(context: Context, modelId: String): LlamaBridge.Session? {
        synchronized(lock) {
            val path = modelFile(context, modelId).absolutePath
            session?.let { if (loadedPath == path && it.isValid) return it }

            // 换模型或首次：释放旧的
            session?.close()
            session = null
            loadedPath = null

            val opened = LlamaBridge.open(
                modelPath = path,
                nCtx = 2048,
                nThreads = LlmBookConfig.getThreads()
            ) ?: run {
                TtsLogger.w("LLM model unavailable: $path (${LlamaBridge.lastError ?: "open failed"})", tag = TAG)
                return null
            }
            session = opened
            loadedPath = path
            TtsLogger.i("LLM session ready: $modelId", tag = TAG)
            return opened
        }
    }

    /**
     * 生成文本；任何异常/超时/模型缺失都返回 null，绝不抛出
     *
     * 超时上限**只覆盖生成阶段**：首次调用需要加载约 460MB 模型（数秒），
     * 把加载算进预算会导致提示词还没处理完就被截断成半截 JSON。
     *
     * @param expectedObjects 期望的 JSON 对象个数（>0 时启用"收够即停"）。
     *   小模型给出完整答案后常继续刷结束符，直到 maxTokens/超时为止——
     *   实测这会让一次分析从 3 秒拖到 12 秒。用已闭合对象数作为停止条件，
     *   比匹配 `]`/`</s>` 可靠（模型会漏 `]`、会把结束符渲染成空串）。
     * @param timeoutMs 生成阶段超时上限，到点通过回调中止
     */
    fun generate(
        context: Context,
        prompt: String,
        maxTokens: Int = 320,
        timeoutMs: Long = LlmBookConfig.getTimeoutMs(),
        modelId: String = LlmBookConfig.getModelId(),
        expectedObjects: Int = 0
    ): String? {
        val s = obtain(context, modelId) ?: return null
        // 计时从"模型已就绪"开始，模型加载耗时不计入生成预算
        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var depth = 0
        var closedObjects = 0
        return try {
            val out = s.generate(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = 0.1f,
                topP = 0.9f,
                onToken = LlamaBridge.TokenCallback { piece ->
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true
                        return@TokenCallback false
                    }
                    if (expectedObjects > 0) {
                        for (c in piece) {
                            when (c) {
                                '{' -> depth++
                                '}' -> if (depth > 0) {
                                    depth--
                                    // 只在回到最外层时计一个完整对象，
                                    // 这样模型多吐的孤立 `}` 不会让计数提前
                                    if (depth == 0) closedObjects++
                                }
                            }
                        }
                        if (closedObjects >= expectedObjects) {
                            return@TokenCallback false
                        }
                    }
                    true
                }
            )
            if (timedOut) {
                TtsLogger.w("LLM generation timed out after ${timeoutMs}ms, partial=${out.length} chars", tag = TAG)
            }
            out
        } catch (e: Exception) {
            TtsLogger.e("LLM generate failed", throwable = e, tag = TAG)
            null
        }
    }

    /** 释放会话（切换设置或内存吃紧时调用） */
    fun release() {
        synchronized(lock) {
            session?.close()
            session = null
            loadedPath = null
        }
    }
}
