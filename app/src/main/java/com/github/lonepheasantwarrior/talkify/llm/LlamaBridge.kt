package com.github.lonepheasantwarrior.talkify.llm

import android.util.Log

/**
 * 手写 JNI 桥的 Kotlin 门面：加载 llama.cpp 推理内核，提供"喂 prompt、收流式 token"的最小能力。
 *
 * 生命周期：init() 拿 handle，用完必须 close()。generate() 是阻塞式，务必放在 IO 线程；
 * 回调 [onToken] 在调用线程触发，返回 false 可中途取消。
 */
object LlamaBridge {

    private const val TAG = "TalkifyLLM"

    @Volatile private var nativeReady = false
    @Volatile private var loadError: String? = null

    /** 首次调用时尝试加载 .so；失败不抛异常，只记录，便于上层降级到规则引擎 */
    fun ensureLoaded(): Boolean {
        if (nativeReady) return true
        if (loadError != null) return false
        return synchronized(this) {
            if (nativeReady) return true
            if (loadError == null) {
                try {
                    System.loadLibrary("talkify_llm")
                    nativeReady = true
                } catch (t: Throwable) {
                    loadError = t.message ?: t.javaClass.simpleName
                    Log.e(TAG, "loadLibrary(talkify_llm) failed: $loadError")
                }
            }
            nativeReady
        }
    }

    val isAvailable: Boolean get() = ensureLoaded()
    val lastError: String? get() = loadError

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        onToken: TokenCallback?
    ): String
    private external fun nativeFree(handle: Long)

    /** 流式回调；返回 false 立即停止生成。用 interface 而非 lambda，保证 JNI 签名确定。 */
    fun interface TokenCallback {
        fun onToken(token: String): Boolean
    }

    /**
     * 打开一个模型。失败返回 null（文件不存在 / 格式不支持 / 内存不足都会走到这）。
     */
    fun open(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4): Session? {
        if (!ensureLoaded()) return null
        val h = try {
            nativeInit(modelPath, nCtx, nThreads)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeInit threw: ${t.message}")
            0L
        }
        if (h == 0L) {
            Log.e(TAG, "nativeInit returned 0 for $modelPath")
            return null
        }
        return Session(h)
    }

    class Session internal constructor(private var handle: Long) {

        val isValid: Boolean get() = handle != 0L

        /**
         * 同步生成；[onToken] 每次拿到一段完整 UTF-8 文本，返回 false 立即停止。
         * @return 生成的完整文本
         */
        fun generate(
            prompt: String,
            maxTokens: Int = 256,
            temperature: Float = 0.3f,
            topP: Float = 0.9f,
            onToken: TokenCallback? = null
        ): String {
            val h = handle
            if (h == 0L) return ""
            return try {
                nativeGenerate(h, prompt, maxTokens, temperature, topP, onToken)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeGenerate threw: ${t.message}")
                ""
            }
        }

        fun close() {
            val h = handle
            if (h != 0L) {
                handle = 0L
                try {
                    nativeFree(h)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeFree threw: ${t.message}")
                }
            }
        }
    }
}
