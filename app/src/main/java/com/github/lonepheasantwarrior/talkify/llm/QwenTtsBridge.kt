package com.github.lonepheasantwarrior.talkify.llm

import android.util.Log

/**
 * Qwen3-TTS JNI 桥的门面：llama.cpp backbone + mtmd 音频管线（与 [LlamaBridge]
 * 同住 libtalkify_llm.so，后端初始化在 native 侧做了引用计数，两个桥可共存）。
 *
 * 生命周期：open() 拿 handle，用完必须 close()。synthesize() 阻塞且可能持续
 * 数秒，务必放在 IO 线程；[PcmCallback] 在调用线程触发，返回 false 中途取消。
 */
object QwenTtsBridge {

    private const val TAG = "TalkifyQwenTTS"

    @Volatile private var nativeReady = false
    @Volatile private var loadError: String? = null

    /**
     * 预载系统 OpenCL 加载器（Adreno/Mali GPU 后端依赖）。
     *
     * libtalkify_llm.so 的 DT_NEEDED 含 libOpenCL.so，但部分 ROM（ColorOS 实测）
     * 的应用命名空间搜索路径里解析不到它；而显式按绝对路径加载"公开厂商库"
     * 是允许的——先 System.load 让同 soname 的库进入进程，随后 dlopen
     * libtalkify_llm 时 DT_NEEDED 直接复用，不再走命名空间搜索。
     * 找不到（无 GPU 的设备）时静默跳过，CPU 后端不受影响。
     */
    private fun preloadSystemOpenCl() {
        for (path in listOf(
            "/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/odm/lib64/libOpenCL.so",
            "/system_ext/lib64/libOpenCL.so"
        )) {
            try {
                System.load(path)
                Log.i(TAG, "OpenCL loader preloaded: $path")
                return
            } catch (t: Throwable) {
                // 试下一个路径
            }
        }
        Log.w(TAG, "system libOpenCL.so not preloadable; GPU backend unavailable")
    }

    fun ensureLoaded(): Boolean {
        if (nativeReady) return true
        if (loadError != null) return false
        return synchronized(this) {
            if (nativeReady) return true
            if (loadError == null) {
                preloadSystemOpenCl()
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

    private external fun nativeTtsInit(
        backbonePath: String,
        mmprojPath: String,
        nCtx: Int,
        nThreads: Int,
        useGpu: Boolean,
        vocoderCtxPath: String?,
        nativeDir: String?
    ): Long

    private external fun nativeTtsSynthesize(
        handle: Long,
        text: String,
        lang: String,
        speakerPcm: FloatArray?,
        speakerSampleRate: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        maxFrames: Int,
        callback: PcmCallback?
    ): Int

    private external fun nativeTtsFree(handle: Long)

    /** 增量 PCM 回调（16bit 小端单声道）；返回 false 立即停止生成。 */
    fun interface PcmCallback {
        fun onPcm(pcm16: ByteArray, sampleRate: Int): Boolean
    }

    /**
     * 打开一个 TTS 会话。
     *
     * @param backbonePath 主干 GGUF（qwen3tts 架构）
     * @param mmprojPath 音频管线 GGUF（说话人编码 + codec + 声码器）
     * @param vocoderCtxPath NPU 声码器 context binary（QAIRT HTP 产物，可选；
     *   null 或初始化失败时静默回退内置 ggml 声码器）
     * @param nativeDir 应用 nativeLibraryDir（ADSP_LIBRARY_PATH 搜索 skel 用，
     *   与 vocoderCtxPath 同时提供才启用 NPU 声码器）
     */
    fun open(
        backbonePath: String,
        mmprojPath: String,
        nCtx: Int = 2048,
        nThreads: Int = 4,
        useGpu: Boolean = false,
        vocoderCtxPath: String? = null,
        nativeDir: String? = null
    ): Session? {
        if (!ensureLoaded()) return null
        val h = try {
            nativeTtsInit(backbonePath, mmprojPath, nCtx, nThreads, useGpu, vocoderCtxPath, nativeDir)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeTtsInit threw: ${t.message}")
            0L
        }
        if (h == 0L) {
            Log.e(TAG, "nativeTtsInit returned 0 for $backbonePath")
            return null
        }
        return Session(h)
    }

    class Session internal constructor(private var handle: Long) {

        val isValid: Boolean get() = handle != 0L

        /**
         * 合成一句话，增量回调 PCM。
         *
         * @param speakerPcm 说话人参考音频（24kHz 归一化 float），null 表示不克隆
         * @return 实际采样率；0 表示被回调取消，负值表示失败
         */
        fun synthesize(
            text: String,
            lang: String = "zh",
            speakerPcm: FloatArray? = null,
            speakerSampleRate: Int = 24000,
            temperature: Float = 0.7f,
            topK: Int = 50,
            topP: Float = 0.95f,
            maxFrames: Int = 800,
            onPcm: PcmCallback? = null
        ): Int {
            val h = handle
            if (h == 0L) return -1
            return try {
                nativeTtsSynthesize(
                    h, text, lang, speakerPcm, speakerSampleRate,
                    temperature, topK, topP, maxFrames, onPcm
                )
            } catch (t: Throwable) {
                Log.e(TAG, "nativeTtsSynthesize threw: ${t.message}")
                -1
            }
        }

        fun close() {
            val h = handle
            if (h != 0L) {
                handle = 0L
                try {
                    nativeTtsFree(h)
                } catch (t: Throwable) {
                    Log.e(TAG, "nativeTtsFree threw: ${t.message}")
                }
            }
        }
    }
}
