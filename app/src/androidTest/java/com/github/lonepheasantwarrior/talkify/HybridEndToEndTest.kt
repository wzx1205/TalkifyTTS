package com.github.lonepheasantwarrior.talkify

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.lonepheasantwarrior.talkify.book.config.BookTtsSettings
import com.github.lonepheasantwarrior.talkify.book.pipeline.DialogueAnalyzer
import com.github.lonepheasantwarrior.talkify.domain.model.HybridConfig
import com.github.lonepheasantwarrior.talkify.llm.LlmBookConfig
import com.github.lonepheasantwarrior.talkify.service.provider.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.provider.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.service.provider.impl.HybridProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 混合引擎端到端（真机）：复刻用户实际场景——
 * 书模式开启 + 角色册旁白绑定「冰糖」+ 对白绑定 Edge 音色，
 * 验证旁白经 MiMo 真实出声、角色走 Edge，整段不斷流。
 *
 * 依赖手机网络可达 api.xiaomimimo.com（旁白走 MiMo 在线 API）。
 */
@RunWith(AndroidJUnit4::class)
class HybridEndToEndTest {

    private val tag = "HybridE2E"

    @Test
    fun narrationViaMimoDialogueViaEdge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 测试聚焦混合引擎链路本身：关 LLM 分析（避免 7s/段的分析延迟干扰计时）
        val llmWasOn = LlmBookConfig.isEnabled()
        LlmBookConfig.setEnabled(false)
        BookTtsSettings.setEnabled(true)
        DialogueAnalyzer.resetSession()

        val chunks = AtomicInteger(0)
        val done = CountDownLatch(1)
        val failed = CountDownLatch(1)

        val listener = object : TtsSynthesisListener {
            override fun onSynthesisStarted() { Log.i(tag, "STARTED") }
            override fun onAudioAvailable(audioData: ByteArray, sampleRate: Int, audioFormat: Int, channelCount: Int) {
                chunks.addAndGet(audioData.size)
            }
            override fun onSynthesisCompleted() { Log.i(tag, "COMPLETED"); done.countDown() }
            override fun onError(error: String) {
                Log.e(tag, "ERROR: $error")
                failed.countDown()
            }
        }

        val provider = HybridProvider()
        try {
            provider.synthesize(
                text = "裴钱咬了咬牙，低声道：“师父，我饿了。” 陈平安笑道：“前面就是小镇，忍一忍。”",
                params = SynthesisParams(),
                config = HybridConfig(voiceId = "冰糖"),
                listener = listener
            )
            assertTrue("timed out waiting for synthesis", done.await(90, TimeUnit.SECONDS))
            assertEquals("synthesis reported error", 1L, failed.count)
            val bytes = chunks.get()
            Log.i(tag, "TOTAL_PCM_BYTES=$bytes")
            assertTrue("no audio delivered", bytes > 10000)
        } finally {
            provider.release()
            LlmBookConfig.setEnabled(llmWasOn)
            BookTtsSettings.setEnabled(false)
        }
    }
}
