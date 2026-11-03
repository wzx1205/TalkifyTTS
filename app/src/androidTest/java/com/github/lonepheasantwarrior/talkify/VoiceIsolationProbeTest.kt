package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.QwenTtsEngine
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.WavSampleReader
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 音色隔离探针:同一引擎分别用男/女参考音色合成两句,
 * 落盘两个 wav 供 PC 端 F0/噪声分析 —— 回答"是否所有音色都变成同一个声音"
 * 以及"杂音来自 NPU 声码器还是引擎本身"。
 */
@RunWith(AndroidJUnit4::class)
class VoiceIsolationProbeTest {

    private val tag = "VoiceIso"

    private fun deployedDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null), "tts_models/qwen3_tts_06b_base/deployed")
    }

    private fun modelInfo(): LocalModelInfo =
        LocalModelRegistry.getModel("qwen3_tts_06b_base")!!

    private fun ref(name: String): WavSampleReader.WavSamples {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.assets.open("voices/$name").use { return WavSampleReader.read(it) }
    }

    private fun writeWav(out: File, chunks: List<Pair<ByteArray, Int>>) {
        val dataLen = chunks.sumOf { it.first.size }
        java.io.RandomAccessFile(out, "rw").use { raf ->
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(24000)
            header.putInt(24000 * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataLen)
            raf.write(header.array())
            chunks.forEach { raf.write(it.first) }
        }
    }

    @Test
    fun maleVsFemaleVoiceIsolation() {
        val engine = QwenTtsEngine(modelInfo(), deployedDir())
        engine.initialize()
        try {
            val cases = listOf(
                "zh_male_liufei_uranus_bigtts" to "陈平安把剑背到身后，低声说道：山路难走，诸位跟紧了。",
                "zh_female_vv_uranus_bigtts" to "宁姚看着远处的灯火，轻声说道：等这件事了结，我带你去看看真正的剑炉。"
            )
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
            for ((voice, text) in cases) {
                val chunks = ArrayList<Pair<ByteArray, Int>>()
                val t = System.nanoTime()
                engine.synthesizeStream(text, ref("$voice.wav").samples, 24000, "", 1.0f) { pcm, sr ->
                    chunks.add(pcm to sr); true
                }
                val ms = (System.nanoTime() - t) / 1_000_000
                val out = File(dir, "voice_iso_$voice.wav")
                writeWav(out, chunks)
                Log.i(tag, "VOICE $voice ms=$ms bytes=${chunks.sumOf { it.first.size }} file=${out.name}")
            }
        } finally {
            engine.release()
        }
    }
}
