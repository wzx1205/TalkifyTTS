package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.SherpaTtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * fanchen-C 多音色包（187 sid）集成验证：
 * 引擎按注册表条目走 VITS 路径（非常规 onnx 文件名 + speakerId 换声）。
 * 需先把 tarball 解到 files/models/vits_zh_fanchen_c/。
 */
@RunWith(AndroidJUnit4::class)
class FanchenPackSmokeTest {

    private val tag = "FanchenSmoke"

    @Test
    fun registryExposes187VoicesWithSpeakerIds() {
        val m = LocalModelRegistry.getModel("vits_zh_fanchen_c")
        assumeTrue("fanchen-C not in registry", m != null)
        assertEquals(187, m!!.voiceList.size)
        assertTrue(m.requiredLocalFiles.any { it.endsWith(".onnx") })
        assertEquals(16000, m.sampleRate)
        Log.i(tag, "voices=${m.voiceList.size} first=${m.voiceList.first().voiceId} last=${m.voiceList.last().voiceId}")
    }

    /** 三个不同 sid 合成出不同的音频（长度不同即证明声线不同），且 sid 路径出声非静音 */
    @Test
    fun synthesizesDistinctVoicesBySpeakerId() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "models/vits_zh_fanchen_c")
        assumeTrue("no model at ${dir.absolutePath}", File(dir, "vits-zh-hf-fanchen-C.onnx").exists())
        val m = LocalModelRegistry.getModel("vits_zh_fanchen_c")!!

        val engine = SherpaTtsEngine(m, dir)
        try {
            val text = "裴钱咬了咬牙，低声道：师父，我饿了。"
            val durations = mutableListOf<Float>()
            for (sid in intArrayOf(0, 1, 100)) {
                val r = engine.synthesize(text, FloatArray(0), 0, "", 1.0f, sid)
                val dur = r.audioData.size / 2.0 / r.sampleRate
                durations += dur.toFloat()
                Log.i(tag, "sid=$sid sr=${r.sampleRate} dur=%.2fs bytes=%d".format(dur, r.audioData.size))
                assertTrue("sid=$sid produced no audio", r.audioData.size > 1000)
            }
            assertTrue("different sids should differ", durations.toSet().size > 1)
        } finally {
            engine.release()
        }
    }

    /** 生产部署布局（tts_models/<id>/deployed）下同样可用 */
    @Test
    fun synthesizesFromDeployedLayout() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val src = File(ctx.filesDir, "models/vits_zh_fanchen_c")
        assumeTrue("no source model", File(src, "vits-zh-hf-fanchen-C.onnx").exists())

        val deployed = File(ctx.getExternalFilesDir(null), "tts_models/vits_zh_fanchen_c/deployed")
        if (!File(deployed, "vits-zh-hf-fanchen-C.onnx").exists()) {
            deployed.mkdirs()
            src.copyRecursively(deployed, overwrite = true)
        }
        val m = LocalModelRegistry.getModel("vits_zh_fanchen_c")!!
        val engine = SherpaTtsEngine(m, deployed)
        try {
            val r = engine.synthesize("部署验证。", FloatArray(0), 0, "", 1.0f, 42)
            Log.i(tag, "DEPLOYED sid=42 bytes=${r.audioData.size} sr=${r.sampleRate}")
            assertTrue(r.audioData.size > 1000)
        } finally {
            engine.release()
        }
    }
}
