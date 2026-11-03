package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.qnn.QnnProbe
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * QNN HTP NPU 探针测试：在 App 进程上下文里跑 code_predictor 的 QNN 模型库。
 *
 * 前置（adb 手工准备，探针失败会给明确步骤名）：
 *   1. QNN 运行时 + skel 已随 APK 打进 nativeLibraryDir（jniLibs）
 *   2. 模型库 libcp_sim.so 推到 App 私有 files/qnn/（run-as）
 */
@RunWith(AndroidJUnit4::class)
class QnnHtpProbeTest {

    private val tag = "QnnHtpProbeTest"

    @Test
    fun probeCodePredictorOnHtp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val modelLib = File(ctx.filesDir, "qnn/libcp_sim.so")
        Log.i(tag, "nativeDir=$nativeDir modelLib=${modelLib.absolutePath} exists=${modelLib.exists()} len=${modelLib.length()}")
        if (!modelLib.exists()) {
            Log.w(tag, "model lib not pushed; push via: adb push libcp_sim.so /data/local/tmp/ && adb shell run-as ${ctx.packageName} cp /data/local/tmp/libcp_sim.so files/qnn/")
            return
        }
        val result = QnnProbe.runHtpProbe(nativeDir, modelLib.absolutePath, iters = 50)
        Log.i(tag, "RESULT: $result")
        // 不做硬断言：探针的意义是拿到数据,失败信息本身就是要的结果
    }

    /** v3:context binary 加载路线(最终路线,dlc-prepare 的产物)。 */
    @Test
    fun probeContextBinaryOnHtp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val ctxFile = File(ctx.filesDir, "qnn/cp_htp_final.ctx")
        Log.i(tag, "ctx=${ctxFile.absolutePath} exists=${ctxFile.exists()} len=${ctxFile.length()}")
        if (!ctxFile.exists()) {
            Log.w(tag, "ctx not pushed; adb push cp_htp_final.ctx.bin /data/local/tmp/ && run-as cp to files/qnn/")
            return
        }
        val result = QnnProbe.runContextBinaryProbe(nativeDir, ctxFile.absolutePath, iters = 100)
        Log.i(tag, "RESULT3: $result")
    }

    /** v4:vocoder(tok_decoder) context binary,25 帧(2s 音频)整块解码计时。 */
    @Test
    fun probeVocoderOnHtp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val ctxFile = File(ctx.filesDir, "qnn/voc_htp.ctx")
        Log.i(tag, "ctx=${ctxFile.absolutePath} exists=${ctxFile.exists()} len=${ctxFile.length()}")
        if (!ctxFile.exists()) {
            Log.w(tag, "ctx not pushed; adb push voc_htp.ctx /data/local/tmp/ && run-as cp to files/qnn/")
            return
        }
        val result = QnnProbe.runContextBinaryProbe(nativeDir, ctxFile.absolutePath, iters = 20)
        Log.i(tag, "RESULT4: $result")
    }

    /** v5:predictor 单步 AR 图(KV-cache 全量 IO),16 次 execute ≈ 1 帧。 */
    @Test
    fun probeCpStepOnHtp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val ctxFile = File(ctx.filesDir, "qnn/cp_step_q.ctx")
        Log.i(tag, "ctx=${ctxFile.absolutePath} exists=${ctxFile.exists()} len=${ctxFile.length()}")
        if (!ctxFile.exists()) {
            Log.w(tag, "ctx not pushed; run-as cp to files/qnn/")
            return
        }
        val result = QnnProbe.runContextBinaryProbe(nativeDir, ctxFile.absolutePath, iters = 100)
        Log.i(tag, "RESULT5: $result")
    }

    /** v6:predictor 全展开 16 步 AR 图(图内贪心采样),1 execute = 1 帧。 */
    @Test
    fun probeCpFullOnHtp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val ctxFile = File(ctx.filesDir, "qnn/cp_full_q.ctx")
        Log.i(tag, "ctx=${ctxFile.absolutePath} exists=${ctxFile.exists()} len=${ctxFile.length()}")
        if (!ctxFile.exists()) {
            Log.w(tag, "ctx not pushed; run-as cp to files/qnn/")
            return
        }
        val result = QnnProbe.runContextBinaryProbe(nativeDir, ctxFile.absolutePath, iters = 100)
        Log.i(tag, "RESULT6: $result")
    }

    /** v2:量化 DLC 在 App 域内离线 Prepare 到 HTP + execute 计时(主路线)。 */
    @Test
    fun probeDlcOnHtp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val dlc = File(ctx.filesDir, "qnn/cp_int8.dlc")
        Log.i(tag, "dlc=${dlc.absolutePath} exists=${dlc.exists()} len=${dlc.length()}")
        if (!dlc.exists()) {
            Log.w(tag, "DLC not pushed: adb push cp_int8.dlc /data/local/tmp/ && adb shell run-as ${ctx.packageName} cp /data/local/tmp/cp_int8.dlc files/qnn/")
            return
        }
        val result = QnnProbe.runDlcProbe(nativeDir, dlc.absolutePath, iters = 100)
        Log.i(tag, "RESULT2: $result")
    }
}
