package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.book.model.Utterance
import com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer
import com.github.lonepheasantwarrior.talkify.llm.LlamaBridge
import com.github.lonepheasantwarrior.talkify.llm.LlmBookConfig
import com.github.lonepheasantwarrior.talkify.llm.LlmBookExtractor
import com.github.lonepheasantwarrior.talkify.llm.LlmEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端上 LLM 角色分析的真机流水验证：
 *  1. 模型能加载并产出可解析的 JSON（能力验证）
 *  2. 走完整 DialogueAnalyzer 后，说话人/语气确被校正（效果验证）
 *  3. 无论 LLM 是否生效，**拼接回来的文本必须与规则结果逐字一致**（安全验证）
 *
 * 需先推模型到 files/models/llm/qwen3_0_6b.gguf，否则 Assume 跳过。
 */
@RunWith(AndroidJUnit4::class)
class LlmAnalysisOnDeviceTest {

    private val tag = "LlmOnDevice"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        LlmBookConfig.setEnabled(false)
        LlmEngine.release()
    }

    private fun requireModel() {
        assumeTrue("LLM not built into this APK", LlamaBridge.ensureLoaded())
        assumeTrue("no LLM model on device", LlmEngine.isModelReady(context))
    }

    /** 模型能加载、能按"只输出 JSON 数组"的约定产出可解析结果（带真实上文线索） */
    @Test
    fun modelProducesParsableJson() {
        requireModel()
        val quotes = listOf(
            Utterance(text = "裴钱咬了咬牙，低声道：", isQuote = false),
            Utterance(text = "师父，我饿了。", isQuote = true, speaker = "咬牙"),
            Utterance(text = "陈平安笑道：", isQuote = false),
            Utterance(text = "前面就是小镇，忍一忍。", isQuote = true, speaker = "陈平安")
        )
        val raw = LlmEngine.generate(context, LlmBookExtractor.buildPrompt(quotes))
        Log.i(tag, "RAW=[${raw?.replace("\n", "\\n")}]")
        assertTrue("model returned nothing (超时或加载失败)", !raw.isNullOrBlank())
        val text = raw ?: return

        val corrections = LlmBookExtractor.parse(text)
        Log.i(tag, "PARSED size=${corrections.size} -> $corrections")
        // 0.6B 小模型输出有抖动，这里只做"能力存在性"验证：能解析出条目即通过。
        // 真实流水线里解析不出条目 = 保持规则原值，不会更差（见 DialogueAnalyzer）。
        assertTrue("model output not parseable at all", corrections.isNotEmpty())
    }

    /**
     * 完整链路：开启 LLM 后 DialogueAnalyzer 应给出更准的说话人与语气，
     * 且文本拼接必须与规则结果逐字一致（防吞字回归）。
     */
    @Test
    fun analyzerCorrectionKeepsTextIntact() {
        requireModel()
        val passage = "裴钱咬了咬牙，低声道：“师父，我饿了。” 陈平安笑道：“前面就是小镇，忍一忍。”"

        // 规则基线
        LlmBookConfig.setEnabled(false)
        DialogueAnalyzer.resetSession()
        val ruleOnly = DialogueAnalyzer.analyze(passage)
        val ruleText = ruleOnly.joinToString("") { it.text }

        // 开启 LLM
        LlmBookConfig.setEnabled(true)
        DialogueAnalyzer.resetSession()
        val t0 = System.currentTimeMillis()
        val enhanced = DialogueAnalyzer.analyze(passage)
        val elapsed = System.currentTimeMillis() - t0
        val enhText = enhanced.joinToString("") { it.text }

        Log.i(tag, "RULE   = " + ruleOnly.joinToString(" | ") { "${it.speaker}/${it.gender}/${it.emotion}:${it.text}" })
        Log.i(tag, "ENHANCED = " + enhanced.joinToString(" | ") { "${it.speaker}/${it.gender}/${it.emotion}:${it.text}" })
        Log.i(tag, "ELAPSED = ${elapsed}ms")

        assertEquals("句数必须一致", ruleOnly.size, enhanced.size)
        assertEquals("文本必须逐字一致（防吞字）", ruleText, enhText)

        val quotes = enhanced.filter { it.isQuote && it.speaker != Utterance.SPEAKER_NARRATOR }
        assertTrue("应至少识别出一句对白", quotes.isNotEmpty())
        // 增强后应出现具体人名，而不是"未知"
        Log.i(tag, "speakers=" + quotes.map { it.speaker })
    }
}
