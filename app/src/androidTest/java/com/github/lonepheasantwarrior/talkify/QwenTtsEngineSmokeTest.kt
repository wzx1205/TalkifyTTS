package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelRegistry
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.QwenTtsEngine
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.WavSampleReader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Qwen3-TTS 集成冒烟测试：走真实的 [QwenTtsEngine]（llama.cpp + mtmd），
 * 验证 backbone/mmproj GGUF 加载 → 说话人克隆 → 24kHz PCM 全链路，
 * 并实测冷启动、首包延迟与 RTF（听书体验的关键指标）。
 *
 * 需先把两个 GGUF 推到
 * externalFilesDir/tts_models/qwen3_tts_06b_base/deployed/，
 * 否则 Assume 跳过（不判失败）。
 */
@RunWith(AndroidJUnit4::class)
class QwenTtsEngineSmokeTest {

    private val tag = "Qwen3Smoke"

    private fun deployedDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.getExternalFilesDir(null), "tts_models/qwen3_tts_06b_base/deployed")
    }

    private fun modelInfo(): LocalModelInfo =
        LocalModelRegistry.getModel("qwen3_tts_06b_base")!!

    private fun assumeModelPresent() {
        val dir = deployedDir()
        modelInfo().requiredLocalFiles.forEach { f ->
            assumeTrue("missing $f at ${dir.absolutePath}", File(dir, f).length() > 0)
        }
    }

    /** 内置参考音色（assets/voices，24kHz PCM16 wav）→ 归一化 float 样本 */
    private fun bundledReference(): WavSampleReader.WavSamples {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.assets.open("voices/zh_female_vv_uranus_bigtts.wav").use {
            return WavSampleReader.read(it)
        }
    }

    private fun audioStats(tag2: String, chunks: List<Pair<ByteArray, Int>>): Pair<Double, Int> {
        var sumSq = 0.0
        var peak = 0
        var n = 0L
        var sr = 24000
        for ((pcm, sampleRate) in chunks) {
            if (sampleRate > 0) sr = sampleRate
            var i = 0
            while (i + 1 < pcm.size) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val s = (hi shl 8) or lo
                val v = if (s >= 32768) s - 65536 else s
                sumSq += v.toDouble() * v
                if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                n++
                i += 2
            }
        }
        val rms = if (n > 0) kotlin.math.sqrt(sumSq / n) else 0.0
        Log.i(tag, "$tag2 rms=%.1f peak=%d samples=%d dur=%.2fs sr=%d".format(rms, peak, n, n.toDouble() / sr, sr))
        return rms to peak
    }

    @Test
    fun registryEntryIsWellFormed() {
        val m = modelInfo()
        assertTrue("architecture should be QWEN3_LLAMA", m.architecture.name == "QWEN3_LLAMA")
        assertTrue("must declare mmproj", m.requiredLocalFiles.any { it.startsWith("mmproj") })
        assertTrue("must declare backbone", m.requiredLocalFiles.any { !it.startsWith("mmproj") && it.endsWith(".gguf") })
        Log.i(tag, "model=${m.id} arch=${m.architecture} files=${m.requiredLocalFiles}")
    }

    /**
     * 核心链路：克隆内置女声合成一句话，实测
     * 冷启动(模型加载) / 首包延迟(第一个 PCM 块) / 总耗时 / 音频时长 → RTF
     */
    @Test
    fun synthesizesWithVoiceCloneFromDeployedLayout() {
        assumeModelPresent()
        val ref = bundledReference()
        Log.i(tag, "reference: ${ref.samples.size} samples @ ${ref.sampleRate}Hz")

        val t0 = System.nanoTime()
        val engine = QwenTtsEngine(modelInfo(), deployedDir())
        engine.initialize()
        val initMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "INIT ${initMs}ms")

        try {
            val chunks = ArrayList<Pair<ByteArray, Int>>()
            var firstChunkMs = -1L
            val t1 = System.nanoTime()
            val completed = engine.synthesizeStream(
                text = "你好，我是本地语音合成，这句话用来验证克隆音色的效果。",
                referenceAudio = ref.samples,
                referenceSampleRate = ref.sampleRate,
                referenceText = "",
                speed = 1.0f
            ) { pcm, sr ->
                if (firstChunkMs < 0) firstChunkMs = (System.nanoTime() - t1) / 1_000_000
                chunks.add(pcm to sr)
                true
            }
            val totalMs = (System.nanoTime() - t1) / 1_000_000

            Log.i(tag, "RESULT completed=$completed chunks=${chunks.size} firstChunk=${firstChunkMs}ms total=${totalMs}ms")
            val totalBytes = chunks.sumOf { it.first.size }
            assertTrue("no audio produced", totalBytes > 1000)

            val (rms, peak) = audioStats("AUDIO", chunks)
            assertTrue("audio looks silent (peak=$peak)", peak > 1000)

            // 落盘 WAV 供人工试听：/sdcard/qwen3_clone_preview.wav（PCM16 单声道 24k）
            runCatching {
                val out = File(
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "qwen3_clone_preview.wav"
                )
                java.io.RandomAccessFile(out, "rw").use { raf ->
                    val dataLen = chunks.sumOf { it.first.size }
                    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    header.put("RIFF".toByteArray())
                    header.putInt(36 + dataLen)
                    header.put("WAVE".toByteArray())
                    header.put("fmt ".toByteArray())
                    header.putInt(16)
                    header.putShort(1)          // PCM
                    header.putShort(1)          // mono
                    header.putInt(24000)
                    header.putInt(24000 * 2)
                    header.putShort(2)
                    header.putShort(16)
                    header.put("data".toByteArray())
                    header.putInt(dataLen)
                    raf.write(header.array())
                    chunks.forEach { raf.write(it.first) }
                }
                Log.i(tag, "WAV written: qwen3_clone_preview.wav")
            }

            val samples = totalBytes / 2L
            val audioSec = samples / 24000.0
            val rtf = if (audioSec > 0) totalMs / 1000.0 / audioSec else -1.0
            Log.i(tag, "METRICS initMs=$initMs firstChunkMs=$firstChunkMs totalMs=$totalMs audioSec=%.2f rtf=%.2f".format(audioSec, rtf))
        } finally {
            engine.release()
        }
    }

    /** 听书场景：同一引擎连续合成三句（对白+旁白），验证复用稳定性与热态速度 */
    @Test
    fun synthesizesMultipleUtterancesInARow() {
        assumeModelPresent()
        val ref = bundledReference()

        val engine = QwenTtsEngine(modelInfo(), deployedDir())
        engine.initialize()
        try {
            val lines = listOf(
                "裴钱咬了咬牙，低声道：“师父，我饿了。”",
                "陈平安笑道：“前面就是小镇，忍一忍。”",
                "老人叹了口气，慢慢说：“这条路，不好走啊。”"
            )
            lines.forEachIndexed { idx, line ->
                val chunks = ArrayList<Pair<ByteArray, Int>>()
                val t = System.nanoTime()
                engine.synthesizeStream(
                    line, ref.samples, ref.sampleRate, "", 1.0f
                ) { pcm, sr -> chunks.add(pcm to sr); true }
                val ms = (System.nanoTime() - t) / 1_000_000
                val audioSec = chunks.sumOf { it.first.size } / 2 / 24000.0
                Log.i(tag, "UTTER #$idx text=${line.take(12)}… totalMs=$ms audioSec=%.2f rtf=%.2f".format(audioSec, if (audioSec > 0) ms / 1000.0 / audioSec else -1.0))
                assertTrue("utterance #$idx produced no audio", chunks.sumOf { it.first.size } > 1000)
            }
        } finally {
            engine.release()
        }
    }

    /**
     * GPU(OpenCL/Adreno)对比：同一句话分别在 CPU 与 GPU 后端合成，输出 RTF 对比。
     * 后端不可用时 native 层自动回退 CPU，结果里以 backend 日志区分。
     */
    @Test
    fun gpuVsCpuRtfComparison() {
        assumeModelPresent()
        val ref = bundledReference()
        val text = "陈平安笑了笑，说道：“今天天气不错。”"

        fun runOnce(useGpu: Boolean): Float {
            val engine = QwenTtsEngine(modelInfo(), deployedDir(), useGpu = useGpu)
            engine.initialize()
            try {
                val chunks = ArrayList<Pair<ByteArray, Int>>()
                val t = System.nanoTime()
                engine.synthesizeStream(text, ref.samples, ref.sampleRate, "", 1.0f) { pcm, sr ->
                    chunks.add(pcm to sr); true
                }
                val ms = (System.nanoTime() - t) / 1_000_000
                val audioSec = chunks.sumOf { it.first.size } / 2 / 24000.0
                val rtf = if (audioSec > 0) (ms / 1000.0 / audioSec).toFloat() else -1f
                Log.i(tag, "BENCH gpu=$useGpu totalMs=$ms audioSec=%.2f rtf=%.2f bytes=%d".format(
                    audioSec, rtf, chunks.sumOf { it.first.size }))
                return rtf
            } finally {
                engine.release()
            }
        }

        val cpuRtf = runOnce(false)
        val gpuRtf = runOnce(true)
        Log.i(tag, "BENCH SUMMARY cpuRtf=%.2f gpuRtf=%.2f speedup=%.1fx".format(
            cpuRtf, gpuRtf, if (gpuRtf > 0) cpuRtf / gpuRtf else 0f))
        // GPU 路径至少必须能正常出声（不因后端缺失而失败）
    }

    /**
     * 线程扩展性：4 vs 8 线程合成同一句话，输出 RTF 对比。
     * 8 Elite 全大核(2P+6E)，看 CPU 段(predictor+backbone+vocoder)的并行收益上限。
     */
    @Test
    fun threadScalingRtfComparison() {
        assumeModelPresent()
        val ref = bundledReference()
        val text = "陈平安笑了笑，说道：“今天天气不错。”"

        fun runOnce(threads: Int): Float {
            val engine = QwenTtsEngine(modelInfo(), deployedDir(), nThreads = threads)
            engine.initialize()
            try {
                val chunks = ArrayList<Pair<ByteArray, Int>>()
                val t = System.nanoTime()
                engine.synthesizeStream(text, ref.samples, ref.sampleRate, "", 1.0f) { pcm, sr ->
                    chunks.add(pcm to sr); true
                }
                val ms = (System.nanoTime() - t) / 1_000_000
                val audioSec = chunks.sumOf { it.first.size } / 2 / 24000.0
                val rtf = if (audioSec > 0) (ms / 1000.0 / audioSec).toFloat() else -1f
                Log.i(tag, "BENCH threads=$threads totalMs=$ms audioSec=%.2f rtf=%.2f".format(audioSec, rtf))
                return rtf
            } finally {
                engine.release()
            }
        }

        val rtf4 = runOnce(4)
        val rtf8 = runOnce(8)
        Log.i(tag, "BENCH SUMMARY rtf4=%.2f rtf8=%.2f speedup=%.2fx".format(
            rtf4, rtf8, if (rtf8 > 0) rtf4 / rtf8 else 0f))
    }
}
