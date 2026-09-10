package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.SherpaTtsEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MeloTTS 集成冒烟测试：走真实的 [SherpaTtsEngine]，验证
 * LocalModelInfo(architecture=MELO_VITS) → VITS 配置 → 出声 全链路。
 *
 * 需先把 vits-melo-tts-zh_en 模型目录推到 app files/models/melotts_zh_en/，
 * 否则 Assume 跳过（不判失败）。
 */
@RunWith(AndroidJUnit4::class)
class MeloTtsEngineSmokeTest {

    private val tag = "MeloSmoke"

    private fun modelDir(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "models/melotts_zh_en")

    private fun modelInfo(): LocalModelInfo =
        LocalModelRegistry.getModel("melotts_zh_en")!!

    /**
     * 生产布局验证：模型放在 LocalModelManager 认定的下载目录
     * （externalFilesDir/tts_models/<id>/deployed）时能否直接出声。
     * 这是"用户真的点了下载"之后引擎看到的路径。
     *
     * 为避免 shell 侧创建目录导致的属主/存储映射差异，这里由 App 自己
     * 从内部暂存目录复制过去，完整复刻下载服务落盘后的状态。
     */
    @Test
    fun synthesizesFromDeployedLayout() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = modelDir()
        assumeTrue("no source model at ${src.absolutePath}", File(src, "model.onnx").exists())

        val deployed = File(ctx.getExternalFilesDir(null), "tts_models/melotts_zh_en/deployed")
        if (!File(deployed, "model.onnx").exists()) {
            deployed.mkdirs()
            src.copyRecursively(deployed, overwrite = true)
        }
        assumeTrue("deployed copy failed", File(deployed, "model.onnx").exists())

        val engine = SherpaTtsEngine(modelInfo(), deployed)
        try {
            val r = engine.synthesize("生产布局验证：这句话应当被正常念出来。", FloatArray(0), 0, "", 1.0f, 0)
            Log.i(tag, "DEPLOYED sampleRate=${r.sampleRate} bytes=${r.audioData.size}")
            assertTrue("no audio from deployed layout", r.audioData.size > 1000)
        } finally {
            engine.release()
        }
    }

    @Test
    fun registryEntryIsWellFormed() {
        val m = modelInfo()
        assertTrue("architecture should be MELO_VITS", m.architecture.name == "MELO_VITS")
        assertTrue("must declare lexicon.txt", m.requiredLocalFiles.contains("lexicon.txt"))
        assertTrue("must expose a voice", m.voiceList.isNotEmpty())
        Log.i(tag, "model=${m.id} arch=${m.architecture} voices=${m.voiceList.map { it.voiceId }}")
    }

    @Test
    fun synthesizesAudioEndToEnd() {
        val dir = modelDir()
        assumeTrue("no model at ${dir.absolutePath}", dir.isDirectory && File(dir, "model.onnx").exists())

        val engine = SherpaTtsEngine(modelInfo(), dir)
        try {
            val result = engine.synthesize(
                text = "你好，我是本地语音合成。",
                referenceAudio = FloatArray(0),
                referenceSampleRate = 0,
                referenceText = "",
                speed = 1.0f,
                speakerId = 0
            )
            val pcm = result.audioData
            Log.i(tag, "OUT sampleRate=${result.sampleRate} bytes=${pcm.size}")

            assertTrue("no audio produced", pcm.size > 1000)

            // PCM16 小端：算 RMS 与峰值，确认不是静音
            var sumSq = 0.0
            var peak = 0
            var n = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val s = (hi shl 8) or lo
                val v = if (s >= 32768) s - 65536 else s
                sumSq += (v.toDouble() * v)
                if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                n++
                i += 2
            }
            val rms = kotlin.math.sqrt(sumSq / n)
            Log.i(tag, "AUDIO rms=%.1f peak=%d samples=%d dur=%.2fs".format(
                rms, peak, n, n.toDouble() / result.sampleRate))
            assertTrue("audio looks silent (peak=$peak)", peak > 1000)
        } finally {
            engine.release()
        }
    }

    /** 同一引擎连续合成多句，验证复用不会崩（听书是长文本连续合成的场景）。 */
    @Test
    fun synthesizesMultipleUtterancesInARow() {
        val dir = modelDir()
        assumeTrue("no model at ${dir.absolutePath}", dir.isDirectory)

        val engine = SherpaTtsEngine(modelInfo(), dir)
        try {
            val lines = listOf(
                "裴钱咬了咬牙，低声道：“师父，我饿了。”",
                "陈平安笑道：“前面就是小镇，忍一忍。”",
                "老人叹了口气，慢慢说：“这条路，不好走啊。”"
            )
            lines.forEachIndexed { idx, line ->
                val r = engine.synthesize(line, FloatArray(0), 0, "", 1.0f, 0)
                Log.i(tag, "UTTER #$idx bytes=${r.audioData.size}")
                assertTrue("utterance #$idx produced no audio", r.audioData.size > 1000)
            }
        } finally {
            engine.release()
        }
    }
}
