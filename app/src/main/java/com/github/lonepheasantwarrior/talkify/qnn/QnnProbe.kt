package com.github.lonepheasantwarrior.talkify.qnn

import android.util.Log

/**
 * QNN HTP(高通 Hexagon NPU)探针门面
 *
 * 在 App 进程里加载 QNN HTP 后端 + QNN 模型库,合成图并实测每帧推理耗时。
 * native 实现仅在本机装有 QAIRT SDK 时编译(qnn_probe.cpp 的 CMake 条件),
 * 其他环境调用会抛 NoSuchMethodError / UnsatisfiedLinkError——调用方捕获降级。
 */
object QnnProbe {

    private const val TAG = "QnnHtpProbe"

    fun runHtpProbe(nativeLibDir: String, modelLibPath: String, iters: Int = 50): String? {
        return try {
            System.loadLibrary("talkify_llm")
            // 注意:不要在这里预载 libcdsprpc——nativeRunHtp 需要先 setenv
            // ADSP_LIBRARY_PATH(含 skel 路径)再触发后端 dlopen 链
            nativeRunHtp(nativeLibDir, modelLibPath, iters).also {
                Log.i(TAG, it ?: "null")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probe unavailable: ${t.message}")
            null
        }
    }

    /**
     * v2:QAIRT 高层 API 路线。量化 DLC 直接 build()(App 域内离线 Prepare 到
     * HTP)并计时 execute()。绕开 v1 的两个坑:运行时构图被 ROM 封、
     * 主机 Prepare 与设备架构不匹配——prepare 完全发生在设备上。
     */
    fun runDlcProbe(nativeLibDir: String, dlcPath: String, iters: Int = 50): String? {
        return try {
            System.loadLibrary("talkify_llm")
            nativeRunDlc(nativeLibDir, dlcPath, iters).also {
                Log.i(TAG, it ?: "null")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probe2 unavailable: ${t.message}")
            null
        }
    }

    private external fun nativeRunDlc(nativeLibDir: String, dlcPath: String, iters: Int): String

    /**
     * v3:context binary 路线(最终)。加载离线 Prepare 好的 HTP context,
     * contextCreateFromBinary 直进 NPU,execute 计时。
     */
    fun runContextBinaryProbe(nativeLibDir: String, ctxPath: String, iters: Int = 100): String? {
        return try {
            System.loadLibrary("talkify_llm")
            nativeRunContextBinary(nativeLibDir, ctxPath, iters).also {
                Log.i(TAG, it ?: "null")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "probe3 unavailable: ${t.message}")
            null
        }
    }

    private external fun nativeRunContextBinary(nativeLibDir: String, ctxPath: String, iters: Int): String

    private external fun nativeRunHtp(nativeLibDir: String, modelLibPath: String, iters: Int): String
}
