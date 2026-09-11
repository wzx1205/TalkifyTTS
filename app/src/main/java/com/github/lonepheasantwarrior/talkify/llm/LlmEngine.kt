package com.github.lonepheasantwarrior.talkify.llm

import android.content.Context
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端上 LLM 引擎（单例）
 *
 * 持有唯一的 [LlamaBridge.Session]。理由与本地 TTS 引擎一致：
 * 模型常驻约 700MB（权重 + KV），多方各持一份会内存翻倍并争抢 CPU。
 *
 * 内存生命周期：空闲 [IDLE_TIMEOUT_MS] 后自动释放会话归还内存
 * （与 LocalModelProvider 的引擎空闲释放同一模式），下次听书再加载；
 * 收到系统内存压力信号时立即释放（见 TalkifyApplication.onTrimMemory）。
 *
 * 释放与推理的竞态：native 侧生成无法安全打断，直接 close 会话会 use-after-free。
 * 用 [inFlight] 计数保护——generate 在锁内先计数再取会话，释放路径发现计数 >0 就跳过
 * （空闲路径下次 generate 会重新调度；手动释放跳过后由下次 obtain 自然换新）。
 *
 * 超时依赖 native 回调：llama.cpp 的生成无法从外部安全打断，
 * 但我们的 JNI [LlamaBridge.TokenCallback] 返回 false 会让它主动停下。
 * 因此把"检查是否超时"放进回调，比用线程强杀干净得多。
 */
object LlmEngine {

    private const val TAG = "LlmEngine"

    /** 模型存放目录：filesDir/models/llm/ */
    private const val MODEL_SUBDIR = "models/llm"

    /** 会话空闲释放超时：与 TTS 引擎空闲释放保持同一节奏（5 分钟） */
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

    private val lock = Any()

    /** 空闲/延迟释放挂这个 scope：不随任何调用方生命周期失效 */
    private val opsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var idleJob: kotlinx.coroutines.Job? = null

    /** 进行中的推理数：释放路径据此避开正在生成的会话 */
    private val inFlight = AtomicInteger(0)

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

    /**
     * 生成文本；任何异常/超时/模型缺失都返回 null，绝不抛出
     *
     * 超时上限**只覆盖生成阶段**：首次调用需要加载模型（数秒），
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
        // 计数与取会话必须在同一锁内完成，保证"释放路径看到的 inFlight"
        // 已覆盖所有持有会话引用的调用方
        val s = synchronized(lock) {
            cancelIdleRelease()
            val opened = obtain(context, modelId)
            if (opened != null) inFlight.incrementAndGet()
            opened
        } ?: return null
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
        } finally {
            inFlight.decrementAndGet()
            scheduleIdleRelease()
        }
    }

    /** 释放会话（切换设置或内存吃紧时调用）；推理进行中则跳过（native 无法安全打断） */
    fun release() {
        synchronized(lock) { releaseIfSafeLocked("manual") }
    }

    /**
     * 内存压力下的释放：close 一次要拆几百 MB native 内存，
     * 不在回调线程（主线程）做，抛到后台执行
     */
    fun releaseOnMemoryPressure() {
        opsScope.launch { release() }
    }

    // ---- 空闲释放 ----

    private fun scheduleIdleRelease() {
        synchronized(lock) {
            idleJob?.cancel()
            idleJob = opsScope.launch {
                delay(IDLE_TIMEOUT_MS)
                synchronized(lock) { releaseIfSafeLocked("idle ${IDLE_TIMEOUT_MS}ms") }
            }
        }
    }

    /** 调用方必须已持有 [lock] */
    private fun cancelIdleRelease() {
        idleJob?.cancel()
        idleJob = null
    }

    /** 调用方必须已持有 [lock]；推理进行中跳过释放，避免 use-after-free */
    private fun releaseIfSafeLocked(reason: String) {
        if (inFlight.get() > 0) return
        val s = session ?: return
        TtsLogger.i("LLM session released ($reason)", tag = TAG)
        session = null
        loadedPath = null
        s.close()
    }
}
